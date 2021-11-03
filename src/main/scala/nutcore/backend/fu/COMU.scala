/**************************************************************************************
 * Copyright (c) 2020 Institute of Computing Technology, CAS
 * Copyright (c) 2020 University of Chinese Academy of Sciences
 *
 * NutShell is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan PSL v2.
 * You may obtain a copy of Mulan PSL v2 at:
 *             http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND, EITHER
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT, MERCHANTABILITY OR
 * FIT FOR A PARTICULAR PURPOSE.
 *
 * See the Mulan PSL v2 for more details.
 ***************************************************************************************/

package nutcore

import chisel3._
import chisel3.util._
import chisel3.util.experimental.BoringUtils

import utils._

object COMUOpType {
  def comadd = "b000".U
  def comsub = "b010".U
  def commul1 = "b101".U      // A(63,32)B(31,0)+A(31,0)B(63,32)
  def commul2 = "b111".U      // A(63,32)B(63,32)-A(31,0)B(31,0)
  def commul3 = "b100".U      // A(63,32)B(63,32)+A(31,0)B(31,0)
  def commul4 = "b110".U      // A(31,0)B(63,32)-A(63,32)B(31,0)

  def isAdd(op:UInt) = op === comadd
  def isSub(op:UInt) = op === comsub
  def isDiff(op:UInt) = op(1)
}

class COMUIO extends FunctionUnitIO {

}

class COMU extends NutCoreModule {
  val io = IO(new COMUIO)

  val (valid, src1, src2, func) = (io.in.valid, io.in.bits.src1, io.in.bits.src2, io.in.bits.func)
  def access(valid: Bool, src1: UInt, src2: UInt, func: UInt): UInt = {
    this.valid := valid
    this.src1 := src1
    this.src2 := src2
    this.func := func
    io.out.bits
  }

  val realA = src1(63, 32)
  val imageA = src1(31, 0)
  val realB = src2(63, 32)
  val imageB = src2(31, 0)

  val isComSub = COMUOpType.isDiff(func)
  val realAdderRes = (realA + (realB ^ Fill(32, isComSub))) + isComSub
  val imageAdderRes = (imageA + (imageB ^ Fill(32, isComSub))) + isComSub

//  val mul1 = Module(new Multiplier(XLEN+1))
//  val mul2 = Module(new Multiplier(XLEN+1))
//
//  mul1.io.in.bits(0) := MuxCase(0.U, Array(
//    (func === COMUOpType.commul1)       -> ZeroExt(SignExt(realA, XLEN), XLEN+1),
//    (func === COMUOpType.commul2)       -> ZeroExt(SignExt(realA, XLEN), XLEN+1),
//    (func === COMUOpType.commul3)       -> ZeroExt(SignExt(realA, XLEN), XLEN+1),
//    (func === COMUOpType.commul4)       -> ZeroExt(SignExt(imageA, XLEN), XLEN+1)
//  ))
//  mul1.io.in.bits(1) := MuxCase(0.U, Array(
//    (func === COMUOpType.commul1)       -> ZeroExt(SignExt(imageB, XLEN), XLEN+1),
//    (func === COMUOpType.commul2)       -> ZeroExt(SignExt(realB, XLEN), XLEN+1),
//    (func === COMUOpType.commul3)       -> ZeroExt(SignExt(realB, XLEN), XLEN+1),
//    (func === COMUOpType.commul4)       -> ZeroExt(SignExt(realB, XLEN), XLEN+1)
//  ))
//
//  mul2.io.in.bits(0) := MuxCase(0.U, Array(
//    (func === COMUOpType.commul1)       -> ZeroExt(SignExt(imageA, XLEN), XLEN+1),
//    (func === COMUOpType.commul2)       -> ZeroExt(SignExt(imageA, XLEN), XLEN+1),
//    (func === COMUOpType.commul3)       -> ZeroExt(SignExt(imageA, XLEN), XLEN+1),
//    (func === COMUOpType.commul4)       -> ZeroExt(SignExt(realA, XLEN), XLEN+1)
//  ))
//  mul2.io.in.bits(1) := MuxCase(0.U, Array(
//    (func === COMUOpType.commul1)       -> ZeroExt(SignExt(realB, XLEN), XLEN+1),
//    (func === COMUOpType.commul2)       -> ZeroExt(SignExt(imageB, XLEN), XLEN+1),
//    (func === COMUOpType.commul3)       -> ZeroExt(SignExt(imageB, XLEN), XLEN+1),
//    (func === COMUOpType.commul4)       -> ZeroExt(SignExt(imageB, XLEN), XLEN+1)
//  ))
  val mul1 = Module(new Multiplier(33))
  val mul2 = Module(new Multiplier(33))

  mul1.io.in.bits(0) := MuxCase(0.U, Array(
    (func === COMUOpType.commul1)       -> ZeroExt(realA, 33),
    (func === COMUOpType.commul2)       -> ZeroExt(realA, 33),
    (func === COMUOpType.commul3)       -> ZeroExt(realA, 33),
    (func === COMUOpType.commul4)       -> ZeroExt(imageA, 33)
  ))
  mul1.io.in.bits(1) := MuxCase(0.U, Array(
    (func === COMUOpType.commul1)       -> ZeroExt(imageB, 33),
    (func === COMUOpType.commul2)       -> ZeroExt(realB, 33),
    (func === COMUOpType.commul3)       -> ZeroExt(realB, 33),
    (func === COMUOpType.commul4)       -> ZeroExt(realB, 33)
  ))

  mul2.io.in.bits(0) := MuxCase(0.U, Array(
    (func === COMUOpType.commul1)       -> ZeroExt(imageA, 33),
    (func === COMUOpType.commul2)       -> ZeroExt(imageA, 33),
    (func === COMUOpType.commul3)       -> ZeroExt(imageA, 33),
    (func === COMUOpType.commul4)       -> ZeroExt(realA, 33)
  ))
  mul2.io.in.bits(1) := MuxCase(0.U, Array(
    (func === COMUOpType.commul1)       -> ZeroExt(realB, 33),
    (func === COMUOpType.commul2)       -> ZeroExt(imageB, 33),
    (func === COMUOpType.commul3)       -> ZeroExt(imageB, 33),
    (func === COMUOpType.commul4)       -> ZeroExt(imageB, 33)
  ))

  mul1.io.in.valid := valid && func(2).asBool()
  mul2.io.in.valid := valid && func(2).asBool()
  mul1.io.out.ready := io.out.ready
  mul2.io.out.ready := io.out.ready
  mul1.io.sign <> DontCare
  mul2.io.sign <> DontCare

  val mul1Res = mul1.io.out.bits(XLEN-1, 0)
  val mul2Res = mul2.io.out.bits(XLEN-1, 0)

  val mulRes = (mul1Res +& (mul2Res ^ Fill(XLEN, isComSub))) + isComSub

  io.out.bits := Mux(func(2).asBool(), mulRes, (realAdderRes << 32) | imageAdderRes)
  io.in.ready := Mux(func(2).asBool(), mul1.io.in.ready && mul2.io.in.ready, io.out.ready)
  io.out.valid := Mux(func(2).asBool(), mul1.io.out.valid && mul2.io.out.valid, valid)
}