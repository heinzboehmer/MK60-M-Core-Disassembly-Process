//@category MK60
import ghidra.app.script.GhidraScript; import ghidra.program.model.listing.*;
public class CountErr extends GhidraScript { public void run() throws Exception {
 BookmarkManager bm=currentProgram.getBookmarkManager(); int n=0;
 for(java.util.Iterator<Bookmark> it=bm.getBookmarksIterator("Error");it.hasNext();){it.next();n++;}
 println("Error bookmarks="+n+" functions="+currentProgram.getFunctionManager().getFunctionCount()); }}
