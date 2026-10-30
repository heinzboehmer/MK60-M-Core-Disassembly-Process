// Recover functions that sit INSIDE an undefined run. RecoverCodeRuns classifies an undefined
// run as a whole, from its first byte: a run that starts with the previous function's literal
// pool (pointer words, then code) is rejected, and every function after the pool stays `??`.
// On a fresh image that is ~13 KB of the code region, 50-70 functions, and none of it raises
// an error bookmark or an orphan-code count. The other flavour is an address-taken callback
// whose entry byte ClassifyData typed as a uint8_t: the run starts one byte after the
// prologue and FunctionizeOrphans' displaced-entry rule never sees it (no instructions yet).
//
// This pass seeds on the M-CORE frame prologue instead — `subi r0,N ; stm rX-r15,(r0)`, the
// same pair find_boundary.py relies on (two constrained halfwords; p ~ 1e-6 per position in
// non-code) — wherever it occurs in [LO,HI) in bytes that are undefined OR typed as a small
// scalar nothing reads or writes, and the code pseudo-decodes cleanly from there. Each seed is
// disassembled and functionized (RecoverFlowCode then follows its branches; RetraceBodies
// grows the body).
//
// Two things such a body runs into, both handled here because they are what hid these
// functions in the first place:
//   - a mis-typed scalar: a constant that looks like a code address (0x80000, a bit-19 mask)
//     gets PARAM references from the lrw that loads it, and ClassifyData/ApplyDataTypes turn
//     the word AT that address into a uint8/16/32_t even though it is mid-function. A body
//     whose straight-line flow falls into such an item (a non-terminator falls through to it,
//     nothing reads or writes it, and the bytes decode cleanly) has the item cleared and the
//     disassembly continued;
//   - a spurious continuation function at the fall-through: an unnamed FUN_ whose entry is
//     reached by straight fall-through from the new body and that nothing calls, jumps to or
//     points at from data is not a function (compilers do not fall into the next function);
//     it is removed and the new function re-created so its body absorbs the instructions.
// Args: LO HI (hex, no 0x). Idempotent: a prologue that is already an instruction is skipped.
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.app.util.PseudoDisassembler;
import ghidra.app.util.PseudoInstruction;
import ghidra.program.model.address.*;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.symbol.*;
import java.util.*;

public class RecoverPrologues extends GhidraScript {
    private long LO = 0x42000L, HI = 0xD7000L;
    private PseudoDisassembler pd;
    private Memory mem;
    private Listing ls;

    @Override
    public void run() throws Exception {
        String[] a = getScriptArgs(); if (a.length >= 2) { LO = Long.parseLong(a[0], 16); HI = Long.parseLong(a[1], 16); }
        pd = new PseudoDisassembler(currentProgram);
        mem = currentProgram.getMemory();
        ls = currentProgram.getListing();

        // 1. seeds: prologue pairs in undefined (or mis-typed-scalar) bytes
        ArrayList<Long> seeds = new ArrayList<>();
        for (long p = LO & ~1L; p + 4 <= HI; p += 2) {
            if (getInstructionContaining(toAddr(p)) != null) continue;
            int h, h2;
            try { h = mem.getShort(toAddr(p)) & 0xFFFF; h2 = mem.getShort(toAddr(p + 2)) & 0xFFFF; } catch (Exception e) { continue; }
            if ((h & 0xFE0F) != 0x2400 || (h2 & 0xFFF0) != 0x0070) continue;
            if (!recoverable(p) || !recoverable(p + 2)) continue;
            if (cleanDecode(toAddr(p), 6)) seeds.add(p);
        }

        int made = 0, disasm = 0, continued = 0, clearedScalars = 0, entryCleared = 0, absorbed = 0, failed = 0;
        for (long s : seeds) {
            Address e = toAddr(s);
            if (getInstructionAt(e) != null) continue;          // reached by an earlier seed's flow
            // a scalar item covering the entry (typed callback entry) is cleared first
            for (long q = s; q < s + 4; ) {
                Data d = ls.getDataContaining(toAddr(q));
                if (d != null && d.isDefined()) { try { clearListing(d.getAddress(), d.getMaxAddress()); entryCleared++; } catch (Exception ex) {} q = d.getMaxAddress().getOffset() + 1; }
                else q++;
            }
            try { disassemble(e); } catch (Exception ex) {}
            if (getInstructionAt(e) == null) { failed++; continue; }
            disasm++;
            // 2. follow straight-line flow through mis-typed scalars
            int c = continueThroughScalars(e);
            clearedScalars += c; if (c > 0) continued++;
            // 3. functionize
            if (getFunctionAt(e) == null) {
                Function f = null;
                try { f = createFunction(e, null); } catch (Exception ex) {}
                if (f == null) { failed++; continue; }
                made++;
                absorbed += absorbContinuations(f);
            }
        }
        println("RecoverPrologues: prologueSeeds=" + seeds.size() + " disassembled=" + disasm + " newFunctions=" + made
                + " entryScalarsCleared=" + entryCleared + " bodiesContinuedThroughScalar=" + continued
                + " scalarsCleared=" + clearedScalars + " continuationsAbsorbed=" + absorbed + " failed=" + failed);
    }

