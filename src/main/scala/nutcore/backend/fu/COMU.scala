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
  def comadd   = "b0000".U
  def comsub   = "b0010".U
  def comconj  = "b0001".U

  def comcmula = "b0101".U      // A(63,32)B(31,0)+A(31,0)B(63,32)
  def commuls  = "b0111".U      // A(63,32)B(63,32)-A(31,0)B(31,0)
  def commula  = "b0100".U      // A(63,32)B(63,32)+A(31,0)B(31,0)
  def comcmuls = "b0110".U      // A(31,0)B(63,32)-A(63,32)B(31,0)

  // for fixedpoint
  def fcomcmula = "b1101".U
  def fcommuls  = "b1111".U
  def fcommula  = "b1100".U
  def fcomcmuls = "b1110".U

  def isConj(op: UInt) = op === comconj
  def isDiff(op: UInt) = op(1)
  def isFixed(op: UInt) = op(3)
  def isMul(op:UInt) = op(2)
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

  val imageInvert = ~imageA + 1.U(32.W)
  val Aconj = (realA << 32) | imageInvert
  val mul1 = Module(new Multiplier(XLEN+1))
  val mul2 = Module(new Multiplier(XLEN+1))

  mul1.io.in.bits(0) := MuxCase(0.U, Array(
    (func === COMUOpType.comcmula || func === COMUOpType.fcomcmula)       -> ZeroExt(SignExt(realA, XLEN), XLEN+1),
    (func === COMUOpType.commuls  || func === COMUOpType.fcommuls)       -> ZeroExt(SignExt(realA, XLEN), XLEN+1),
    (func === COMUOpType.commula  || func === COMUOpType.fcommula)       -> ZeroExt(SignExt(realA, XLEN), XLEN+1),
    (func === COMUOpType.comcmuls || func === COMUOpType.fcomcmuls)       -> ZeroExt(SignExt(imageA, XLEN), XLEN+1)
  ))
  mul1.io.in.bits(1) := MuxCase(0.U, Array(
    (func === COMUOpType.comcmula || func === COMUOpType.fcomcmula)       -> ZeroExt(SignExt(imageB, XLEN), XLEN+1),
    (func === COMUOpType.commuls  || func === COMUOpType.fcommuls)       -> ZeroExt(SignExt(realB, XLEN), XLEN+1),
    (func === COMUOpType.commula  || func === COMUOpType.fcommula)       -> ZeroExt(SignExt(realB, XLEN), XLEN+1),
    (func === COMUOpType.comcmuls || func === COMUOpType.fcomcmuls)       -> ZeroExt(SignExt(realB, XLEN), XLEN+1)
  ))

  mul2.io.in.bits(0) := MuxCase(0.U, Array(
    (func === COMUOpType.comcmula || func === COMUOpType.fcomcmula)       -> ZeroExt(SignExt(imageA, XLEN), XLEN+1),
    (func === COMUOpType.commuls  || func === COMUOpType.fcommuls)       -> ZeroExt(SignExt(imageA, XLEN), XLEN+1),
    (func === COMUOpType.commula  || func === COMUOpType.fcommula)       -> ZeroExt(SignExt(imageA, XLEN), XLEN+1),
    (func === COMUOpType.comcmuls || func === COMUOpType.fcomcmuls)       -> ZeroExt(SignExt(realA, XLEN), XLEN+1)
  ))
  mul2.io.in.bits(1) := MuxCase(0.U, Array(
    (func === COMUOpType.comcmula || func === COMUOpType.fcomcmula)       -> ZeroExt(SignExt(realB, XLEN), XLEN+1),
    (func === COMUOpType.commuls  || func === COMUOpType.fcommuls)       -> ZeroExt(SignExt(imageB, XLEN), XLEN+1),
    (func === COMUOpType.commula  || func === COMUOpType.fcommula)       -> ZeroExt(SignExt(imageB, XLEN), XLEN+1),
    (func === COMUOpType.comcmuls || func === COMUOpType.fcomcmuls)       -> ZeroExt(SignExt(imageB, XLEN), XLEN+1)
  ))
