// WRITE: replay ExportAnnotations output onto a project of the SAME image, by address.
// Refuses (changes nothing) if the image hash differs. Rules, per record:
//   F  function at addr -> rename if ours is generic (FUN_/LAB_/…) or different;
//      no function but code inside a function -> primary label there (the reference split a
//      branch-only block into its own function; we keep the body whole and keep the name);
//      undefined/data -> label.
//   L  our primary at addr is generic (DAT_/LAB_/tbl_…) or absent -> this name, primary;
//      ours is a different real name -> add as a secondary label (both kept, counted).
//   C  no comment of that type -> set; ours is the "[auto-ported …]" marker -> replace;
//      same text -> nothing; different -> ours kept, reference text appended.
//   B  bookmark created if none of that type/category/text exists at addr.
//   D  scalar type applied only where our data is undefined / our own stdint typedef /
//      a plain byte-word-dword (same guard as ApplyDataTypes); sized to fit.
// Args: <annotations.tsv>
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.*;
import ghidra.program.model.symbol.*;
import java.io.*;
import java.util.*;

public class ApplyAnnotations extends GhidraScript {
    private DataTypeManager dtm;

    @Override
    public void run() throws Exception {
        String path = getScriptArgs()[0];
        Memory mem = currentProgram.getMemory();
        Listing ls = currentProgram.getListing();
        SymbolTable st = currentProgram.getSymbolTable();
        dtm = currentProgram.getDataTypeManager();
        String ours = String.format("%016x", ExportAnnotations.imageHash(mem));

        int fnRenamed = 0, fnLabel = 0, fnSame = 0, labSet = 0, labSecondary = 0, labSame = 0,
            comSet = 0, comAppended = 0, comSame = 0, bmSet = 0, typSet = 0, typSkipped = 0, failed = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String ln = br.readLine();
            if (ln == null || !ln.startsWith("H\t")) { println("ApplyAnnotations: bad file (no H line)"); return; }
            String theirs = ln.substring(2).trim();
            if (!theirs.equals(ours)) {
                println("ApplyAnnotations: IMAGE MISMATCH (reference " + theirs + " vs this " + ours
                        + ") — nothing applied; address-based porting is only valid for the same image.");
                return;
            }
            while ((ln = br.readLine()) != null) {
                String[] c = ln.split("\t", -1);
                if (c.length < 3) continue;
                Address a;
                try { a = toAddr(Long.parseLong(c[1], 16)); } catch (Exception e) { continue; }
                if (!mem.contains(a) && !c[0].equals("L")) continue;
                try {
                    switch (c[0]) {
                    case "F": {
                        String name = c[2];
                        Function f = getFunctionAt(a);
                        if (f != null) {
                            if (f.getName().equals(name)) { fnSame++; break; }
                            f.setName(name, SourceType.USER_DEFINED); fnRenamed++;
                        } else {
                            Symbol p = st.getPrimarySymbol(a);
                            if (p != null && p.getName().equals(name)) { fnSame++; break; }
                            createLabel(a, name, true, SourceType.USER_DEFINED); fnLabel++;
                        }
                        break;
                    }
                    case "L": {
                        String name = c[2]; boolean primary = c.length > 3 && c[3].equals("P");
                        Symbol p = st.getPrimarySymbol(a);
                        if (p != null && p.getName().equals(name)) { labSame++; break; }
                        boolean exists = false;
                        for (Symbol s : st.getSymbols(a)) if (s.getName().equals(name)) { exists = true; break; }
                        if (exists) { labSame++; break; }
                        if (p == null || p.getSource() == SourceType.DEFAULT || generic(p.getName())) {
                            if (p != null && p.getSource() != SourceType.DEFAULT && generic(p.getName())) p.delete();
                            createLabel(a, name, primary, SourceType.USER_DEFINED); labSet++;
                        } else {
                            createLabel(a, name, false, SourceType.USER_DEFINED); labSecondary++;
                        }
                        break;
                    }
                    case "C": {
                        CommentType t = ctype(c[2]); String text = unesc(c[3]);
                        String cur = ls.getComment(t, a);
                        if (cur == null || cur.isEmpty() || cur.startsWith("[auto-ported")) { ls.setComment(a, t, text); comSet++; }
                        else if (cur.equals(text) || cur.contains(text)) comSame++;
                        else { ls.setComment(a, t, cur + "\n" + text); comAppended++; }
                        break;
                    }
                    case "B": {
                        String type = c[2], cat = unesc(c[3]), text = unesc(c[4]);
                        boolean have = false;
                        for (Bookmark b : currentProgram.getBookmarkManager().getBookmarks(a))
                            if (b.getTypeString().equals(type) && b.getCategory().equals(cat) && b.getComment().equals(text)) have = true;
                        if (!have) { createBookmark(a, cat, text); bmSet++; }
                        break;
                    }
                    case "D": {
                        if (ls.getInstructionContaining(a) != null) { typSkipped++; break; }
                        Data cur = ls.getDefinedDataAt(a);
                        if (cur != null && !retypeable(cur.getDataType())) { typSkipped++; break; }
                        int size = sizeFor(c[2]);
                        int fit = fitSize(ls, a, size);
                        DataType dt = dtFor(c[2], fit);
                        clearListing(a, a.add(fit - 1));
                        createData(a, dt); typSet++;
                        break;
                    }
                    default: break;
                    }
                } catch (Exception e) { failed++; }
            }
        }
        println("ApplyAnnotations: functions renamed=" + fnRenamed + " asLabel=" + fnLabel + " same=" + fnSame
                + " | labels set=" + labSet + " secondary=" + labSecondary + " same=" + labSame
                + " | comments set=" + comSet + " appended=" + comAppended + " same=" + comSame
                + " | bookmarks=" + bmSet + " | types set=" + typSet + " skipped=" + typSkipped + " | failed=" + failed);
    }

    static boolean generic(String n) {
        return n.startsWith("FUN_") || n.startsWith("LAB_") || n.startsWith("DAT_") || n.startsWith("SUB_")
                || n.startsWith("thunk_") || n.startsWith("tbl_") || n.startsWith("uint8_t_") || n.startsWith("uint16_t_")
                || n.startsWith("uint32_t_") || n.startsWith("PTR_") || n.startsWith("DWORD_") || n.startsWith("WORD_") || n.startsWith("BYTE_");
    }
    static CommentType ctype(String s) {
        switch (s) { case "PRE": return CommentType.PRE; case "POST": return CommentType.POST; case "PLATE": return CommentType.PLATE;
            case "REPEATABLE": return CommentType.REPEATABLE; default: return CommentType.EOL; }
    }
    static String unesc(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) { char n = s.charAt(++i); b.append(n == 'n' ? '\n' : n == 't' ? '\t' : n); }
            else b.append(ch);
        }
        return b.toString();
    }
    static boolean retypeable(DataType dt) {
        return dt instanceof TypeDef || dt instanceof Undefined || dt instanceof DefaultDataType
                || dt instanceof ByteDataType || dt instanceof WordDataType || dt instanceof DWordDataType || dt instanceof QWordDataType;
    }
    static int sizeFor(String tc) { switch (tc) { case "u16": case "s16": return 2; case "u32": case "s32": return 4; default: return 1; } }
    private DataType typedef(String name, DataType base) {
        DataType ex = dtm.getDataType(new DataTypePath(CategoryPath.ROOT, name));
        return ex != null ? ex : dtm.addDataType(new TypedefDataType(CategoryPath.ROOT, name, base), DataTypeConflictHandler.DEFAULT_HANDLER);
    }
    private DataType dtFor(String tc, int size) {
        boolean signed = tc.startsWith("s");
        if (tc.equals("bool") && size == 1) return BooleanDataType.dataType;
        if (tc.equals("char") && size == 1) return CharDataType.dataType;
        if (size == 4) return signed ? typedef("int32_t", IntegerDataType.dataType) : typedef("uint32_t", UnsignedIntegerDataType.dataType);
        if (size == 2) return signed ? typedef("int16_t", ShortDataType.dataType) : typedef("uint16_t", UnsignedShortDataType.dataType);
        return signed ? typedef("int8_t", SignedByteDataType.dataType) : typedef("uint8_t", ByteDataType.dataType);
    }
    private int fitSize(Listing listing, Address a, int want) {
        int size = want;
        while (size > 1) {
            boolean ok = true;
            for (int k = 1; k < size; k++) {
                Address nx = a.add(k);
                if (getSymbolAt(nx) != null || listing.getInstructionContaining(nx) != null) { ok = false; break; }
            }
            if (ok) return size;
            size >>= 1;
        }
        return 1;
    }
}
