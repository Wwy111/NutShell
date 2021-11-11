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

object MDUOpType {
  def mul    = "b0000".U
  def mulh   = "b0001".U
  def mulhsu = "b0010".U
  def mulhu  = "b0011".U
  def div    = "b0100".U
  def divu   = "b0101".U
  def rem    = "b0110".U
  def remu   = "b0111".U

  def mulw   = "b1000".U
  def divw   = "b1100".U
  def divuw  = "b1101".U
  def remw   = "b1110".U
  def remuw  = "b1111".U

  def isDiv(op: UInt) = op(2)
  def isDivSign(op: UInt) = isDiv(op) && !op(0)
  def isW(op: UInt) = op(3)
}

class MulDivIO(val len: Int) extends Bundle {
  val in = Flipped(DecoupledIO(Vec(2, Output(UInt(len.W)))))
  val sign = Input(Bool())
  val out = DecoupledIO(Output(UInt((len * 2).W)))
}

class Multiplier(len: Int) extends NutCoreModule {
  val io = IO(new MulDivIO(len))
  val latency = 1

  def DSPInPipe[T <: Data](a: T) = RegNext(a)
  def DSPOutPipe[T <: Data](a: T) = RegNext(RegNext(RegNext(a)))
  val mulRes = (DSPInPipe(io.in.bits(0)).asSInt * DSPInPipe(io.in.bits(1)).asSInt)
  io.out.bits := DSPOutPipe(mulRes).asUInt
  io.out.valid := DSPOutPipe(DSPInPipe(io.in.fire()))

  val busy = RegInit(false.B)
  when (io.in.valid && !busy) { busy := true.B }
  when (io.out.valid) { busy := false.B }
  io.in.ready := (if (latency == 0) true.B else !busy)
}

class Divider(len: Int = 64) extends NutCoreModule {
  val io = IO(new MulDivIO(len))

  def abs(a: UInt, sign: Bool): (Bool, UInt) = {
    val s = a(len - 1) && sign
    (s, Mux(s, -a, a))
  }

  val s_idle :: s_log2 :: s_shift :: s_compute :: s_finish :: Nil = Enum(5)
  val state = RegInit(s_idle)
  val newReq = (state === s_idle) && io.in.fire()

  val (a, b) = (io.in.bits(0), io.in.bits(1))
  val divBy0 = b === 0.U(len.W)

  val shiftReg = Reg(UInt((1 + len * 2).W))
  val hi = shiftReg(len * 2, len)
  val lo = shiftReg(len - 1, 0)

  val (aSign, aVal) = abs(a, io.sign)
  val (bSign, bVal) = abs(b, io.sign)
  val aSignReg = RegEnable(aSign, newReq)
  val qSignReg = RegEnable((aSign ^ bSign) && !divBy0, newReq)
  val bReg = RegEnable(bVal, newReq)
  val aValx2Reg = RegEnable(Cat(aVal, "b0".U), newReq)

  val cnt = Counter(len)
  when (newReq) {
    state := s_log2
  } .elsewhen (state === s_log2) {
    // `canSkipShift` is calculated as following:
    //   bEffectiveBit = Log2(bVal, XLEN) + 1.U
    //   aLeadingZero = 64.U - aEffectiveBit = 64.U - (Log2(aVal, XLEN) + 1.U)
    //   canSkipShift = aLeadingZero + bEffectiveBit
    //     = 64.U - (Log2(aVal, XLEN) + 1.U) + Log2(bVal, XLEN) + 1.U
    //     = 64.U + Log2(bVal, XLEN) - Log2(aVal, XLEN)
    //     = (64.U | Log2(bVal, XLEN)) - Log2(aVal, XLEN)  // since Log2(bVal, XLEN) < 64.U
    val canSkipShift = (len.U | Log2(bReg)) - Log2(aValx2Reg)
    // When divide by 0, the quotient should be all 1's.
    // Therefore we can not shift in 0s here.
    // We do not skip any shift to avoid this.
    cnt.value := Mux(divBy0, 0.U, Mux(canSkipShift >= (len-1).U, (len-1).U, canSkipShift))
    state := s_shift
  } .elsewhen (state === s_shift) {
    shiftReg := aValx2Reg << cnt.value
    state := s_compute
  } .elsewhen (state === s_compute) {
    val enough = hi.asUInt >= bReg.asUInt
    shiftReg := Cat(Mux(enough, hi - bReg, hi)(len - 1, 0), lo, enough)
    cnt.inc()
    when (cnt.value === (len-1).U) { state := s_finish }
  } .elsewhen (state === s_finish) {
    state := s_idle
  }

  val r = hi(len, 1)
  val resQ = Mux(qSignReg, -lo, lo)
  val resR = Mux(aSignReg, -r, r)
  io.out.bits := Cat(resR, resQ)

  io.out.valid := (if (HasDiv) (state === s_finish) else io.in.valid) // FIXME: should deal with ready = 0
  io.in.ready := (state === s_idle)
}

class MDUIO extends FunctionUnitIO {
  val com = Input(Bool())
}

class MDU extends NutCoreModule {
  val io = IO(new MDUIO)

