// Fix M-CORE literal pools that auto-analysis mis-disassembled as code.
//
// M-CORE has no large immediates: lrw/jmpi/jsri load a 32-bit constant or
// pointer from a PC-relative literal pool, which the compiler drops right after
// each function. Ghidra's analysis falls through an unconditional tail jmpi/br
// into that pool, decodes some of it as instructions, collides with the data the
// lrw reference already created, and marks "bad instruction" -- which truncates
// the decompiled function and paints the scrollbar red.
//
// This pass harvests every in-flash *read* target of an instruction (= a pool
// slot), clears whatever code landed on it, and defines it as a 4-byte pointer
// (if the value lands in mapped memory) or a dword. Flow targets (call/jump/
// branch destinations) and function entry points are excluded, so real code is
// never touched. Then it drops stale "Bad Instruction" error bookmarks whose
// address is no longer an instruction. Idempotent; re-runnable.
//
// Bounded: only slots inside [LO, HI) — the code region — are typed. Literal pools live
// between functions; a calibration table above dataLO that an instruction reads is NOT a
// pool slot, and typing its first 4 bytes as a pointer whenever they happen to look like a
// flash address is a mistake ClassifyData/ApplyDataTypes then (rightly) refuse to undo.
// Table bases in the data section are DataSectionClean's (labels) and ClassifyData's
// (width) business. With no args the old unbounded behaviour is kept.
//
// Usage (headless, writes back -- close the GUI first for the project lock):
//   -process 7846816A.cpu.hex -noanalysis -postScript FixLiteralPools.java [LO HI]
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.*;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.RefType;
import java.util.*;

public class FixLiteralPools extends GhidraScript {
    private long LO = Long.MIN_VALUE, HI = Long.MAX_VALUE;

    @Override
    public void run() throws Exception {
        String[] _bnd = getScriptArgs(); if (_bnd.length >= 2) { LO = Long.parseLong(_bnd[0], 16); HI = Long.parseLong(_bnd[1], 16); }
        Listing listing = currentProgram.getListing();
        Memory mem = currentProgram.getMemory();
        BookmarkManager bm = currentProgram.getBookmarkManager();

        int errBefore = countErrors(bm);

        // Addresses that are real code: every flow destination + every function entry.
        HashSet<Long> codeTargets = new HashSet<>();
        for (Function f : currentProgram.getFunctionManager().getFunctions(true)) {
            codeTargets.add(f.getEntryPoint().getOffset());
        }

        // Pool slots: in-memory read/data targets of instructions (lrw/jsri/jmpi ...).
        HashSet<Long> pool = new HashSet<>();
        int ramRefs = 0, outOfRange = 0;
        InstructionIterator iit = listing.getInstructions(true);
        while (iit.hasNext()) {
            Instruction ins = iit.next();
            for (Reference r : ins.getReferencesFrom()) {
                RefType rt = r.getReferenceType();
                Address to = r.getToAddress();
                if (to == null || !to.isMemoryAddress()) continue;
                if (rt.isFlow()) { codeTargets.add(to.getOffset()); continue; }
                if (rt.isWrite() || !(rt.isRead() || rt.isData())) continue;
                if (!mem.contains(to)) continue;   // unmapped constants aren't flash pools
                if (to.getOffset() < LO || to.getOffset() >= HI) { outOfRange++; continue; }   // data-section table, not a pool
                MemoryBlock blk = mem.getBlock(to);
                if (blk == null || !blk.isInitialized()) { ramRefs++; continue; }  // RAM variable, not a pool
                pool.add(to.getOffset());
            }
        }
        pool.removeAll(codeTargets);

        ArrayList<Long> slots = new ArrayList<>(pool);
        Collections.sort(slots);

        PointerDataType ptr = new PointerDataType(currentProgram.getDataTypeManager());
        DWordDataType dword = new DWordDataType();
        int made = 0, ptrs = 0, skipped = 0, failed = 0;
        ArrayList<String> failEx = new ArrayList<>();   // first few failures, for the log
        byte[] b = new byte[4];
        for (long off : slots) {
            Address p = toAddr(off);
            try {
                Data d = getDataAt(p);
                if (d != null && d.isDefined() && d.getLength() == 4) { skipped++; continue; }
                // Inside (or at the base of) a typed table — an Array/Structure a curator or the
                // annotation replay applied — this is table data, not a pool slot: clearing the
                // 4 bytes would destroy the whole item and leave fragments for RecoverCodeRuns.
                Data dc = listing.getDataContaining(p);
                if (dc != null && (dc.getDataType() instanceof Array || dc.getDataType() instanceof Structure)) { skipped++; continue; }
                // Arbitration: a read-target reached by fall-through from a non-terminator is
                // truncated-function CODE (see FixTruncated), not a literal pool -- leave it.
                // Real pools sit after a terminator (jmpi/br/rts) and have no fall-through in.
                Instruction prevI = getInstructionBefore(p);
                if (prevI != null && p.equals(prevI.getFallThrough())) { skipped++; continue; }
                clearListing(p, p.add(3));
                mem.getBytes(p, b);
                long v = ((long)(b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16)
                        | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
                boolean isPtr = false;
                if (v != 0) {
                    try { isPtr = mem.contains(toAddr(v)); } catch (Exception ignore) {}
                }
                createData(p, isPtr ? ptr : dword);
                if (isPtr) ptrs++;
                made++;
            } catch (Exception e) {
                // A slot that can't be cleared/typed, e.g. its 4 bytes run off the end of a
                // block (0x8ed in the low config block). Stable across passes and harmless.
                failed++;
                if (failEx.size() < 3) { String m = String.valueOf(e.getMessage()); failEx.add(p + ":" + e.getClass().getSimpleName() + "[" + (m.length() > 70 ? m.substring(0, 70) : m) + "]"); }
            }
        }

        // Drop stale bad-instruction bookmarks now sitting on data.
        ArrayList<Bookmark> stale = new ArrayList<>();
        Iterator<Bookmark> bit = bm.getBookmarksIterator(BookmarkType.ERROR);
        while (bit.hasNext()) {
            Bookmark bk = bit.next();
            if (getInstructionAt(bk.getAddress()) == null) stale.add(bk);
        }
        for (Bookmark bk : stale) bm.removeBookmark(bk);

        int errAfter = countErrors(bm);
        println("FixLiteralPools: poolSlots=" + slots.size() + " defined=" + made
                + " pointers=" + ptrs + " alreadyData=" + skipped + " ramRefsSkipped=" + ramRefs + " outsideCodeRegion=" + outOfRange
                + " failed=" + failed
                + (failEx.isEmpty() ? "" : " (failed = slot can't be typed, harmless; e.g. " + String.join(" ", failEx) + ")"));
        println("FixLiteralPools: errorBookmarks before=" + errBefore
                + " removedStale=" + stale.size() + " after=" + errAfter);
    }

    private int countErrors(BookmarkManager bm) {
        int n = 0;
        Iterator<Bookmark> it = bm.getBookmarksIterator(BookmarkType.ERROR);
        while (it.hasNext()) { it.next(); n++; }
        return n;
    }
}