//  val mul1 = Module(new Multiplier(33))
//  val mul2 = Module(new Multiplier(33))
//
//  mul1.io.in.bits(0) := MuxCase(0.U, Array(
//    (func === COMUOpType.comcmula || func === COMUOpType.fcomcmula)      -> ZeroExt(realA, 33),
//    (func === COMUOpType.commuls  || func === COMUOpType.fcommuls)       -> ZeroExt(realA, 33),
//    (func === COMUOpType.commula  || func === COMUOpType.fcommula)       -> ZeroExt(realA, 33),
//    (func === COMUOpType.comcmuls || func === COMUOpType.fcomcmuls)      -> ZeroExt(imageA, 33)
//  ))
//  mul1.io.in.bits(1) := MuxCase(0.U, Array(
//    (func === COMUOpType.comcmula || func === COMUOpType.fcomcmula)      -> ZeroExt(imageB, 33),
//    (func === COMUOpType.commuls  || func === COMUOpType.fcommuls)       -> ZeroExt(realB, 33),
//    (func === COMUOpType.commula  || func === COMUOpType.fcommula)       -> ZeroExt(realB, 33),
//    (func === COMUOpType.comcmuls || func === COMUOpType.fcomcmuls)      -> ZeroExt(realB, 33)
//  ))
//
//  mul2.io.in.bits(0) := MuxCase(0.U, Array(
//    (func === COMUOpType.comcmula || func === COMUOpType.fcomcmula)      -> ZeroExt(imageA, 33),
//    (func === COMUOpType.commuls  || func === COMUOpType.fcommuls)       -> ZeroExt(imageA, 33),
//    (func === COMUOpType.commula  || func === COMUOpType.fcommula)       -> ZeroExt(imageA, 33),
//    (func === COMUOpType.comcmuls || func === COMUOpType.fcomcmuls)      -> ZeroExt(realA, 33)
//  ))
//  mul2.io.in.bits(1) := MuxCase(0.U, Array(
//    (func === COMUOpType.comcmula || func === COMUOpType.fcomcmula)      -> ZeroExt(realB, 33),
//    (func === COMUOpType.commuls  || func === COMUOpType.fcommuls)       -> ZeroExt(imageB, 33),
//    (func === COMUOpType.commula  || func === COMUOpType.fcommula)       -> ZeroExt(imageB, 33),
//    (func === COMUOpType.comcmuls || func === COMUOpType.fcomcmuls)      -> ZeroExt(imageB, 33)
//  ))

  mul1.io.in.valid := valid && func(2).asBool()
  mul2.io.in.valid := valid && func(2).asBool()
  mul1.io.out.ready := io.out.ready
  mul2.io.out.ready := io.out.ready
  mul1.io.sign <> DontCare
  mul2.io.sign <> DontCare

  val mul1Res = mul1.io.out.bits(XLEN-1, 0)
  val mul2Res = mul2.io.out.bits(XLEN-1, 0)

  val mulRes = SignExt((mul1Res + (mul2Res ^ Fill(XLEN, isComSub))) + isComSub, XLEN)
  val mulResSh = SignExt(((mul1Res.asSInt() >> 16).asUInt() + ((mul2Res.asSInt() >> 16).asUInt() ^ Fill(XLEN, isComSub))) + isComSub, XLEN)

//  when(io.out.fire() && func === COMUOpType.fcommula) {
//    printf("mul1 in is %x, %x\n", mul1.io.in.bits(0), mul1.io.in.bits(1))
//    printf("mul2 in is %x, %x\n", mul2.io.in.bits(0), mul2.io.in.bits(1))
//    printf("mul1 res is %x, mul2 res is %x\n", mul1Res, mul2Res)
//    printf("mulres is %x\n", mulResSh)
//  }

  io.out.bits := Mux(func(2).asBool(), Mux(func(3).asBool(), mulResSh, mulRes), Mux(func(0).asBool, Aconj, (realAdderRes << 32) | imageAdderRes))
  io.in.ready := Mux(func(2).asBool(), mul1.io.in.ready && mul2.io.in.ready, io.out.ready)
  io.out.valid := Mux(func(2).asBool(), mul1.io.out.valid && mul2.io.out.valid, valid)
}