  val (valid, src1, src2, func, com) = (io.in.valid, io.in.bits.src1, io.in.bits.src2, io.in.bits.func, io.com)
  def access(valid: Bool, src1: UInt, src2: UInt, func: UInt, com: Bool): UInt = {
    this.valid := valid
    this.src1 := src1
    this.src2 := src2
    this.func := func
    this.com  := com
    io.out.bits
  }

  val isDiv = MDUOpType.isDiv(func)
  val isDivSign = MDUOpType.isDivSign(func)
  val isW = MDUOpType.isW(func)

  val mul1 = Module(new Multiplier(XLEN + 1))
  val mul2 = Module(new Multiplier(XLEN + 1))
  val div = Module(new Divider(XLEN))
  List(mul1.io, mul2.io, div.io).map { case x =>
    x.sign := isDivSign
    x.out.ready := io.out.ready
  }

  when(!com) {
    val signext = SignExt(_: UInt, XLEN + 1)
    val zeroext = ZeroExt(_: UInt, XLEN + 1)
    val mulInputFuncTable = List(
      MDUOpType.mul -> (zeroext, zeroext),
      MDUOpType.mulh -> (signext, signext),
      MDUOpType.mulhsu -> (signext, zeroext),
      MDUOpType.mulhu -> (zeroext, zeroext)
    )
    mul1.io.in.bits(0) := LookupTree(func(1, 0), mulInputFuncTable.map(p => (p._1(1, 0), p._2._1(src1))))
    mul1.io.in.bits(1) := LookupTree(func(1, 0), mulInputFuncTable.map(p => (p._1(1, 0), p._2._2(src2))))

    mul2.io.in.bits(0) := 0.U
    mul2.io.in.bits(1) := 0.U

    val divInputFunc = (x: UInt) => Mux(isW, Mux(isDivSign, SignExt(x(31, 0), XLEN), ZeroExt(x(31, 0), XLEN)), x)
    div.io.in.bits(0) := divInputFunc(src1)
    div.io.in.bits(1) := divInputFunc(src2)

    mul1.io.in.valid := io.in.valid && !isDiv
    mul2.io.in.valid := false.B
    div.io.in.valid := io.in.valid && isDiv

    val mulRes = Mux(func(1, 0) === MDUOpType.mul(1, 0), mul1.io.out.bits(XLEN - 1, 0), mul1.io.out.bits(2 * XLEN - 1, XLEN))
    val divRes = Mux(func(1) /* rem */ , div.io.out.bits(2 * XLEN - 1, XLEN), div.io.out.bits(XLEN - 1, 0))
    val res = Mux(isDiv, divRes, mulRes)
    io.out.bits := Mux(isW, SignExt(res(31, 0), XLEN), res)

    val isDivReg = Mux(io.in.fire(), isDiv, RegNext(isDiv))
    io.in.ready := Mux(isDiv, div.io.in.ready, mul1.io.in.ready)
    io.out.valid := Mux(isDivReg, div.io.out.valid, mul1.io.out.valid)
  }.otherwise {
    val realA = src1(63, 32)
    val imageA = src1(31, 0)
    val realB = src2(63, 32)
    val imageB = src2(31, 0)

    val isComSub = COMUOpType.isDiff(func)
    val realAdderRes = (realA + (realB ^ Fill(32, isComSub))) + isComSub
    val imageAdderRes = (imageA + (imageB ^ Fill(32, isComSub))) + isComSub

    val imageInvert = ~imageA + 1.U(32.W)
    val Aconj = (realA << 32) | imageInvert
//    val mul1 = Module(new Multiplier(XLEN+1))
//    val mul2 = Module(new Multiplier(XLEN+1))

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

    div.io.in.bits(0) := 0.U
    div.io.in.bits(1) := 0.U
    div.io.in.valid := false.B
    mul1.io.in.valid := valid && func(2).asBool()
    mul2.io.in.valid := valid && func(2).asBool()
    mul1.io.out.ready := io.out.ready
    mul2.io.out.ready := io.out.ready
    mul1.io.sign <> DontCare
    mul2.io.sign <> DontCare

    val mul1Res = mul1.io.out.bits(XLEN-1, 0)
    val mul2Res = mul2.io.out.bits(XLEN-1, 0)

    val commulRes = SignExt((mul1Res + (mul2Res ^ Fill(XLEN, isComSub))) + isComSub, XLEN)
    val mulResSh = SignExt(((mul1Res.asSInt() >> 16).asUInt() + ((mul2Res.asSInt() >> 16).asUInt() ^ Fill(XLEN, isComSub))) + isComSub, XLEN)

    io.out.bits := Mux(func(2).asBool(), Mux(func(3).asBool(), mulResSh, commulRes), Mux(func(0).asBool, Aconj, (realAdderRes << 32) | imageAdderRes))
    io.in.ready := Mux(func(2).asBool(), mul1.io.in.ready && mul2.io.in.ready, io.out.ready)
    io.out.valid := Mux(func(2).asBool(), mul1.io.out.valid && mul2.io.out.valid, valid)
  }
  Debug("[FU-MDU] irv-orv %d %d - %d %d\n", io.in.ready, io.in.valid, io.out.ready, io.out.valid)

  BoringUtils.addSource(mul1.io.out.fire(), "perfCntCondMmulInstr")
}
