// Give orphan code an owner. After RetraceBodies, instructions that still belong to no
// function fall into: runs referenced from DATA (function-pointer / vector / jump-table
// entries — a code address stored in a table is a function entry), runs referenced from a
// function's instruction that body tracing could not absorb, runs that start with the
// M-CORE frame prologue (subi r0,N ; stm rX-r15,(r0)), and unreferenced speculative decodes
// (left alone — they may be dead code). This creates a function at the start of each run in
// the first three classes; createFunction then traces the body, absorbing any orphan chain
// hanging off it. Args: LO HI (hex, no 0x). Idempotent.
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import java.util.*;

public class FunctionizeOrphans extends GhidraScript {
    private long LO = 0x42000L, HI = 0xD7000L;

    @Override
    public void run() throws Exception {
        String[] _a = getScriptArgs(); if (_a.length >= 2) { LO = Long.parseLong(_a[0],16); HI = Long.parseLong(_a[1],16); }
        Listing ls = currentProgram.getListing();
        ReferenceManager rm = currentProgram.getReferenceManager();
        AddressSet set = new AddressSet(toAddr(LO), toAddr(HI - 1));

        // Collect orphan run starts first (creating functions while iterating would shift ownership).
        ArrayList<Address> starts = new ArrayList<>();
        Instruction prev = null; boolean prevOrphan = false;
        for (Instruction i : ls.getInstructions(set, true)) {
            boolean orphan = getFunctionContaining(i.getAddress()) == null;
            if (orphan) {
                boolean contiguous = prevOrphan && prev != null && i.getAddress().equals(prev.getAddress().add(prev.getLength()));
                if (!contiguous) starts.add(i.getAddress());
            }
            prev = i; prevOrphan = orphan;
        }

        int dataRefd = 0, fnRefd = 0, prologue = 0, made = 0, failed = 0, left = 0;
        for (Address a : starts) {
            if (getFunctionContaining(a) != null) continue;   // absorbed by an earlier creation
            boolean fromData = false, fromFn = false;
            ReferenceIterator ri = rm.getReferencesTo(a);
            while (ri.hasNext()) {
                Address from = ri.next().getFromAddress();
                if (getInstructionAt(from) == null) fromData = true;
                else if (getFunctionContaining(from) != null) fromFn = true;
            }
            boolean pro = false;
            try {
                int h = currentProgram.getMemory().getShort(a) & 0xFFFF, h2 = currentProgram.getMemory().getShort(a.add(2)) & 0xFFFF;
                pro = (h & 0xFE0F) == 0x2400 && (h2 & 0xFFF0) == 0x0070;
            } catch (Exception e) {}
            if (!(fromData || fromFn || pro)) { left++; continue; }
            if (fromData) dataRefd++; else if (fromFn) fnRefd++; else prologue++;
            try { if (createFunction(a, null) != null) made++; else failed++; } catch (Exception e) { failed++; }
        }
        println("FunctionizeOrphans: orphanRuns=" + starts.size() + " dataReferenced=" + dataRefd + " fnReferenced=" + fnRefd
                + " prologueStarted=" + prologue + " newFunctions=" + made + " failed=" + failed + " leftUnreferenced=" + left);
    }
}
