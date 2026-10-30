// Resolve M-CORE jump/pointer tables correctly: the table itself is DATA (an array of
// absolute code pointers), and its ENTRIES' targets are the real handlers (switch arms).
// Ghidra's import (and an over-eager flow recovery) can follow a computed jump INTO a
// table and decode the pointer bytes as code (they form valid idly4/movi/jmpi), silently.
//
// Pass:
//  1. Find runs of >=MINRUN consecutive 4-byte words that are code-range pointers (a table).
//  2. If any of the run is currently disassembled as code, clear it; define every slot as
//     a 4-byte pointer (so the table reads as data and can't be mistaken for code again).
//  3. Collect every entry's target; disassemble + functionize each target that is undefined,
//     decodes cleanly (PseudoDisassembler reaches an unconditional terminator, 0 invalid),
//     and does NOT itself land inside a table region.
// Follow with FixLiteralPools/FixResidual. Args: LO HI [MINRUN] (hex, hex, decimal; MINRUN default 4).
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.app.util.PseudoDisassembler;
import ghidra.app.util.PseudoInstruction;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.PointerDataType;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.symbol.Reference;
import java.util.*;

public class FixJumpTables extends GhidraScript {
    private long LO = 0x42000L, HI = 0xD7000L;
    private PseudoDisassembler pd;

    @Override
    public void run() throws Exception {
        String[] _bnd = getScriptArgs(); if (_bnd.length>=2) { LO=Long.parseLong(_bnd[0],16); HI=Long.parseLong(_bnd[1],16); }
        Memory mem = currentProgram.getMemory();
        pd = new PseudoDisassembler(currentProgram);
        int minRun = 4;
        String[] a = getScriptArgs();
        if (a.length >= 3) minRun = Integer.parseInt(a[2]);   // args[0..1] are LO HI

        // 1. find table runs
        ArrayList<long[]> tables = new ArrayList<>();   // {start, entries}
        long rs = -1; int rl = 0;
        for (long p = LO; p + 4 <= HI; p += 4) {
            long v;
            try { v = mem.getInt(toAddr(p)) & 0xFFFFFFFFL; } catch (Exception e) { v = 0; }
            boolean ptr = (v >= LO && v < HI);
            if (ptr) { if (rs < 0) { rs = p; rl = 0; } rl++; }
            else { if (rs >= 0 && rl >= minRun) tables.add(new long[]{rs, rl}); rs = -1; rl = 0; }
        }
        if (rs >= 0 && rl >= minRun) tables.add(new long[]{rs, rl});

        // bases proven to be jump tables by a `jmp rN` indexed-switch dispatcher
        Set<Long> forcedBases = collectJumpTableBases(mem);

        // table address ranges (to exclude targets that land in a table)
        // 2. revert mis-coded tables to pointer data
        int tablesFixed = 0; long bytesCleared = 0;
        PointerDataType ptrT = new PointerDataType(currentProgram.getDataTypeManager());
        for (long[] t : tables) {
            long start = t[0]; int n = (int) t[1];
            Address s = toAddr(start), e = toAddr(start + n * 4L - 1);
            boolean wasCode = false;
            for (long p = start; p < start + n * 4L; p += 2)
                if (getInstructionContaining(toAddr(p)) != null) { wasCode = true; break; }
            if (wasCode) { tablesFixed++; bytesCleared += n * 4L; }
            clearListing(s, e);
            for (int i = 0; i < n; i++) {
                Address slot = toAddr(start + i * 4L);
                try { if (getDataAt(slot) == null || !getDataAt(slot).isDefined()) createData(slot, ptrT); } catch (Exception ex) {}
            }
        }

        // 3. disassemble entry targets -- but only for JUMP tables (majority of targets are
        //    already code), never data-pointer tables (targets are data). For a jump table a
        //    target mis-typed as a small scalar (e.g. uint8_t from ClassifyData) is cleared.
        int jumpTables = 0, targets = 0, disassembled = 0, fns = 0;
        LinkedHashSet<Long> seen = new LinkedHashSet<>();
        for (long[] t : tables) {
            // classify: fraction of (non-self) targets already code
            int code = 0, nonSelf = 0;
            for (int i = 0; i < t[1]; i++) {
                long v = (mem.getInt(toAddr(t[0] + i * 4L)) & 0xFFFFFFFFL) & ~1L;
                if (inTable(v, tables)) continue;
                nonSelf++;
                if (getInstructionAt(toAddr(v)) != null) code++;
            }
            boolean forced = false;
            for (long b : forcedBases) if (b >= t[0] && b < t[0] + t[1] * 4L) { forced = true; break; }
            if (!forced && (nonSelf < 2 || code * 2 < nonSelf)) continue;  // data-pointer table -> leave targets
            jumpTables++;
            for (int i = 0; i < t[1]; i++) {
                long v = (mem.getInt(toAddr(t[0] + i * 4L)) & 0xFFFFFFFFL) & ~1L;
                if (!seen.add(v)) continue;
                targets++;
                Address ta = toAddr(v);
                if (getInstructionAt(ta) != null) continue;
                if (inTable(v, tables)) continue;                 // target is itself a table slot
                Data d = getDataAt(ta);
                if (d != null && d.isDefined()) {
                    ghidra.program.model.data.DataType dt = d.getDataType();
                    boolean structured = dt instanceof ghidra.program.model.data.Pointer
                            || dt instanceof ghidra.program.model.data.Array
                            || dt instanceof ghidra.program.model.data.Structure
                            || dt instanceof ghidra.program.model.data.Union
                            || dt instanceof ghidra.program.model.data.AbstractStringDataType;
                    boolean scalar = !structured && dt.getLength() >= 1 && dt.getLength() <= 4;  // incl. uint8_t typedef
                    if (!scalar) continue;                        // pointer/array/struct -> leave
                    if (!cleanDecode(ta)) continue;
                    clearListing(ta, ta.add(dt.getLength() - 1));
                } else if (!cleanDecode(ta)) continue;
                try {
                    disassemble(ta);
                    if (getInstructionAt(ta) != null) { disassembled++; if (getFunctionAt(ta) == null && createFunction(ta, null) != null) fns++; }
                } catch (Exception ex) {}
            }
        }
        println("FixJumpTables: tables=" + tables.size() + " jumpTables=" + jumpTables
                + " misCodedReverted=" + tablesFixed + " bytesCleared=" + bytesCleared
                + " targets=" + targets + " newlyDisassembled=" + disassembled + " newFunctions=" + fns);
    }

