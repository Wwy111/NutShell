package bus.cfi

import chisel3._
import chisel3.util._
import utils._


class UartTxPathIO extends Bundle {
  val txData = DecoupledIO(Output(UInt(8.W)))
}

class UartRxPathIO extends Bundle {
  val rxData = Flipped(DecoupledIO(Output(UInt(8.W))))
}

class CfiTo extends Bundle {
  val valid = Input(Bool())
  val id = Input(UInt(2.W))
  val cmd = Input(UInt(3.W))
  val prepresrc = Input(UInt(32.W))
  val presrc = Input(UInt(32.W))
  val src = Input(UInt(32.W))
  val dst = Input(UInt(32.W))
}

class ToCfi extends Bundle {
  val valid = Output(Bool())
  val id = Output(UInt(2.W))
  val cmd = Output(UInt(3.W))
  val srcAddr = Output(UInt(32.W))
  val dstAddr = Output(UInt(32.W))
}

class Cfi2UartConverter extends Module {
  val io = IO(new Bundle {
    val in = new CfiTo
    val out = new UartTxPathIO
  })

  val idle :: head :: infor :: prepre :: pre :: src :: dst :: Nil = Enum(7)
  val state = RegInit(idle)
  val byte_cnt = RegInit(0.U(3.W))
  val framehead = WireInit(VecInit("haa".U(8.W), "hbb".U(8.W), "hcc".U(8.W), "hdd".U(8.W)))

  io.out.txData.valid := false.B
  io.out.txData.bits := 0.U

  switch(state) {
    is(idle) {
      io.out.txData.valid := false.B
      io.out.txData.bits := 0.U
      when(io.in.valid) {
        state := head
        byte_cnt := 0.U
      }
    }
    is(head) {
      when(io.out.txData.ready && byte_cnt < 4.U) {
        io.out.txData.valid := true.B
        io.out.txData.bits := framehead(byte_cnt)
        when(byte_cnt =/= 3.U) {
          byte_cnt := byte_cnt + 1.U
        }.otherwise {
          state := infor
          byte_cnt := 0.U
        }
      }.otherwise {
        io.out.txData.valid := false.B
      }
    }
    is(infor) {
      when(io.out.txData.ready) {
        io.out.txData.valid := true.B
        io.out.txData.bits := (io.in.id << 6) | io.in.cmd
        state := prepre
        byte_cnt := 0.U
      }.otherwise {
        io.out.txData.valid := false.B
      }
    }
    is(prepre) {
      when(io.out.txData.ready && byte_cnt < 4.U) {
        io.out.txData.valid := true.B
        io.out.txData.bits := WordRightShift(io.in.prepresrc, byte_cnt, 8) & "h0ff".U(32.W)
        when(byte_cnt =/= 3.U) {
          byte_cnt := byte_cnt + 1.U
        }.otherwise {
          state := pre
          byte_cnt := 0.U
        }
      }.otherwise {
        io.out.txData.valid := false.B
      }
    }
    is(pre) {
      when(io.out.txData.ready && byte_cnt < 4.U) {
        io.out.txData.valid := true.B
        io.out.txData.bits := WordRightShift(io.in.presrc, byte_cnt, 8) & "h0ff".U(32.W)
        when(byte_cnt =/= 3.U) {
          byte_cnt := byte_cnt + 1.U
        }.otherwise {
          state := src
          byte_cnt := 0.U
        }
      }.otherwise {
        io.out.txData.valid := false.B
      }
    }
    is(src) {
      when(io.out.txData.ready && byte_cnt < 4.U) {
        io.out.txData.valid := true.B
        io.out.txData.bits := WordRightShift(io.in.src, byte_cnt, 8) & "h0ff".U(32.W)
        when(byte_cnt =/= 3.U) {
          byte_cnt := byte_cnt + 1.U
        }.otherwise {
          state := dst
          byte_cnt := 0.U
        }
      }.otherwise {
        io.out.txData.valid := false.B
      }
    }
    is(dst) {
      when(io.out.txData.ready && byte_cnt < 4.U) {
        io.out.txData.valid := true.B
        io.out.txData.bits := WordRightShift(io.in.dst, byte_cnt, 8) & "h0ff".U(32.W)
        when(byte_cnt =/= 3.U) {
          byte_cnt := byte_cnt + 1.U
        }.otherwise {
          state := idle
          byte_cnt := 0.U
        }
      }.otherwise {
        io.out.txData.valid := false.B
      }
    }
  }
}

class Uart2CfiConverter extends Module {
  val io = IO(new Bundle {
    val in = new UartRxPathIO
    val out = new ToCfi
  })

  val (valid, data) = (io.in.rxData.valid, io.in.rxData.bits)

  val head :: infor :: src :: dst :: Nil = Enum(4)
  val state = RegInit(head)
  val byte_cnt = RegInit(0.U(3.W))
  val framehead = WireInit(VecInit("haa".U(8.W), "hbb".U(8.W), "hcc".U(8.W), "hdd".U(8.W)))
  val toCfiDataBuf = RegInit(0.U.asTypeOf(new ToCfi))


  io.in.rxData.ready := true.B

  switch(state) {
    is(head) {
      toCfiDataBuf := 0.U.asTypeOf(new ToCfi)
      when(valid) {
        when(data === framehead(byte_cnt)) {
          when(byte_cnt === 3.U) {
            state := infor
          }
          byte_cnt := byte_cnt + 1.U
        }.otherwise {
          byte_cnt := 0.U
        }
      }
    }
    is(infor) {
      when(valid) {
        byte_cnt := 0.U
        toCfiDataBuf.id := data(7, 6)
        toCfiDataBuf.cmd := data(3, 0)
        state := src
      }
    }
    is(src) {
      when(valid) {
        toCfiDataBuf.srcAddr := WordShift(data, byte_cnt, 8) | toCfiDataBuf.srcAddr
        when(byte_cnt =/= 3.U) {
          byte_cnt := byte_cnt + 1.U
        }.otherwise {
          state := dst
          byte_cnt := 0.U
        }
      }
    }
    is(dst) {
      when(valid) {
        toCfiDataBuf.dstAddr := WordShift(data, byte_cnt, 8) | toCfiDataBuf.dstAddr
        when(byte_cnt =/= 3.U) {
          byte_cnt := byte_cnt + 1.U
        }.otherwise {
          toCfiDataBuf.valid := true.B
          state := head
        }
      }
    }
  }

  io.out <> toCfiDataBuf
}
