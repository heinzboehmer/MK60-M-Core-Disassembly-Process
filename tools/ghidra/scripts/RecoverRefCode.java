// Recover functions reached through references that were never followed: an undefined
// code-range address that (a) is the target of a FLOW reference (a materialized call/jump
// -> provably code), or (b) is address-taken by a plain DATA reference (a function pointer)
// AND begins with a function prologue (subi r0,N; stm rX-r15,(r0)) or is a clean leaf that
// reaches a return. Excludes anything read/written as data (a variable) and anything inside
// a pointer-table region. Disassembles + functionizes; RecoverFlowCode then cascades.
// Also seeds the VBR exception-vector table (VBR=0x40000 set by crt0; 128 x 4-byte entries).
// Bit 0 of an entry is the M-CORE alternate-register-file flag, NOT part of the address, so
// entries are odd: mask it, record a DATA reference slot -> handler, clear whatever auto-sized
// data ClassifyData put on the handler's first bytes (the odd address made it a uint8_t that
// displaced the decode by two), and functionize. ISR entries have no prologue and are not
// leaves, so the generic data-pointer rule below misses them.
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.app.util.PseudoDisassembler;
import ghidra.app.util.PseudoInstruction;
import ghidra.program.model.address.*;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.symbol.*;
import ghidra.program.model.listing.*;
import java.util.*;

public class RecoverRefCode extends GhidraScript {
    private long LO = 0x42000L, HI = 0xD7000L;  // override via args: LO HI (hex)
    private PseudoDisassembler pd;
    private final ArrayList<long[]> tableRanges = new ArrayList<>();

    @Override
    public void run() throws Exception {
        String[] _bnd = getScriptArgs(); if (_bnd.length>=2) { LO=Long.parseLong(_bnd[0],16); HI=Long.parseLong(_bnd[1],16); }
        Memory mem = currentProgram.getMemory();
        pd = new PseudoDisassembler(currentProgram);
        findTableRanges(mem);
        ReferenceManager rm = currentProgram.getReferenceManager();
        AddressIterator dests = rm.getReferenceDestinationIterator(new AddressSet(toAddr(LO), toAddr(HI - 1)), true);

        ArrayList<Long> seeds = new ArrayList<>();
        int flowSeeds = 0, ptrSeeds = 0, vecSeeds = 0, fns0 = 0;

        // VBR vector table: 0x40000 + 4*i, i < 128; entry = handler | AF-flag(bit 0)
        for (int i = 0; i < 128; i++) {
            Address slot = toAddr(0x40000L + 4L * i);
            long v; try { v = mem.getInt(slot) & 0xFFFFFFFFL; } catch (Exception e) { continue; }
            if (v == 0 || v == 0xFFFFFFFFL) continue;
            long tv = v & ~1L;
            if (tv < 0x40000L || tv >= HI) continue;
            Address t = toAddr(tv);
            try { rm.addMemoryReference(slot, t, RefType.DATA, SourceType.ANALYSIS, 0); } catch (Exception e) {}
            if (getFunctionAt(t) != null) continue;
            if (getInstructionAt(t) == null && getInstructionContaining(t) == null) {
                try { clearListing(t, t.add(3)); } catch (Exception e) {}   // auto-sized uint8/16 on the entry
                if (!cleanCode(t, 4)) continue;
                seeds.add(tv); vecSeeds++;
            } else if (getInstructionAt(t) != null && getFunctionContaining(t) == null) {
                try { if (createFunction(t, null) != null) { vecSeeds++; fns0++; } } catch (Exception e) {}
            }
        }

        while (dests.hasNext()) {
            Address a = dests.next();
            long o = a.getOffset();
            if (getInstructionAt(a) != null) continue;
            if (inTable(o)) continue;
            if (getDataAt(a) != null && getDataAt(a).isDefined()) {
                var dt = getDataAt(a).getDataType();
                boolean scalar = !(dt instanceof ghidra.program.model.data.Pointer
                        || dt instanceof ghidra.program.model.data.Array
                        || dt instanceof ghidra.program.model.data.Structure
                        || dt instanceof ghidra.program.model.data.Union);
                if (!scalar || dt.getLength() > 4) continue;
            }
            boolean hasData = false, hasRW = false, hasFlow = false;
            for (Reference r : rm.getReferencesTo(a)) {
                RefType rt = r.getReferenceType();
                if (rt.isFlow()) hasFlow = true;
                else if (rt.isRead() || rt.isWrite()) hasRW = true;
                else hasData = true;
            }
            if (hasRW) continue;                         // accessed as data -> variable
            if (hasFlow && cleanCode(a, 6)) { seeds.add(o); flowSeeds++; }
            else if (hasData && (isPrologue(a) || leafReturns(a))) { seeds.add(o); ptrSeeds++; }
        }

        int made = 0, fns = fns0;
        for (long o : seeds) {
            Address a = toAddr(o);
            if (getInstructionAt(a) != null) continue;
            if (getDataAt(a) != null && getDataAt(a).isDefined()) clearListing(a, toAddr(o + getDataAt(a).getLength() - 1));
            try { disassemble(a); } catch (Exception e) {}
            if (getInstructionAt(a) != null) { made++; try { if (createFunction(a, null) != null) fns++; } catch (Exception e) {} }
        }
        println("RecoverRefCode: flowSeeds=" + flowSeeds + " ptrSeeds=" + ptrSeeds + " vectorSeeds=" + vecSeeds
                + " disassembled=" + made + " newFunctions=" + fns);
    }

