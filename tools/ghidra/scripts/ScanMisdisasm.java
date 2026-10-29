// Read-only: find pointer tables that were wrongly disassembled as code. Scan 4-aligned
// addresses in the code range; a run of >=4 consecutive 4-byte words that are all code-
// range pointers AND currently sit inside instructions is almost certainly a jump/pointer
// table decoded as code (coincidence for a 4-run ~1e-16). Report each run.
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.Memory;

public class ScanMisdisasm extends GhidraScript {
    private static final long LO = 0x42000L, HI = 0xD7000L;

    @Override
    public void run() throws Exception {
        Memory mem = currentProgram.getMemory();
        int runs = 0; long totalBytes = 0;
        long runStart = -1; int runLen = 0;
        for (long a = LO; a + 4 <= HI; a += 4) {
            boolean isPtrWord = false, isCode = false;
            try {
                long v = mem.getInt(toAddr(a)) & 0xFFFFFFFFL;
                isPtrWord = (v >= LO && v < HI);
                isCode = getInstructionContaining(toAddr(a)) != null;
            } catch (Exception e) {}
            if (isPtrWord && isCode) {
                if (runStart < 0) { runStart = a; runLen = 0; }
                runLen++;
            } else {
                if (runStart >= 0 && runLen >= 4) { report(runStart, runLen); runs++; totalBytes += runLen * 4L; }
                runStart = -1; runLen = 0;
            }
        }
        if (runStart >= 0 && runLen >= 4) { report(runStart, runLen); runs++; totalBytes += runLen * 4L; }
        println("ScanMisdisasm: suspected mis-disassembled pointer-table runs=" + runs + " totalBytes=" + totalBytes);
    }

    private void report(long start, int len) {
        Address fn0 = null;
        var f = getFunctionContaining(toAddr(start));
        println(String.format("  %06x  entries=%-3d  (%06x..%06x)  inFn=%s",
                start, len, start, start + len * 4L - 1,
                f == null ? "-" : f.getName() + "@" + f.getEntryPoint()));
    }
}
