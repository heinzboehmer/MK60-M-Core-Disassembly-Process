// Read-only: dump every hand-made annotation of a NAMED reference project so it can be
// replayed by address onto another project of the SAME image (ApplyAnnotations checks the
// image hash and refuses otherwise — addresses mean nothing across variants; use the
// signature port for those). Runs on a snapshot, so the reference GUI may stay open.
//
// Output (TSV, one record per line; text has \t and \n escaped):
//   H  <fnv64 of all initialized bytes>
//   F  <addr>  <name>                         user-named function entry
//   L  <addr>  <name>  P|S                    user label, primary or secondary
//   C  <addr>  EOL|PRE|POST|PLATE|REPEATABLE  <text>
//   B  <addr>  <type>  <category>  <text>     non-analysis bookmark
//   D  <addr>  u8|s8|u16|s16|u32|s32|bool|char   concrete scalar type on data
// Args: <out.tsv>
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.*;
import ghidra.program.model.symbol.*;
import java.io.*;
import java.util.*;

public class ExportAnnotations extends GhidraScript {
    @Override
    public void run() throws Exception {
        String out = getScriptArgs()[0];
        Memory mem = currentProgram.getMemory();
        Listing ls = currentProgram.getListing();
        SymbolTable st = currentProgram.getSymbolTable();
        int fn = 0, lab = 0, com = 0, bm = 0, typ = 0;
        try (PrintWriter w = new PrintWriter(new FileWriter(out))) {
            w.println("H\t" + String.format("%016x", imageHash(mem)));

            for (Symbol s : st.getAllSymbols(false)) {
                if (s.getSource() == SourceType.DEFAULT) continue;
                Address a = s.getAddress();
                if (!a.isMemoryAddress()) continue;
                String name = s.getName();
                if (name.startsWith("FUN_") || name.startsWith("LAB_") || name.startsWith("DAT_")
                        || name.startsWith("SUB_") || name.startsWith("thunk_") || name.startsWith("tbl_")) continue;
                if (s.getSymbolType() == SymbolType.FUNCTION) { w.println("F\t" + hex(a) + "\t" + name); fn++; }
                else if (s.getSymbolType() == SymbolType.LABEL && s.getSource() == SourceType.USER_DEFINED) {
                    w.println("L\t" + hex(a) + "\t" + name + "\t" + (s.isPrimary() ? "P" : "S")); lab++;
                }
            }

            String[] cn = {"EOL", "PRE", "POST", "PLATE", "REPEATABLE"};
            CommentType[] ct = {CommentType.EOL, CommentType.PRE, CommentType.POST, CommentType.PLATE, CommentType.REPEATABLE};
            for (int t = 0; t < ct.length; t++) {
                AddressIterator it = ls.getCommentAddressIterator(ct[t], mem, true);
                while (it.hasNext()) {
                    Address a = it.next();
                    String c = ls.getComment(ct[t], a);
                    if (c == null || c.isEmpty()) continue;
                    w.println("C\t" + hex(a) + "\t" + cn[t] + "\t" + esc(c)); com++;
                }
            }

            Iterator<Bookmark> bit = currentProgram.getBookmarkManager().getBookmarksIterator();
            while (bit.hasNext()) {
                Bookmark b = bit.next();
                if (b.getTypeString().equals("Analysis") || b.getTypeString().equals("Error")) continue;
                w.println("B\t" + hex(b.getAddress()) + "\t" + b.getTypeString() + "\t" + esc(b.getCategory()) + "\t" + esc(b.getComment())); bm++;
            }

            for (Data d : ls.getDefinedData(true)) {
                String tc = typeCode(d.getDataType());
                if (tc == null) continue;
                w.println("D\t" + hex(d.getAddress()) + "\t" + tc); typ++;
            }
        }
        println("ExportAnnotations: functions=" + fn + " labels=" + lab + " comments=" + com
                + " bookmarks=" + bm + " dataTypes=" + typ + " -> " + out);
    }

    static String typeCode(DataType dt) {
        if (dt instanceof TypeDef) dt = ((TypeDef) dt).getBaseDataType();
        if (dt instanceof BooleanDataType) return "bool";
        if (dt instanceof CharDataType) return "char";
        if (dt instanceof ByteDataType) return "u8";
        if (dt instanceof SignedByteDataType) return "s8";
        if (dt instanceof UnsignedShortDataType || dt instanceof WordDataType) return "u16";
        if (dt instanceof ShortDataType) return "s16";
        if (dt instanceof UnsignedIntegerDataType || dt instanceof DWordDataType) return "u32";
        if (dt instanceof IntegerDataType) return "s32";
        return null;
    }

    static long imageHash(Memory mem) throws Exception {
        long h = 1469598103934665603L;
        for (MemoryBlock b : mem.getBlocks()) {
            if (!b.isInitialized()) continue;
            byte[] buf = new byte[(int) b.getSize()];
            mem.getBytes(b.getStart(), buf);
            for (byte x : buf) { h ^= (x & 0xFF); h *= 1099511628211L; }
        }
        return h;
    }

    static String hex(Address a) { return String.format("%06x", a.getOffset()); }
    static String esc(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n"); }
}
