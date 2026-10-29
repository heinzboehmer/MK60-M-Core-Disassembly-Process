// Disassemble undefined runs that classify as CODE (decode cleanly to a terminator; NOT a
// pointer table, NOT padding) -- the unnamed code reached by unresolved jumps / sitting after
// a terminator. Clears any typed data, disassembles from the even start, and functionizes a
// run whose predecessor is a terminator (a real function boundary). RecoverFlowCode cascades
// branches afterward; RetraceBodies fixes bodies. Table-guarded; verify with ScanMisdisasm.
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.app.util.PseudoDisassembler;
import ghidra.app.util.PseudoInstruction;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.Undefined;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.Memory;
import java.util.*;

public class RecoverCodeRuns extends GhidraScript {
    private long LO = 0x42000L, HI = 0xD7000L;
    private PseudoDisassembler pd;
    private Memory mem;

    private boolean addr(long v) {
        return (v >= 0x40000L && v < 0x100000L) || (v >= 0x400000L && v < 0x424000L) || (v >= 0xD00000L && v < 0x1000000L);
    }

    @Override
    public void run() throws Exception {
        String[] _bnd = getScriptArgs(); if (_bnd.length>=2) { LO=Long.parseLong(_bnd[0],16); HI=Long.parseLong(_bnd[1],16); }
        pd = new PseudoDisassembler(currentProgram);
        mem = currentProgram.getMemory();
        ArrayList<long[]> runs = new ArrayList<>();
        long c = LO;
        while (c < HI) {
            if (getMemoryBlock(toAddr(c)) == null) { c += 2; continue; }
            if (getInstructionAt(toAddr(c)) != null) { c = getInstructionAt(toAddr(c)).getMaxAddress().add(1).getOffset(); continue; }
            Data d = getDataAt(toAddr(c));
            boolean undef = d == null || !d.isDefined() || d.getDataType() instanceof Undefined;
            if (!undef) { c += d.getLength(); continue; }
            long start = c;
            while (c < HI) {
                if (getInstructionAt(toAddr(c)) != null) break;
                Data dd = getDataAt(toAddr(c));
                boolean u = dd == null || !dd.isDefined() || dd.getDataType() instanceof Undefined;
                if (!u) break;
                c += (dd != null && dd.isDefined()) ? dd.getLength() : 1;
            }
            long len = c - start;
            if (len >= 4 && isCode(start, len)) runs.add(new long[]{start, len});
        }

        int disasm = 0, fns = 0;
        for (long[] r : runs) {
            long start = r[0];
            long es = (start + 1) & ~1L;
            Address a = toAddr(es);
            // clear typed data across the run
            long p = es;
            while (p < start + r[1]) {
                Data dd = getDataAt(toAddr(p));
                if (dd != null && dd.isDefined()) clearListing(toAddr(p), dd.getMaxAddress());
                p += (dd != null && dd.isDefined()) ? dd.getLength() : 2;
            }
            if (getInstructionAt(a) == null) { try { disassemble(a); } catch (Exception e) {} }
            if (getInstructionAt(a) == null) continue;
            disasm++;
            Instruction prev = getInstructionBefore(a);
            boolean boundary = prev == null || prev.getFlowType().isTerminal()
                    || prev.getMnemonicString().equalsIgnoreCase("jmp") || prev.getMnemonicString().equals("jmpi")
                    || prev.getMnemonicString().equals("br") || prev.getMnemonicString().equals("rts");
            if (boundary && getFunctionAt(a) == null) { try { if (createFunction(a, null) != null) fns++; } catch (Exception e) {} }
        }
        println("RecoverCodeRuns: codeRuns=" + runs.size() + " disassembled=" + disasm + " newFunctions=" + fns);
    }

    private boolean isCode(long start, long len) {
        boolean allZ = true, allF = true;
        try { for (long p = start; p < start + Math.min(len, 64); p++) { int b = mem.getByte(toAddr(p)) & 0xFF; if (b != 0) allZ = false; if (b != 0xFF) allF = false; } } catch (Exception e) {}
        if (allZ || allF) return false;
        long a0 = (start + 3) & ~3L; int words = 0, ptrs = 0;
        for (long p = a0; p + 4 <= start + len; p += 4) { words++; try { if (addr(mem.getInt(toAddr(p)) & 0xFFFFFFFFL)) ptrs++; } catch (Exception e) {} }
        if (words >= 2 && ptrs * 2 >= words) return false;             // pointer table
        long es = (start + 1) & ~1L; Address p = toAddr(es); int ni = 0;
        for (int i = 0; i < 24; i++) {
            if (p.getOffset() >= start + len) break;
            PseudoInstruction pi; try { pi = pd.disassemble(p); } catch (Exception e) { pi = null; }
            if (pi == null) return ni >= 6;
            ni++;
            String m = pi.getMnemonicString();
            if (m.equals("br") || m.equals("jmpi") || m.equals("rts") || m.equalsIgnoreCase("jmp")) return ni >= 4;
            int l = pi.getLength(); if (l <= 0) break; p = p.add(l);
        }
        return ni >= 6;
    }
}
