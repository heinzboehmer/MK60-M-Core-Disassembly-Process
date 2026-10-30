// Read-only: M-CORE frame prologues (subi r0,N ; stm rX-r15,(r0)) sitting in bytes of [LO,HI)
// that are not instructions — undefined, or a small scalar nothing reads or writes. Each one
// is a function the recovery never disassembled (want 0 after RecoverPrologues); raw `??`
// raises no error bookmark and no orphan count, so this is the only check that sees them.
// Lists the runs they sit in when there are few. Args: LO HI (hex, no 0x).
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.symbol.*;
import java.util.*;

public class UndefPrologues extends GhidraScript {
    @Override
    public void run() throws Exception {
        String[] a = getScriptArgs();
        long LO = a.length > 0 ? Long.parseLong(a[0], 16) : 0x42000L, HI = a.length > 1 ? Long.parseLong(a[1], 16) : 0xD7000L;
        Memory mem = currentProgram.getMemory();
        Listing ls = currentProgram.getListing();
        ArrayList<Long> hits = new ArrayList<>();
        for (long p = LO & ~1L; p + 4 <= HI; p += 2) {
            if (ls.getInstructionContaining(toAddr(p)) != null || ls.getInstructionContaining(toAddr(p + 2)) != null) continue;
            int h, h2;
            try { h = mem.getShort(toAddr(p)) & 0xFFFF; h2 = mem.getShort(toAddr(p + 2)) & 0xFFFF; } catch (Exception e) { continue; }
            if ((h & 0xFE0F) != 0x2400 || (h2 & 0xFFF0) != 0x0070) continue;
            if (!nonCode(ls, p) || !nonCode(ls, p + 2)) continue;
            hits.add(p);
        }
        // group into runs of undefined bytes
        ArrayList<String> runs = new ArrayList<>(); long runLo = -1, runHi = -1; int inRun = 0;
        for (long p : hits) {
            if (runLo >= 0 && p <= runHi) { inRun++; continue; }
            if (runLo >= 0) runs.add(String.format("%x..%x(%d)", runLo, runHi, inRun));
            long lo = p, hi = p + 4;
            while (lo > LO && ls.getInstructionContaining(toAddr(lo - 1)) == null) lo--;
            while (hi < HI && ls.getInstructionContaining(toAddr(hi)) == null) hi++;
            runLo = lo; runHi = hi; inRun = 1;
        }
        if (runLo >= 0) runs.add(String.format("%x..%x(%d)", runLo, runHi, inRun));
        println("UndefPrologues: prologuesNotCode=" + hits.size() + " runs=" + runs.size());
        if (!runs.isEmpty() && runs.size() <= 40) println("UndefPrologues: runs: " + String.join(" ", runs));
    }

    private boolean nonCode(Listing ls, long p) {
        Data d = ls.getDataContaining(toAddr(p));
        if (d == null || !d.isDefined() || d.getDataType() instanceof Undefined) return true;
        if (d.getLength() > 4) return false;
        DataType dt = d.getDataType();
        if (dt instanceof Pointer || dt instanceof Array || dt instanceof Structure || dt instanceof Union) return false;
        for (Reference r : getReferencesTo(d.getAddress())) {
            RefType rt = r.getReferenceType();
            if (rt.isRead() || rt.isWrite() || rt.isFlow()) return false;
        }
        return true;
    }
}