    // Undefined, or a small scalar nothing reads/writes (an address-shaped constant's shadow).
    private boolean recoverable(long p) {
        Address a = toAddr(p);
        if (getInstructionContaining(a) != null) return false;
        Data d = ls.getDataContaining(a);
        if (d == null || !d.isDefined() || d.getDataType() instanceof Undefined) return true;
        return misTypedScalar(d);
    }

    // >= n valid instructions, or an unconditional terminator after >= 4, before anything invalid.
    private boolean cleanDecode(Address a, int n) {
        try {
            Address p = a; int ni = 0;
            for (int i = 0; i < 24; i++) {
                PseudoInstruction ins = pd.disassemble(p);
                if (ins == null) return false;
                ni++;
                String m = ins.getMnemonicString();
                if (m.equals("br") || m.equals("jmpi") || m.equals("rts") || m.equalsIgnoreCase("jmp")) return ni >= 4;
                if (ni >= n) return true;
                int l = ins.getLength(); if (l <= 0) return false;
                p = p.add(l);
            }
            return ni >= n;
        } catch (Exception e) { return false; }
    }

    // A small scalar item (<= 4 bytes, not pointer/array/struct/string) that nothing reads,
    // writes or jumps to: an address-shaped constant's shadow, not a pool slot or variable.
    private boolean misTypedScalar(Data d) {
        if (d == null || !d.isDefined() || d.getLength() > 4) return false;
        DataType dt = d.getDataType();
        if (dt instanceof Pointer || dt instanceof Array || dt instanceof Structure || dt instanceof Union
                || dt instanceof AbstractStringDataType) return false;
        for (Reference r : getReferencesTo(d.getAddress())) {
            RefType rt = r.getReferenceType();
            if (rt.isRead() || rt.isWrite() || rt.isFlow()) return false;
        }
        return true;
    }

    // Walk fall-through from `e`; when it lands on a mis-typed scalar whose bytes decode
    // cleanly, clear it and keep disassembling. Returns the number of scalars cleared.
    private int continueThroughScalars(Address e) {
        int cleared = 0;
        Address pc = e;
        for (int i = 0; i < 4000; i++) {
            Instruction ins = getInstructionAt(pc);
            if (ins == null) break;
            Address ft = ins.getFallThrough();
            if (ft == null) break;                               // terminator
            if (getInstructionAt(ft) != null) { pc = ft; continue; }
            if (ft.getOffset() >= HI) break;
            Data d = getDataAt(ft);
            if (d == null || !d.isDefined()) {
                try { disassemble(ft); } catch (Exception ex) {}
                if (getInstructionAt(ft) == null) break;
                pc = ft; continue;
            }
            if (!misTypedScalar(d) || !cleanDecode(ft, 4)) break;
            try { clearListing(ft, d.getMaxAddress()); disassemble(ft); } catch (Exception ex) { break; }
            if (getInstructionAt(ft) == null) break;
            cleared++;
            pc = ft;
        }
        return cleared;
    }

    // While the body's last instruction falls through to the entry of an unnamed FUN_ that
    // nothing calls, jumps to or references from data, remove it and re-create the function.
    private int absorbContinuations(Function f) {
        int n = 0;
        for (int i = 0; i < 16; i++) {
            Instruction last = getInstructionContaining(f.getBody().getMaxAddress());
            if (last == null) break;
            Address ft = last.getFallThrough();
            if (ft == null || f.getBody().contains(ft)) break;
            Function g = getFunctionAt(ft);
            if (g == null || !g.getName().startsWith("FUN_")) break;
            boolean real = false;
            for (Reference r : getReferencesTo(ft)) {
                RefType rt = r.getReferenceType();
                if (getInstructionAt(r.getFromAddress()) == null || rt.isFlow()) { real = true; break; }
            }
            if (real) break;
            Address e = f.getEntryPoint();
            try {
                removeFunction(g);
                removeFunction(f);
                Function nf = createFunction(e, null);
                if (nf == null) break;
                f = nf; n++;
            } catch (Exception ex) { break; }
        }
        return n;
    }
}
