// WRITE: remove the informational Analysis bookmarks (orange in the overview margin: "Found Code",
// "Address Table", ...) that auto-analysis leaves. They are breadcrumbs, not errors/TODOs. Keeps
// Error/Warning/Note. Run as the LAST step of the recovery pipeline for a clean overview margin.
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import java.util.*;
public class ClearAnalysisBookmarks extends GhidraScript {
    public void run() throws Exception {
        BookmarkManager bm = currentProgram.getBookmarkManager();
        int removed=0;
        for (String t : new String[]{"Analysis","Info"}) {
            ArrayList<Bookmark> rm=new ArrayList<>();
            for (Iterator<Bookmark> it=bm.getBookmarksIterator(t); it.hasNext();) rm.add(it.next());
            for (Bookmark b: rm){ bm.removeBookmark(b); removed++; }
        }
        println("ClearAnalysisBookmarks: removed="+removed);
    }
}
