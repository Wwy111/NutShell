
package system

import nutcore._
import bus.axi4.{AXI4, AXI4Lite}
import top.CFG
import chisel3._
import chisel3.experimental.chiselName
import chisel3.util._
import chisel3.util.experimental.BoringUtils

object CFICmdType {                // cmd from iot devices or server

//  def idle = "b00".U

  // resp cmd
  def patch = "b000".U
  def lookupFail = "b001".U
  def halt = "b010".U

  // req cmd
  def lookupIoT = "b011".U    // req to other or other req to this
  def lookupCloud = "b100".U

}

class CFIIoTIO extends NutCoreBundle {
  val in = new Bundle {
    val valid = Input(Bool())
    val id = Input(UInt(2.W))
    val cmd = Input(UInt(3.W))
    val srcAddr = Input(UInt(VAddrBits.W))
    val dstAddr = Input(UInt(VAddrBits.W))
    }

  val out = Flipped(in)
}

class CFIIO extends NutCoreBundle {
  val soc = new Bundle {
    val valid = Input(Bool())               // soc jalr validation request
    val srcAddr = Input(UInt(VAddrBits.W))  // src 39bit  dest 39bit
    val dstAddr = Input(UInt(VAddrBits.W))
  }

  val iot = new CFIIoTIO
}
@chiselName
class CFI extends NutCoreModule {
  val io = IO(new CFIIO)

  val (valid, id, cmd, srcAddr, dstAddr) = (io.iot.in.valid, io.iot.in.id, io.iot.in.cmd, io.iot.in.srcAddr, io.iot.in.dstAddr)
  val selfID = CFG.getCFDID().U

  val srcNum = 16
  val dstNum = 16
  val srcMem = Mem(srcNum, UInt(VAddrBits.W))
  val dstMem = Seq.fill(srcNum)(Mem(dstNum, UInt(VAddrBits.W)))
  val dstSp = Seq.fill(srcNum)(Counter(dstNum))

  // in cmd
  def patch = valid && (cmd === CFICmdType.patch) && (id === selfID)
  def lookupFail = valid && (cmd === CFICmdType.lookupFail) && (id === selfID)
  def halt = valid && (cmd === CFICmdType.halt) && (id === selfID)
  def lookupIoT = valid && (cmd === CFICmdType.lookupIoT) && (id =/= selfID)

  // default output
  io.iot.out.valid := false.B
  io.iot.out.id := selfID
  io.iot.out.cmd := CFICmdType.lookupIoT
  io.iot.out.srcAddr := io.soc.srcAddr
  io.iot.out.dstAddr := io.soc.dstAddr

  // init the table
  when(RegNext(reset.asBool()) && (!reset.asBool())) {
    for(i <- 0 to (srcNum - 1)) {
      srcMem.write(i.U, CFG.getSrc(i).asUInt())
      for(j <- 0 to (dstNum - 1)) {
        dstMem(i).write(j.U, CFG.getDest(i, j).asUInt())
      }
      dstSp(i).value := (dstNum - 1).U
    }
  }

//  def keyMap[T <: Mem](i: UInt, mem: T) = {
//    mem.read(i)  -> i
//  }

  // soc cfi lookup
  val socSrcIndex = MuxLookup(io.soc.srcAddr, 0x10.U, (0 to srcNum-1).map(i => (srcMem.read(i.U) -> i.U)).toSeq)
  val socDstIndex = WireInit(0.U(5.W))
  for(j <- 0 to (srcNum - 1)) {
    when(j.U === socSrcIndex) {
      socDstIndex := MuxLookup(io.soc.dstAddr, 0x10.U, (0 to dstNum-1).map(i => (dstMem(j).read(i.U) -> i.U)).toSeq)
    }
  }

  val socHit = (!io.soc.valid) || (socSrcIndex =/= 0x10.U && socDstIndex =/= 0x10.U)
  val cfivalid = (socHit || patch) && (!halt)
  BoringUtils.addSource(cfivalid, "cfiValid")

  when(!socHit) {
    io.iot.out.valid := true.B
    io.iot.out.id := selfID
    io.iot.out.cmd := CFICmdType.lookupIoT
    io.iot.out.srcAddr := io.soc.srcAddr
    io.iot.out.dstAddr := io.soc.dstAddr
    printf("\nID : %d jalr wrong\n", selfID)
    printf("src is %x, dest is %x\n", io.soc.srcAddr, io.soc.dstAddr)
    when(halt) {
      printf("\nhalt!!!\n")
    }
  }



  when(patch) {                      // patch the table
    for(i <- 0 to (srcNum - 1)) {
      when(srcMem.read(i.U) === srcAddr) {
        dstMem(i).write(dstSp(i).value, dstAddr)
        dstSp(i).value := dstSp(i).value - 1.U
      }
    }
  }

  when(lookupFail) {
    io.iot.out.valid := true.B
    io.iot.out.id := selfID
    io.iot.out.cmd := CFICmdType.lookupCloud
    io.iot.out.srcAddr := io.soc.srcAddr
    io.iot.out.dstAddr := io.soc.dstAddr
  }


  // iot cfi lookup
  val iotSrcIndex = MuxLookup(srcAddr, 0x10.U, (0 to srcNum-1).map(i => (srcMem.read(i.U) -> i.U)).toSeq)
  val iotDstIndex = WireInit(0.U(5.W))
  for(j <- 0 to (srcNum - 1)) {
    when(j.U === iotSrcIndex) {
      iotDstIndex := MuxLookup(dstAddr, 0x10.U, (0 to dstNum-1).map(i => (dstMem(j).read(i.U) -> i.U)).toSeq)
    }
  }
  val iotHit = iotSrcIndex =/= 0x10.U && iotDstIndex =/= 0x10.U
  when(lookupIoT) {
    io.iot.out.valid := true.B
    io.iot.out.id := id
    io.iot.out.cmd := Mux(iotHit, CFICmdType.patch, CFICmdType.lookupFail)
    io.iot.out.srcAddr := srcMem.read(iotSrcIndex)
    for(j <- 0 to (srcNum - 1)) {
      when(j.U === iotSrcIndex) {
        io.iot.out.dstAddr := dstMem(j).read(iotDstIndex)
      }
    }
  }
}
