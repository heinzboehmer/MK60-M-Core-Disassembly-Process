// WRITE: mark the flash image blocks (the loader-created, initialized blocks in 0x40000..0xFFFFF)
// non-writable. The Intel HEX loader creates every block read-write-execute, and the decompiler
// refuses to fold a load from writable memory — so every PC-relative literal-pool load shows up
// as `*piRam0007ab3c` / `(*pcRam0007ab40)(1,0)` instead of the pool's value (`ccp_cro_pending`,
// `module_method_dispatch(1,0)`), and a table jump through the pool is never resolved into a
// switch. With the blocks read-only the decompiler (with its default "respect read-only flags"
// option) propagates the pool constants, and DecompilerParameterIdCmd sees resolved callees.
// Idempotent; run before AnalyzeSignatures. The recovery scripts never look at block
// permissions (they classify reference types), so recovery stays a no-op afterwards.
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.program.model.mem.MemoryBlock;

public class ReadOnlyFlash extends GhidraScript {
    @Override public void run() throws Exception {
        int n = 0, already = 0;
        for (MemoryBlock b : currentProgram.getMemory().getBlocks()) {
            long o = b.getStart().getOffset();
            if (!b.isInitialized() || o < 0x40000L || o >= 0x100000L) continue;
            if (b.isWrite()) { b.setWrite(false); n++; } else already++;
        }
        println("ReadOnlyFlash: flash blocks set read-only=" + n + " alreadyReadOnly=" + already);
    }
}
