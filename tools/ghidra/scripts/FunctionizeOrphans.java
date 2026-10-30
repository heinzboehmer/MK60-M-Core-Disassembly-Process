// Give orphan code an owner. After RetraceBodies, instructions that still belong to no
// function fall into: runs referenced from DATA (function-pointer / vector / jump-table
// entries — a code address stored in a table is a function entry), runs referenced from a
// function's instruction that body tracing could not absorb, runs that start with the
// M-CORE frame prologue (subi r0,N ; stm rX-r15,(r0)), and unreferenced speculative decodes
// (left alone — they may be dead code). This creates a function at the start of each run in
// the first three classes; createFunction then traces the body, absorbing any orphan chain
// hanging off it.
// A fourth class is the DISPLACED ENTRY: a callback whose address is taken (lrw'd and passed
// as a parameter — DATA/PARAM references from instructions, never read or written) gets its
// first 1-4 bytes auto-typed as uint8/16/32 by ClassifyData, so the orphan run starts a few
// bytes after the real entry. When the data item right before a substantial run (>=16 instrs,
// ending in a terminator) is such an address-taken auto-sized scalar and the code decodes
// cleanly from the item's address, the item is cleared and the function created there.
// Inline tables are address-taken too, so the size/terminator/clean-decode guards matter.
// Args: LO HI (hex, no 0x). Idempotent.
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.data.*;
import ghidra.app.util.PseudoDisassembler;
import ghidra.app.util.PseudoInstruction;
import java.util.*;

public class FunctionizeOrphans extends GhidraScript {
    private long LO = 0x42000L, HI = 0xD7000L;

    @Override
    public void run() throws Exception {
        String[] _a = getScriptArgs(); if (_a.length >= 2) { LO = Long.parseLong(_a[0],16); HI = Long.parseLong(_a[1],16); }
        // A run of switch-case stubs (movi; br — 4 bytes each, every one a jump-table target) is
        // one orphan run whose first stub becomes a function and whose next stub is then the new
        // run start; rescanning until nothing new is created functionizes the whole table at once.
        int[] tot = new int[8];
        for (int iter = 0; iter < 64; iter++) {
            int[] r = pass();
            for (int i = 0; i < 7; i++) tot[i] += r[i];
            tot[7] = r[7];
            if (r[5] == 0) break;
        }
        println("FunctionizeOrphans: orphanRuns=" + tot[0] + " dataReferenced=" + tot[1] + " fnReferenced=" + tot[2]
                + " prologueStarted=" + tot[3] + " displacedEntry=" + tot[4] + " newFunctions=" + tot[5] + " failed=" + tot[6] + " leftUnreferenced=" + tot[7]);
    }

    // one scan; returns {orphanRuns, dataRefd, fnRefd, prologue, displaced, made, failed, left}
    private int[] pass() throws Exception {
        Listing ls = currentProgram.getListing();
        ReferenceManager rm = currentProgram.getReferenceManager();
        AddressSet set = new AddressSet(toAddr(LO), toAddr(HI - 1));

        // Collect orphan run starts first (creating functions while iterating would shift ownership).
        ArrayList<Address> starts = new ArrayList<>();
        HashMap<Address, int[]> runInfo = new HashMap<>();   // start -> {instrCount, endsInTerminator}
        Instruction prev = null; boolean prevOrphan = false; Address cur = null;
        for (Instruction i : ls.getInstructions(set, true)) {
            boolean orphan = getFunctionContaining(i.getAddress()) == null;
            if (orphan) {
                boolean contiguous = prevOrphan && prev != null && i.getAddress().equals(prev.getAddress().add(prev.getLength()));
                if (!contiguous) { cur = i.getAddress(); starts.add(cur); runInfo.put(cur, new int[]{0, 0}); }
                int[] ri = runInfo.get(cur); ri[0]++; ri[1] = isTerminator(i) ? 1 : 0;
            }
            prev = i; prevOrphan = orphan;
        }
        pd = new PseudoDisassembler(currentProgram);

        int dataRefd = 0, fnRefd = 0, prologue = 0, displaced = 0, made = 0, failed = 0, left = 0;
        for (Address a : starts) {
            if (getFunctionContaining(a) != null) continue;   // absorbed by an earlier creation
            // Displaced entry: address-taken auto-sized scalar right before a substantial run.
            Address entry = displacedEntry(a, runInfo.get(a));
            if (entry != null) {
                try { clearListing(entry, a.subtract(1)); disassemble(entry); } catch (Exception e) {}
                if (getInstructionAt(entry) != null) {
                    displaced++;
                    try { if (createFunction(entry, null) != null) made++; else failed++; } catch (Exception e) { failed++; }
                    continue;
                }
            }
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
        return new int[]{starts.size(), dataRefd, fnRefd, prologue, displaced, made, failed, left};
    }

    private PseudoDisassembler pd;

    private boolean isTerminator(Instruction i) {
        String m = i.getMnemonicString();
        return m.equals("br") || m.equals("jmpi") || m.equals("rts") || m.equals("rfi") || m.equals("rte")
                || (m.equalsIgnoreCase("jmp") && i.getNumOperands() > 0 && "r15".equals(i.getDefaultOperandRepresentation(0)));
    }

    // The data item immediately before run start `a`, if it is an address-taken auto-sized
    // scalar (<=4 bytes) and the run is substantial; returns the item's address or null.
    private Address displacedEntry(Address a, int[] ri) {
        if (ri == null || ri[0] < 16 || ri[1] == 0) return null;
        Data d = currentProgram.getListing().getDataContaining(a.subtract(1));
        if (d == null || !d.isDefined() || d.getLength() > 4) return null;
        DataType dt = d.getDataType();
        if (dt instanceof Pointer || dt instanceof Array || dt instanceof Structure) return null;
        boolean taken = false;
        for (Reference r : getReferencesTo(d.getAddress())) {
            RefType rt = r.getReferenceType();
            if (rt.isRead() || rt.isWrite()) return null;            // a real variable / table
            if (getInstructionAt(r.getFromAddress()) != null && (rt.isData() || rt == RefType.PARAM)) taken = true;
        }
        if (!taken) return null;
        Address e = d.getAddress();
        if (e.getOffset() < LO || (e.getOffset() & 1) != 0) return null;
        // must decode cleanly from the item straight into the run
        Address p = e;
        try {
            for (int k = 0; k < 8 && p.compareTo(a) < 0; k++) {
                PseudoInstruction pi = pd.disassemble(p);
                if (pi == null || pi.getLength() <= 0) return null;
                p = p.add(pi.getLength());
            }
        } catch (Exception ex) { return null; }
        return p.equals(a) ? e : null;
    }
}
