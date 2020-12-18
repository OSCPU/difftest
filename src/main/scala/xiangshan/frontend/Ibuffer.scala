package xiangshan.frontend

import chisel3._
import chisel3.util._

import xiangshan._
import utils._
import xiangshan.backend.fu.HasExceptionNO


class IbufPtr extends CircularQueuePtr(IbufPtr.IBufSize) { }

object IbufPtr extends HasXSParameter {
  def apply(f: Bool, v: UInt): IbufPtr = {
    val ptr = Wire(new IbufPtr)
    ptr.flag := f
    ptr.value := v
    ptr
  }
}

class IBufferIO extends XSBundle {
  val flush = Input(Bool())
  val in = Flipped(DecoupledIO(new FetchPacket))
  val out = Vec(DecodeWidth, DecoupledIO(new CtrlFlow))
}

class Ibuffer extends XSModule with HasCircularQueuePtrHelper {
  val io = IO(new IBufferIO)

  class IBufEntry extends XSBundle {
    val inst = UInt(32.W)
    val pc = UInt(VAddrBits.W)
    val pnpc = UInt(VAddrBits.W)
    val brInfo = new BranchInfo
    val pd = new PreDecodeInfo
    val ipf = Bool()
    val acf = Bool()
    val crossPageIPFFix = Bool()
  }

  // Ignore
  // io.loopBufPar <> DontCare
  // io.loopBufPar.LBredirect.valid := false.B
  // io.loopBufPar.inLoop := false.B


  for(out <- io.out) {
    // out.bits.exceptionVec := DontCare
    out.bits.intrVec := DontCare
    // out.bits.crossPageIPFFix := DontCare
  }

  // Ibuffer define
  val ibuf = Mem(IBufSize, new IBufEntry)
  val ibuf_valid = RegInit(VecInit(Seq.fill(IBufSize)(false.B)))
  // val head_ptr = RegInit(0.U(log2Up(IBufSize).W))
  // val tail_ptr = RegInit(0.U(log2Up(IBufSize).W))
  val head_ptr = RegInit(IbufPtr(false.B, 0.U))
  val tail_ptr = RegInit(IbufPtr(false.B, 0.U))

  val validEntries = distanceBetween(tail_ptr, head_ptr) // valid entries
  val emptyEntries = IBufSize.U - validEntries

  // enqValid := !ibuf_valid(tail_ptr.value + PredictWidth.U - 1.U)
  val enqValid = emptyEntries >= PredictWidth.U
  // deqValid := ibuf_valid(head_ptr.value)
  val deqValid = validEntries > 0.U

  // Enque
  io.in.ready := enqValid

  val enq_vec = Wire(Vec(PredictWidth, UInt(log2Up(IBufSize).W)))
  for(i <- 0 unitl PredictWidth) {
    enq_vec(i) := tail_ptr + i.U
  }
  for(i <- 0 until PredictWidth) {
    if (i == 0) {
      enq_vec(i) := tail_ptr.value
    } else {
      enq_vec(i) := tail_ptr.value + PopCount(io.in.bits.pdmask(i-1, 0))
    }
  }

  when(io.in.fire && !io.flush) {
    for(i <- 0 until PredictWidth) {
      val inWire = Wire(new IBufEntry)
      inWire := DontCare

      when(io.in.bits.mask(i)) {
        ibuf_valid(enq_vec(i)) := true.B
        inWire.inst := io.in.bits.instrs(i)
        inWire.pc := io.in.bits.pc(i)
        inWire.pnpc := io.in.bits.pnpc(i)
        inWire.brInfo := io.in.bits.brInfo(i)
        inWire.pd := io.in.bits.pd(i)
        inWire.ipf := io.in.bits.ipf
        inWire.acf := io.in.bits.acf
        inWire.crossPageIPFFix := io.in.bits.crossPageIPFFix
        ibuf(enq_vec(PopCount(i, 0))) := inWire
      }
    }

    tail_ptr := tail_ptr + PopCount(io.in.bits.mask)
  }

