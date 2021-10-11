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
  def sqrsum = "b000".U
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

  val mul1 = Module(new Multiplier(XLEN))
  val mul2 = Module(new Multiplier(XLEN))

  mul1.io.in.bits(0) := src1
  mul1.io.in.bits(1) := src1

  mul2.io.in.bits(0) := src2
  mul2.io.in.bits(1) := src2

  mul1.io.in.valid := io.in.valid
  mul2.io.in.valid := io.in.valid
  mul1.io.out.ready := io.out.ready
  mul2.io.out.ready := io.out.ready
  mul1.io.sign <> DontCare
  mul2.io.sign <> DontCare

  val mul1Res = mul1.io.out.bits(XLEN-1, 0)
  val mul2Res = mul2.io.out.bits(XLEN-1, 0)

  io.out.bits := mul1Res + mul2Res
  io.in.ready := mul1.io.in.ready && mul2.io.in.ready
  io.out.valid := mul1.io.out.valid && mul2.io.out.valid
}