    // Table bases proven by an M-CORE indexed-switch dispatcher:
    //   lrw rJ,<slot> ; ixw rJ,rIdx ; ld.w rJ,(rJ,0x0) ; jmp rJ
    // The base is the pointer VALUE at the lrw's pool slot (lrw loads the word). Such a
    // table is a jump table even when all its targets are still raw (the code-fraction
    // heuristic can't tell it from a data-pointer table otherwise).
    private Set<Long> collectJumpTableBases(Memory mem) {
        HashSet<Long> bases = new HashSet<>();
        InstructionIterator it = currentProgram.getListing().getInstructions(toAddr(LO), true);
        while (it.hasNext()) {
            Instruction jmp = it.next();
            if (jmp.getAddress().getOffset() >= HI) break;
            if (!jmp.getMnemonicString().equals("jmp")) continue;
            String reg = jmp.getDefaultOperandRepresentation(0);
            if (reg.equals("r15")) continue;                     // return
            Instruction p1 = getInstructionBefore(jmp.getAddress());
            if (p1 == null || !p1.getMnemonicString().equals("ld.w")) continue;
            Instruction p2 = getInstructionBefore(p1.getAddress());
            if (p2 == null || !p2.getMnemonicString().equals("ixw")) continue;
            Instruction p3 = getInstructionBefore(p2.getAddress());
            if (p3 == null || !p3.getMnemonicString().equals("lrw")) continue;
            // The lrw's pool-slot ref is often not materialized, so compute it from the
            // encoding: lrw rZ,disp8 -> slot = (addr + 2 + (disp8<<2)) & ~3. The table base
            // is the pointer VALUE stored there.
            try {
                byte[] bb = p3.getBytes();
                int w = ((bb[0] & 0xFF) << 8) | (bb[1] & 0xFF);
                int disp8 = w & 0xFF;
                long slot = (p3.getAddress().getOffset() + 2 + (disp8 << 2)) & ~3L;
                if (!mem.contains(toAddr(slot))) continue;
                long base = mem.getInt(toAddr(slot)) & 0xFFFFFFFFL;
                if (base >= LO && base < HI) bases.add(base);
            } catch (Exception e) {}
        }
        return bases;
    }

    private boolean inTable(long v, ArrayList<long[]> tables) {
        for (long[] t : tables) if (v >= t[0] && v < t[0] + t[1] * 4L) return true;
        return false;
    }

    private boolean cleanDecode(Address a) {
        try {
            Address p = a;
            for (int i = 0; i < 16; i++) {
                PseudoInstruction ins = pd.disassemble(p);
                if (ins == null) return false;
                String m = ins.getMnemonicString();
                if (m.equals("br") || m.equals("jmpi") || m.equals("jmp") || m.equals("rts")) return true;
                int l = ins.getLength(); if (l <= 0) return false;
                p = p.add(l);
            }
            return true;
        } catch (Exception e) { return false; }
    }
}