    // clean decode for >=n instrs (0 invalid), or reaches a terminator cleanly first
    private boolean cleanCode(Address a, int n) {
        Address p = a;
        for (int i = 0; i < Math.max(n, 16); i++) {
            PseudoInstruction ins;
            try { ins = pd.disassemble(p); } catch (Exception e) { ins = null; }
            if (ins == null) return i >= n;
            String m = ins.getMnemonicString();
            if (m.equals("br") || m.equals("jmpi") || m.equals("rts")
                    || (m.equalsIgnoreCase("jmp") && ins.getDefaultOperandRepresentation(0).equals("r15")))
                return i + 1 >= Math.min(n, 2);
            int l = ins.getLength(); if (l <= 0) return false;
            p = p.add(l);
        }
        return true;
    }

    private boolean isPrologue(Address a) {
        try {
            PseudoInstruction i0 = pd.disassemble(a);
            if (i0 == null || !i0.getMnemonicString().equals("subi")
                    || !i0.getDefaultOperandRepresentation(0).equals("r0")) return false;
            PseudoInstruction i1 = pd.disassemble(a.add(i0.getLength()));
            return i1 != null && i1.getMnemonicString().equals("stm");
        } catch (Exception e) { return false; }
    }

    private boolean leafReturns(Address a) {
        Address p = a;
        for (int i = 0; i < 40; i++) {
            PseudoInstruction ins;
            try { ins = pd.disassemble(p); } catch (Exception e) { ins = null; }
            if (ins == null) return false;
            String m = ins.getMnemonicString();
            if (m.equals("rts") || (m.equalsIgnoreCase("jmp") && ins.getDefaultOperandRepresentation(0).equals("r15"))) return true;
            if (m.equals("br") || m.equals("jmpi")) return false;   // non-leaf; let flow/cascade handle
            int l = ins.getLength(); if (l <= 0) return false;
            p = p.add(l);
        }
        return false;
    }

    private void findTableRanges(Memory mem) {
        long rs = -1; int rl = 0;
        for (long p = LO; p + 4 <= HI; p += 4) {
            long v; try { v = mem.getInt(toAddr(p)) & 0xFFFFFFFFL; } catch (Exception e) { v = 0; }
            boolean ptr = (v >= LO && v < HI);
            if (ptr) { if (rs < 0) { rs = p; rl = 0; } rl++; }
            else { if (rs >= 0 && rl >= 4) tableRanges.add(new long[]{rs, rs + rl * 4L}); rs = -1; rl = 0; }
        }
        if (rs >= 0 && rl >= 4) tableRanges.add(new long[]{rs, rs + rl * 4L});
    }
    private boolean inTable(long o) { for (long[] t : tableRanges) if (o >= t[0] && o < t[1]) return true; return false; }
}
