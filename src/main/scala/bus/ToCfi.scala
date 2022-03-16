package bus

import chisel3._
import chisel3.util._

class UartTxPathIO extends Bundle {
  val txData = DecoupledIO(Output(UInt(8.W)))
}

class UartRxPathIO extends Bundle {
  val rxData = DecoupledIO(Input(UInt(8.W)))
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

}

class Uart2CfiConverter extends Module {
  val io = IO(new Bundle {
    val in = new UartRxPathIO
    val out = new ToCfi
  })
}
