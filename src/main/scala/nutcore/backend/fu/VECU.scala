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

object VECUOpType {
  def vecadd     = "b000".U
  def vecsub     = "b001".U
  def scamul_8   = "b010".U
  def scamul_16  = "b100".U
  def dotprod_8  = "b011".U
  def dotprod_16 = "b101".U

  def isSub(op: UInt) = op === vecsub
  def is16(op: UInt) = op(2).asBool()
}

class VECUIO extends FunctionUnitIO {

}

class VECU extends NutCoreModule {
  val io = IO(new VECUIO)

  val (valid, src1, src2, func) = (io.in.valid, io.in.bits.src1, io.in.bits.src2, io.in.bits.func)
  def access(valid: Bool, src1: UInt, src2: UInt, func: UInt): UInt = {
    this.valid := valid
    this.src1 := src1
    this.src2 := src2
    this.func := func
    io.out.bits
  }

  val vecA8 = VecInit(src1(63,56), src1(55,48), src1(47,40), src1(39,32), src1(31,24), src1(23,16), src1(15,8), src1(7, 0))
  val vecB8 = VecInit(src2(63,56), src2(55,48), src2(47,40), src2(39,32), src2(31,24), src2(23,16), src2(15,8), src2(7, 0))
  val vecA16= VecInit(src1(63,48), src1(47,32), src1(31,16), src1(15,0))
  val vecB16= VecInit(src2(63,48), src2(47,32), src2(31,16), src2(15,0))

//  val vecA = Mux(VECUOpType.is16(func), VecInit(src1(63,48), src1(47,32), src1(31,16), src1(15,0)), VecInit(src1(63,56), src1(55,48), src1(47,40), src1(39,32), src1(31,24), src1(23,16), src1(15,8), src1(7, 0)))
//  val vecB = Mux(VECUOpType.is16(func), VecInit(src2(63,48), src2(47,32), src2(31,16), src2(15,0)), VecInit(src2(63,56), src2(55,48), src2(47,40), src2(39,32), src2(31,24), src2(23,16), src2(15,8), src2(7, 0)))

  val isVecSub = VECUOpType.isSub(func)
  def Adder(src1: UInt, src2: UInt, isSub: Bool): UInt = {
    src1 + (src2 ^ Fill(8, isSub)) + isSub
  }
  val vecAdderRes = VecInit((0 to 7).map(i => Adder(vecA8(i), vecB8(i), isVecSub)))
  val mul = VecInit(Seq.fill(8)(Module(new Multiplier(9)).io))

  when(VECUOpType.is16(func)) {
    for(i <- 0 to 3) {
      mul(i).in.bits(0) := vecA16(i)
      mul(i).in.bits(1) := Mux(func(0).asBool(), vecB16(i), src2)
      mul(i).in.valid := valid && func(2).asBool()
      mul(i).out.ready := io.out.ready
//      mul(i).sign <> DontCare
    }
    for(i <- 4 to 7) {
      mul(i).in.bits(0) := 0.U
      mul(i).in.bits(1) := 0.U
      mul(i).in.valid := false.B
      mul(i).out.ready := io.out.ready
//      mul(i).sign <> DontCare
    }
  }.otherwise {
    for(i <- 0 to 7) {
      mul(i).in.bits(0) := vecA8(i)
      mul(i).in.bits(1) := Mux(func(0).asBool(), vecB8(i), src2)
      mul(i).in.valid := valid && func(1).asBool()
      mul(i).out.ready := io.out.ready
//      mul(i).sign <> DontCare
    }
  }
  (0 to 7).map(i => mul(i).sign := true.B)

  val vec16MulRes = mul(0).out.bits(15, 0) << 48 | mul(1).out.bits(15, 0) << 32 | mul(2).out.bits(15, 0) << 16 | mul(3).out.bits(15, 0)
  val vec8MulRes = mul(0).out.bits(7, 0) << 56 | mul(1).out.bits(7, 0) << 48 | mul(2).out.bits(7, 0) << 40 | mul(3).out.bits(7, 0) << 32 |
                   mul(4).out.bits(7, 0) << 24 | mul(5).out.bits(7, 0) << 16 | mul(6).out.bits(7, 0) << 8 | mul(7).out.bits(7, 0)
  val vec8AddRes = vecAdderRes(0)(7, 0) << 56 | vecAdderRes(1)(7, 0) << 48 | vecAdderRes(2)(7, 0) << 40 | vecAdderRes(3)(7, 0) << 32 |
                   vecAdderRes(4)(7, 0) << 24 | vecAdderRes(5)(7, 0) << 16 | vecAdderRes(6)(7, 0) << 8 | vecAdderRes(7)(7, 0)

  io.out.bits := MuxCase(0.U, Array(
    (func === VECUOpType.vecadd || func === VECUOpType.vecsub)      ->     vec8AddRes,
    (func === VECUOpType.scamul_8 || func === VECUOpType.dotprod_8) ->     vec8MulRes,
    (func === VECUOpType.scamul_16 || func === VECUOpType.dotprod_16) ->   vec16MulRes
  ))

  io.in.ready := MuxCase(io.out.ready, Array(
    (func === VECUOpType.vecadd || func === VECUOpType.vecsub)      ->     io.out.ready,
    (func === VECUOpType.scamul_8 || func === VECUOpType.dotprod_8) ->     (mul(0).in.ready && mul(1).in.ready && mul(2).in.ready && mul(3).in.ready && mul(4).in.ready && mul(5).in.ready && mul(6).in.ready && mul(7).in.ready),
    (func === VECUOpType.scamul_16 || func === VECUOpType.dotprod_16) ->   (mul(0).in.ready && mul(1).in.ready && mul(2).in.ready && mul(3).in.ready)
  ))
  io.out.valid := MuxCase(valid, Array(
    (func === VECUOpType.vecadd || func === VECUOpType.vecsub)      ->     valid,
    (func === VECUOpType.scamul_8 || func === VECUOpType.dotprod_8) ->     (mul(0).out.valid && mul(1).out.valid && mul(2).out.valid && mul(3).out.valid && mul(4).out.valid && mul(5).out.valid && mul(6).out.valid && mul(7).out.valid),
    (func === VECUOpType.scamul_16 || func === VECUOpType.dotprod_16) ->   (mul(0).out.valid && mul(1).out.valid && mul(2).out.valid && mul(3).out.valid)
  ))
}