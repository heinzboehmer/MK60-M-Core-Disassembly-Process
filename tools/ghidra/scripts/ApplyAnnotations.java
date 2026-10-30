// WRITE: replay ExportAnnotations output onto a project of the SAME image, by address.
// Refuses (changes nothing) if the image hash differs. Applied in phases:
//   K  missing uninitialized blocks are recreated (initialized ones must already exist);
//   E/T  enums and structures are (re)created in this program's data-type manager — all
//      structure shells first, then their fields, so nested references resolve;
//   low-region reset: below CODE_LO (0x42000) the pipeline leaves auto-analysis noise; in a
//      same-image port the reference is authoritative there, so unnamed FUN_ functions at
//      entries the reference does not have are removed and their instructions cleared;
//   N  function entries: created where we have none and no other function owns the address
//      (an entry inside one of our whole bodies is skipped — the body model wins), and only
//      where the entry is ALREADY an instruction here: raw bytes are never disassembled on
//      the reference's say-so (its unnamed entries are its own auto-analysis noise, in the
//      code region and below CODE_LO alike); at/above CODE_HI (the data section) never. New
//      functions get the program's default calling convention;
//   F  function at addr -> rename if ours is generic or different; no function but code inside
//      a function -> primary label; undefined/data -> label.
//   L  our primary at addr is generic (DAT_/LAB_/tbl_…) or absent -> this name, primary;
//      ours is a different real name -> added as a secondary label (both kept, counted).
//   C  no comment of that type -> set; ours is the "[auto-ported …]" marker -> replace;
//      same text -> nothing; different -> ours kept, reference text appended.
//   B  bookmark created if none of that type/category/text exists at addr.
//   D  scalar type applied OUTSIDE the code region only (data section, RAM/MMIO, below
//      CODE_LO), and only where we have NO defined data or a retypeable item of the SAME
//      length (undefined / own stdint typedef / plain byte-word-dword). In the code region
//      the pools are FixLiteralPools' business: a stray scalar in a long undefined pool run
//      splits it into fragments the next RecoverCodeRuns pass accepts as code, and a 4-byte
//      slot shrunk to 1 byte does the same.
//   X  composite/array/enum/typed-pointer applied when every code unit in its span is
//      retypeable (no instructions, no composite already there).
// Args: <annotations.tsv> [CODE_HI hex = start of the data section, from <prog>.bounds]
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
    private static final long CODE_LO = 0x42000L;
    private DataTypeManager dtm;
    private ghidra.app.util.PseudoDisassembler pd;
    private Listing ls;
    private Memory mem;
    private SymbolTable st;

    @Override
    public void run() throws Exception {
        String path = getScriptArgs()[0];
        long codeHi = getScriptArgs().length > 1 ? Long.parseLong(getScriptArgs()[1], 16) : Long.MAX_VALUE;
        pd = new ghidra.app.util.PseudoDisassembler(currentProgram);
        mem = currentProgram.getMemory();
        ls = currentProgram.getListing();
        st = currentProgram.getSymbolTable();
        dtm = currentProgram.getDataTypeManager();
        String ours = String.format("%016x", ExportAnnotations.imageHash(mem));

        ArrayList<String[]> recs = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String ln = br.readLine();
            if (ln == null || !ln.startsWith("H\t")) { println("ApplyAnnotations: bad file (no H line)"); return; }
            String theirs = ln.substring(2).trim();
            if (!theirs.equals(ours)) {
                println("ApplyAnnotations: IMAGE MISMATCH (reference " + theirs + " vs this " + ours
                        + ") — nothing applied; address-based porting is only valid for the same image.");
                return;
            }
            while ((ln = br.readLine()) != null) { String[] c = ln.split("\t", -1); if (c.length >= 2) recs.add(c); }
        }

        int blocks = 0, enums = 0, structs = 0, lowRemoved = 0, entries = 0, entriesInBody = 0, entriesSkipped = 0,
            compSkipInstr = 0, compSkipComposite = 0, compSkipType = 0,
            fnRenamed = 0, fnLabel = 0, fnSame = 0, labSet = 0, labSecondary = 0, labSame = 0,
            comSet = 0, comAppended = 0, comSame = 0, bmSet = 0, typSet = 0, typSkipped = 0,
            compSet = 0, compSkipped = 0, failed = 0;

        // --- K: blocks ---
        for (String[] c : recs) {
            if (!c[0].equals("K") || c.length < 6) continue;
            try {
                Address start = toAddr(Long.parseLong(c[2], 16)); long size = Long.parseLong(c[3]);
                if (mem.getBlock(start) != null) continue;
                if (!c[4].equals("U")) { println("  block " + c[1] + " @" + start + " is initialized in the reference but absent here — not recreated"); continue; }
                MemoryBlock b = mem.createUninitializedBlock(unesc(c[1]), start, size, false);
                b.setRead(c[5].charAt(0) == 'r'); b.setWrite(c[5].charAt(1) == 'w'); b.setExecute(c[5].charAt(2) == 'x');
                if (c[5].length() > 3 && c[5].charAt(3) == 'v') b.setVolatile(true);
                blocks++;
            } catch (Exception e) { failed++; }
        }

        // --- E: enums ---
        for (String[] c : recs) {
            if (!c[0].equals("E") || c.length < 4) continue;
            try {
                EnumDataType e = new EnumDataType(CategoryPath.ROOT, unesc(c[1]), Integer.parseInt(c[2]));
                for (String kv : splitList(c[3])) { int i = kv.indexOf('='); if (i > 0) e.add(unesc(kv.substring(i + 1)), Long.parseLong(kv.substring(0, i))); }
                dtm.addDataType(e, DataTypeConflictHandler.REPLACE_HANDLER); enums++;
            } catch (Exception e) { failed++; }
        }
        // --- T: structure shells, then fields ---
        HashMap<String, Structure> shells = new HashMap<>();
        for (String[] c : recs) {
            if (!c[0].equals("T") || c.length < 4) continue;
            try {
                String name = unesc(c[1]); int len = Integer.parseInt(c[2]);
                StructureDataType s = new StructureDataType(CategoryPath.ROOT, name, len);
                shells.put(name, (Structure) dtm.addDataType(s, DataTypeConflictHandler.REPLACE_HANDLER));
            } catch (Exception e) { failed++; }
        }
        for (String[] c : recs) {
            if (!c[0].equals("T") || c.length < 4) continue;
            Structure s = shells.get(unesc(c[1])); if (s == null) continue;
            try {
                for (String f : splitList(c[3])) {
                    String[] p = splitFields(f); if (p.length < 4) continue;
                    int off = Integer.parseInt(p[0]); String fname = unesc(p[1]); DataType ft = resolve(unesc(p[2])); int flen = Integer.parseInt(p[3]);
                    if (ft == null) ft = Undefined.getUndefinedDataType(flen);
                    s.replaceAtOffset(off, ft, flen, fname.isEmpty() ? null : fname, null);
                }
                structs++;
            } catch (Exception e) { failed++; }
        }

        // --- low-region reset + N: function entries ---
        HashSet<Long> refEntries = new HashSet<>();
        for (String[] c : recs) if (c[0].equals("N")) { try { refEntries.add(Long.parseLong(c[1], 16)); } catch (Exception e) {} }
        if (!refEntries.isEmpty()) {
            ArrayList<Function> junk = new ArrayList<>();
            for (Function f : currentProgram.getFunctionManager().getFunctions(true)) {
                long o = f.getEntryPoint().getOffset();
                if (o >= CODE_LO || !f.getName().startsWith("FUN_") || refEntries.contains(o)) continue;
                junk.add(f);
            }
            for (Function f : junk) {
                try { AddressSetView body = f.getBody(); removeFunction(f); for (AddressRange r : body) clearListing(r.getMinAddress(), r.getMaxAddress()); lowRemoved++; } catch (Exception e) { failed++; }
            }
        }
        String defaultCC = currentProgram.getCompilerSpec().getDefaultCallingConvention().getName();
        for (long o : new TreeSet<>(refEntries)) {
            Address a = toAddr(o);
            if (!mem.contains(a) || o >= codeHi) { entriesSkipped++; continue; }     // data section: never
            if (getFunctionAt(a) != null) continue;
            if (getFunctionContaining(a) != null) { entriesInBody++; continue; }
            try {
                if (getInstructionAt(a) == null) {
                    if (getInstructionContaining(a) != null) { entriesInBody++; continue; }
                    // Never disassemble raw bytes on the reference's say-so: in the code region
                    // its unnamed entries may be its own spurious ones, and below CODE_LO they
                    // are mostly auto-analysis noise there too (the few real init stubs are
                    // NAMED and come through as F records, which name whatever is here).
                    entriesSkipped++; continue;
                }
                if (getInstructionAt(a) != null) {
                    Function nf = createFunction(a, null);
                    if (nf != null) { entries++; try { nf.setCallingConvention(defaultCC); } catch (Exception e) {} }
                }
            } catch (Exception e) { failed++; }
        }

        // --- F L C B D X in file order ---
        for (String[] c : recs) {
            if (c.length < 3) continue;
            Address a;
            try { a = toAddr(Long.parseLong(c[1], 16)); } catch (Exception e) { continue; }
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
                    if (c.length < 4) break;
                    CommentType t = ctype(c[2]); String text = unesc(c[3]);
                    String cur = ls.getComment(t, a);
                    if (cur == null || cur.isEmpty() || cur.startsWith("[auto-ported")) { ls.setComment(a, t, text); comSet++; }
                    else if (cur.equals(text) || cur.contains(text)) comSame++;
                    else { ls.setComment(a, t, cur + "\n" + text); comAppended++; }
                    break;
                }
                case "B": {
                    if (c.length < 5) break;
                    String type = c[2], cat = unesc(c[3]), text = unesc(c[4]);
                    boolean have = false;
                    for (Bookmark b : currentProgram.getBookmarkManager().getBookmarks(a))
                        if (b.getTypeString().equals(type) && b.getCategory().equals(cat) && b.getComment().equals(text)) have = true;
                    if (!have) { createBookmark(a, cat, text); bmSet++; }
                    break;
                }
                case "D": {
                    long ao = a.getOffset();
                    // Code region: pool typing is FixLiteralPools/ApplyDataTypes' job. A stray
                    // scalar dropped into a long undefined pool run splits it into fragments
                    // short enough for RecoverCodeRuns to accept as code on the next recovery.
                    if (ao >= CODE_LO && ao < codeHi) { typSkipped++; break; }
                    if (!mem.contains(a) || ls.getInstructionContaining(a) != null) { typSkipped++; break; }
                    Data cur = ls.getDefinedDataAt(a);
                    int size = sizeFor(c[2]);
                    if (cur != null && (!retypeable(cur.getDataType()) || cur.getLength() != size)) { typSkipped++; break; }
                    if (cur == null && ls.getDataContaining(a) != null && ls.getDataContaining(a).isDefined()) { typSkipped++; break; } // inside an item
                    DataType dt = dtFor(c[2], size);
                    if (cur != null && cur.getDataType().isEquivalent(dt)) { typSkipped++; break; }
                    int fit = fitSize(a, size);
                    if (fit != size) { typSkipped++; break; }
                    clearListing(a, a.add(size - 1));
                    createData(a, dt); typSet++;
                    break;
                }
                case "X": {
                    DataType dt = resolve(unesc(c[2]));
                    if (dt == null || dt.getLength() <= 0 || !mem.contains(a)) { compSkipType++; break; }
                    Data cur = ls.getDefinedDataAt(a);
                    if (cur != null && cur.getDataType().isEquivalent(dt)) { compSkipped++; break; }   // already there
                    Address end = a.add(dt.getLength() - 1);
                    if (!mem.contains(end)) { compSkipType++; break; }
                    if (ls.getInstructions(new AddressSet(a, end), true).hasNext()) { compSkipInstr++; break; }
                    if (!spanRetypeable(a, end)) { compSkipComposite++; break; }
                    clearListing(a, end);
                    createData(a, dt); compSet++;
                    break;
                }
                default: break;
                }
            } catch (Exception e) { failed++; }
        }
        println("ApplyAnnotations: blocks=" + blocks + " enums=" + enums + " structs=" + structs
                + " | lowRegion junkRemoved=" + lowRemoved + " entries created=" + entries + " inBody=" + entriesInBody + " skipped=" + entriesSkipped
                + " | functions renamed=" + fnRenamed + " asLabel=" + fnLabel + " same=" + fnSame
                + " | labels set=" + labSet + " secondary=" + labSecondary + " same=" + labSame
                + " | comments set=" + comSet + " appended=" + comAppended + " same=" + comSame
                + " | bookmarks=" + bmSet + " | scalar types set=" + typSet + " skipped=" + typSkipped
                + " | composite types set=" + compSet + " same=" + compSkipped + " skippedInstr=" + compSkipInstr
                + " skippedComposite=" + compSkipComposite + " skippedType=" + compSkipType + " | failed=" + failed);
    }

    // ---- type strings ----
    private DataType resolve(String ts) {
        ts = ts.trim();
        if (ts.endsWith("*")) {
            DataType b = resolve(ts.substring(0, ts.length() - 1));
            return b == null ? new PointerDataType(dtm) : new PointerDataType(b, dtm);
        }
        if (ts.endsWith("]")) {
            int i = ts.lastIndexOf('[');
            if (i > 0) {
                int n = Integer.parseInt(ts.substring(i + 1, ts.length() - 1));
                DataType e = resolve(ts.substring(0, i));
                if (e == null || e.getLength() <= 0 || n <= 0) return null;
                return new ArrayDataType(e, n, e.getLength(), dtm);
            }
        }
        if (ts.equals("pointer")) return new PointerDataType(dtm);
        DataType d = dtm.getDataType(new DataTypePath(CategoryPath.ROOT, ts));
        if (d != null) return d;
        switch (ts) {
            case "byte": return ByteDataType.dataType; case "sbyte": return SignedByteDataType.dataType;
            case "word": return WordDataType.dataType; case "dword": return DWordDataType.dataType; case "qword": return QWordDataType.dataType;
            case "char": return CharDataType.dataType; case "bool": return BooleanDataType.dataType;
            case "short": return ShortDataType.dataType; case "ushort": return UnsignedShortDataType.dataType;
            case "int": return IntegerDataType.dataType; case "uint": return UnsignedIntegerDataType.dataType;
            case "long": return LongDataType.dataType; case "ulong": return UnsignedLongDataType.dataType;
            case "float": return FloatDataType.dataType; case "double": return DoubleDataType.dataType;
            case "undefined": return DataType.DEFAULT; case "undefined1": return Undefined1DataType.dataType;
            case "undefined2": return Undefined2DataType.dataType; case "undefined4": return Undefined4DataType.dataType;
            case "undefined8": return Undefined8DataType.dataType; case "void": return VoidDataType.dataType;
            case "uint8_t": return typedef("uint8_t", ByteDataType.dataType); case "int8_t": return typedef("int8_t", SignedByteDataType.dataType);
            case "uint16_t": return typedef("uint16_t", UnsignedShortDataType.dataType); case "int16_t": return typedef("int16_t", ShortDataType.dataType);
            case "uint32_t": return typedef("uint32_t", UnsignedIntegerDataType.dataType); case "int32_t": return typedef("int32_t", IntegerDataType.dataType);
            default: break;
        }
        ArrayList<DataType> found = new ArrayList<>(); dtm.findDataTypes(ts, found);
        return found.isEmpty() ? null : found.get(0);
    }

    private boolean cleanDecode(Address a, int n) {
        Address p = a;
        try {
            for (int i = 0; i < n; i++) {
                ghidra.app.util.PseudoInstruction pi = pd.disassemble(p);
                if (pi == null || pi.getLength() <= 0) return false;
                p = p.add(pi.getLength());
            }
        } catch (Exception e) { return false; }
        return true;
    }

    private boolean spanRetypeable(Address a, Address end) {
        if (ls.getInstructions(new AddressSet(a, end), true).hasNext()) return false;
        for (Data d : ls.getDefinedData(new AddressSet(a, end), true)) {
            DataType dt = d.getDataType();
            if (!(retypeable(dt) || (dt instanceof Pointer && !(((Pointer) dt).getDataType() instanceof Composite)))) return false;
        }
        return true;
    }

    static List<String> splitList(String s) {   // split on unescaped ';'
        ArrayList<String> out = new ArrayList<>(); StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) { char ch = s.charAt(i); if (ch == '\\' && i + 1 < s.length()) { b.append(ch).append(s.charAt(++i)); continue; } if (ch == ';') { if (b.length() > 0) out.add(b.toString()); b.setLength(0); } else b.append(ch); }
        if (b.length() > 0) out.add(b.toString());
        return out;
    }
    static String[] splitFields(String s) {     // split on unescaped ':'
        ArrayList<String> out = new ArrayList<>(); StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) { char ch = s.charAt(i); if (ch == '\\' && i + 1 < s.length()) { b.append(ch).append(s.charAt(++i)); continue; } if (ch == ':') { out.add(b.toString()); b.setLength(0); } else b.append(ch); }
        out.add(b.toString());
        return out.toArray(new String[0]);
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
    private int fitSize(Address a, int want) {
        int size = want;
        while (size > 1) {
            boolean ok = true;
            for (int k = 1; k < size; k++) {
                Address nx = a.add(k);
                if (getSymbolAt(nx) != null || ls.getInstructionContaining(nx) != null) { ok = false; break; }
            }
            if (ok) return size;
            size >>= 1;
        }
        return 1;
    }
}
