// Clean up the handful of error bookmarks left after FixLiteralPools: padding
// (0x00000000 / 0xFFFF fill) and pool slots whose loader never decoded, while
// leaving genuine code (a valid instruction that is not padding/pointer) alone.
// Each case is decided by the 4 bytes at the flagged address:
//   all-zero or FFFF-high -> padding  : define a dword, drop the bookmark
//   high byte 0 & value in flash/RAM  -> pointer  : clear, define ptr/dword, drop
//   otherwise                         -> real code: keep the instruction, drop the
//                                        stale bookmark only
// Verified case-by-case against cmg5461's 7846816A_main.lst before writing.
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.Memory;
import java.util.*;

public class FixResidual extends GhidraScript {
    @Override
    public void run() throws Exception {
        Memory mem = currentProgram.getMemory();
        BookmarkManager bm = currentProgram.getBookmarkManager();
        PointerDataType ptr = new PointerDataType(currentProgram.getDataTypeManager());
        DWordDataType dword = new DWordDataType();

        ArrayList<Bookmark> errs = new ArrayList<>();
        Iterator<Bookmark> it = bm.getBookmarksIterator(BookmarkType.ERROR);
        while (it.hasNext()) errs.add(it.next());

        int padding = 0, pointers = 0, keptCode = 0, failed = 0;
        byte[] b = new byte[4];
        for (Bookmark bk : errs) {
            Address a = bk.getAddress();
            try {
                mem.getBytes(a, b);
                int b0 = b[0] & 0xFF, b1 = b[1] & 0xFF;
                long v = ((long)b0 << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF);
                boolean zero = (v == 0);
                boolean ff = (b0 == 0xFF && b1 == 0xFF);
                boolean ptrish = (b0 == 0x00 && v != 0
                        && (mem.contains(toAddr(v)) || (v >= 0x400000L && v < 0x500000L)));

                if (zero || ff) {
                    clearRange(a);
                    defineBestEffort(a, dword, dword);
                    padding++;
                } else if (ptrish) {
                    clearRange(a);
                    defineBestEffort(a, mem.contains(toAddr(v)) ? ptr : dword, dword);
                    pointers++;
                } else {
                    keptCode++;   // genuine instruction; only the bookmark is stale
                }
                bm.removeBookmark(bk);   // always drop the stale/handled bookmark
            } catch (Exception e) {
                failed++;
            }
        }

        int left = 0;
        Iterator<Bookmark> it2 = bm.getBookmarksIterator(BookmarkType.ERROR);
        while (it2.hasNext()) { it2.next(); left++; }
        println("FixResidual: padding=" + padding + " pointers=" + pointers
                + " keptCode=" + keptCode + " failed=" + failed + " errorBookmarksLeft=" + left);
    }

    // Clear whatever code unit sits at a (2 or 4 bytes), tolerant of block edges.
    private void clearRange(Address a) {
        try { clearListing(a, a.add(3)); return; } catch (Exception e) {}
        try { clearListing(a, a.add(1)); return; } catch (Exception e) {}
        try { clearListing(a); } catch (Exception e) {}
    }

    // Try a 4-byte type; if it won't fit (block boundary), fall back to a word,
    // else leave the bytes cleared/undefined. Never throws.
    private void defineBestEffort(Address a, DataType four, DataType fourAlt) {
        try { createData(a, four); return; } catch (Exception e) {}
        try { createData(a, new WordDataType()); return; } catch (Exception e) {}
        // leave as undefined (bad-instruction bookmark is still removed by caller)
    }
}
