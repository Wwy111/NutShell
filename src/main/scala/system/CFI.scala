
package system

import nutcore._
import bus.axi4.{AXI4, AXI4Lite}
import top.CFG
import chisel3._
import chisel3.experimental.chiselName
import chisel3.util._

object CFICmdType {                // cmd from iot devices or server
  def num = 6

  def srcInit = "b000".U
  def dstInit = "b001".U
  //  def matInit = "b010".U
  def patch = "b011".U
  def halt = "b100".U         // illegal jalr
  def read = "b101".U

  //  def loadready = "b111".U

  def apply() = UInt(log2Up(num).W)
}

class CFIIO extends NutCoreBundle {
  val soc = new Bundle {
    val req = Input(Bool())       // soc jalr validation request
    val srcAddr = Input(UInt(VAddrBits.W))  // src 32bit  dest 32bit
    val dstAddr = Input(UInt(VAddrBits.W))
    //    val valid = Output(Bool())    // valid signal of jalr
//    val loadready = Output(Bool()) // CFI ready
  }
  val iot = new Bundle {
    //    val req = Output(Bool())      // request for other iot devices or server
    //    val srcAddr = Output(UInt(32.W))
    //    val dstAddr = Output(UInt(32.W))
    val cmd = Input(CFICmdType.apply())
//    val loadready = Input(Bool())
    val addr = Input(UInt(8.W))   // index the mem
    val data = Input(UInt(VAddrBits.W))
  }
}
@chiselName
class CFI extends NutCoreModule {
  val io = IO(new CFIIO)

  val srcNum = 16
  val dstNum = 16
  val srcMem = Mem(srcNum, UInt(VAddrBits.W))
  val dstMem = Seq.fill(srcNum)(Mem(dstNum, UInt(VAddrBits.W)))

//  when(io.iot.cmd === CFICmdType.srcInit) {
//    srcMem.write(io.iot.addr(7, 4), io.iot.data)
//  }.elsewhen(io.iot.cmd === CFICmdType.dstInit || io.iot.cmd === CFICmdType.patch) {
//    //    printf("dstInit\n")
//    for(i <- 0 to (srcNum - 1)) {
//      when(i.U === io.iot.addr(7, 4).asUInt()) {
//        dstMem(i).write(io.iot.addr(3, 0), io.iot.data)
//      }
//    }
//  }

  //  io.soc.loadready := io.iot.loadready
//  io.soc.loadready <> DontCare
  when(RegNext(reset.asBool()) && (!reset.asBool())) {
    for(i <- 0 to (srcNum - 1)) {
      srcMem.write(i.U, CFG.getSrc(i).asUInt())
//      printf("src : %x, dest : ", srcMem.read(i.U))
      for(j <- 0 to (dstNum - 1)) {
        dstMem(i).write(j.U, CFG.getDest(i, j).asUInt())
//        printf("%x ", dstMem(i).read(j.U))
      }
//      printf("\n")
    }
  }

  when(io.iot.cmd === CFICmdType.patch) {
    for(i <- 0 to (srcNum - 1)) {
      when(i.U === io.iot.addr(7, 4).asUInt()) {
        dstMem(i).write(io.iot.addr(3, 0), io.iot.data)
      }
    }
  }


  val srcHit = Wire(Vec(srcNum, Bool()))
  val dstHit = Wire(Vec(srcNum, Vec(dstNum, Bool())))
  for (i <- 0 to (srcNum - 1)) {
    srcHit(i) :=  io.soc.srcAddr === srcMem.read(i.U)
    for(j <- 0 to (dstNum - 1)) {
      dstHit(i)(j) := srcHit(i) && io.soc.dstAddr === dstMem(i).read(j.U)
    }
  }

  val valid = (!io.soc.req) || (srcHit.asUInt().orR() && dstHit.asUInt().orR()) || io.iot.cmd === CFICmdType.patch
//  when(io.soc.req) {
//    printf("jalr src is %x, dst is %x\n", io.soc.srcAddr, io.soc.dstAddr)
//  }
  when(!valid) {
    printf("jalr wrong\n")
    printf("target is %x, cfg is %x\n", io.soc.dstAddr, dstMem(0).read(0.U))
  }
  //  io.soc.valid := (!io.soc.req) || (srcHit.asUInt().orR() && dstHit.asUInt().orR()) || io.iot.cmd === CFICmdType.patch
  //  io.iot.req := io.soc.req && (!dstHit.asUInt().orR())
  //  io.iot.srcAddr := io.soc.srcAddr
  //  io.iot.dstAddr := io.soc.dstAddr
  //  io.soc.valid <> DontCare
  //  io.iot.req <> DontCare
  //  io.iot.srcAddr <> DontCare
  //  io.iot.dstAddr <> DontCare
}