  // Deque
  when(deqValid) {
    for(i <- 0 until DecodeWidth) {
      val head_wire = head_ptr.value + i.U
      val outWire = WireInit(ibuf(head_wire))

      io.out(i).valid := ibuf_valid(head_wire)
      when(ibuf_valid(head_wire) && io.out(i).ready) {
        ibuf_valid(head_wire) := false.B
      }

      io.out(i).bits.instr := outWire.inst
      io.out(i).bits.pc := outWire.pc
      // io.out(i).bits.exceptionVec := Mux(outWire.ipf, UIntToOH(instrPageFault.U), 0.U)
      io.out(i).bits.exceptionVec := 0.U.asTypeOf(Vec(16, Bool()))
      io.out(i).bits.exceptionVec(instrPageFault) := outWire.ipf
      io.out(i).bits.exceptionVec(instrAccessFault) := outWire.acf
      // io.out(i).bits.brUpdate := outWire.brInfo
      io.out(i).bits.brUpdate := DontCare
      io.out(i).bits.brUpdate.pc := outWire.pc
      io.out(i).bits.brUpdate.pnpc := outWire.pnpc
      io.out(i).bits.brUpdate.pd := outWire.pd
      io.out(i).bits.brUpdate.brInfo := outWire.brInfo
      io.out(i).bits.crossPageIPFFix := outWire.crossPageIPFFix
    }
    // head_ptr := head_ptr + io.out.map(_.fire).fold(0.U(log2Up(DecodeWidth).W))(_+_)
    head_ptr := head_ptr + PopCount(io.out.map(_.fire))
  }.otherwise {
    io.out.foreach(_.valid := false.B)
    io.out.foreach(_.bits <> DontCare)
  }

  // Flush
  when(io.flush) {
    ibuf_valid.foreach(_ := false.B)
    head_ptr.value := 0.U
    head_ptr.flag := false.B
    tail_ptr.value := 0.U
    tail_ptr.flag := false.B
  }

  // Debug info
  XSDebug(io.flush, "IBuffer Flushed\n")

  when(io.in.fire) {
    XSDebug("Enque:\n")
    XSDebug(p"MASK=${Binary(io.in.bits.mask)}\n")
    for(i <- 0 until PredictWidth){
        XSDebug(p"PC=${Hexadecimal(io.in.bits.pc(i))} ${Hexadecimal(io.in.bits.instrs(i))}\n")
    }
  }

  when(deqValid) {
    XSDebug("Deque:\n")
    for(i <- 0 until DecodeWidth){
        XSDebug(p"${Hexadecimal(io.out(i).bits.instr)} PC=${Hexadecimal(io.out(i).bits.pc)} v=${io.out(i).valid} r=${io.out(i).ready} " +
          p"excpVec=${Binary(io.out(i).bits.exceptionVec.asUInt)} crossPageIPF=${io.out(i).bits.crossPageIPFFix}\n")
    }
  }

  XSDebug(p"last_head_ptr=$head_ptr  last_tail_ptr=$tail_ptr\n")
  for(i <- 0 until IBufSize/8) {
    XSDebug("%x v:%b | %x v:%b | %x v:%b | %x v:%b | %x v:%b | %x v:%b | %x v:%b | %x v:%b\n",
      ibuf(i*8+0).inst, ibuf_valid(i*8+0),
        ibuf(i*8+1).inst, ibuf_valid(i*8+1),
        ibuf(i*8+2).inst, ibuf_valid(i*8+2),
        ibuf(i*8+3).inst, ibuf_valid(i*8+3),
        ibuf(i*8+4).inst, ibuf_valid(i*8+4),
        ibuf(i*8+5).inst, ibuf_valid(i*8+5),
        ibuf(i*8+6).inst, ibuf_valid(i*8+6),
        ibuf(i*8+7).inst, ibuf_valid(i*8+7)
    )
  }
}
