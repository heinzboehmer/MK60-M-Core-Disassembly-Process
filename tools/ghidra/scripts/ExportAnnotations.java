// Read-only: dump every hand-made annotation of a NAMED reference project so it can be
// replayed by address onto another project of the SAME image (ApplyAnnotations checks the
// image hash and refuses otherwise — addresses mean nothing across variants; use the
// signature port for those). Runs on a snapshot, so the reference GUI may stay open.
//
// Output (TSV, one record per line; text has \t and \n escaped; types are written as a
// type string: a name, with "[n]" for arrays (outer count last) and " *" for pointers):
//   H  <fnv64 of all initialized bytes>
//   K  <name>  <start>  <size>  I|U  <rwx>        memory block (uninitialized ones are recreated;
//                                                  initialized ones only checked)
//   E  <name>  <len>  <v=name;v=name;…>           enum definition
//   T  <name>  <len>  <off:name:type:len;…>       structure definition (non-packed, fixed size)
//   N  <addr>                                     function entry (any name)
//   F  <addr>  <name>                             user-named function entry
//   L  <addr>  <name>  P|S                        user label, primary or secondary
//   C  <addr>  EOL|PRE|POST|PLATE|REPEATABLE  <text>
//   B  <addr>  <type>  <category>  <text>         non-analysis bookmark
//   D  <addr>  u8|s8|u16|s16|u32|s32|bool|char    concrete scalar type on data
//   X  <addr>  <type string>                      composite / array / enum / typed-pointer data
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
        DataTypeManager dtm = currentProgram.getDataTypeManager();
        int blocks = 0, enums = 0, structs = 0, entries = 0, fn = 0, lab = 0, com = 0, bm = 0, typ = 0, comp = 0;
        try (PrintWriter w = new PrintWriter(new FileWriter(out))) {
            w.println("H\t" + String.format("%016x", imageHash(mem)));

            for (MemoryBlock b : mem.getBlocks()) {
                if (b.getName().equals("ram")) continue;
                w.println("K\t" + esc(b.getName()) + "\t" + hex(b.getStart()) + "\t" + b.getSize() + "\t"
                        + (b.isInitialized() ? "I" : "U") + "\t" + (b.isRead() ? "r" : "-") + (b.isWrite() ? "w" : "-")
                        + (b.isExecute() ? "x" : "-") + (b.isVolatile() ? "v" : "-"));
                blocks++;
            }

            // Type definitions: enums, then structures (fields may reference other structs;
            // ApplyAnnotations creates all shells first, then fills).
            ArrayList<DataType> all = new ArrayList<>();
            Iterator<DataType> it = dtm.getAllDataTypes();
            while (it.hasNext()) all.add(it.next());
            for (DataType d : all) {
                if (!(d instanceof ghidra.program.model.data.Enum)) continue;
                ghidra.program.model.data.Enum e = (ghidra.program.model.data.Enum) d;
                StringBuilder sb = new StringBuilder();
                for (String n : e.getNames()) sb.append(e.getValue(n)).append('=').append(esc(n)).append(';');
                w.println("E\t" + esc(e.getName()) + "\t" + e.getLength() + "\t" + sb); enums++;
            }
            for (DataType d : all) {
                if (!(d instanceof Structure)) continue;
                Structure s = (Structure) d;
                StringBuilder sb = new StringBuilder();
                for (DataTypeComponent c : s.getDefinedComponents()) {
                    sb.append(c.getOffset()).append(':').append(esc(c.getFieldName() == null ? "" : c.getFieldName()))
                      .append(':').append(esc(typeString(c.getDataType()))).append(':').append(c.getLength()).append(';');
                }
                w.println("T\t" + esc(s.getName()) + "\t" + s.getLength() + "\t" + sb); structs++;
            }

            for (Function f : currentProgram.getFunctionManager().getFunctions(true)) {
                w.println("N\t" + hex(f.getEntryPoint())); entries++;
            }

            for (Symbol s : st.getAllSymbols(false)) {
                if (s.getSource() == SourceType.DEFAULT) continue;
                Address a = s.getAddress();
                if (!a.isMemoryAddress()) continue;
                String name = s.getName();
                if (generic(name)) continue;
                if (s.getSymbolType() == SymbolType.FUNCTION) { w.println("F\t" + hex(a) + "\t" + name); fn++; }
                else if (s.getSymbolType() == SymbolType.LABEL && s.getSource() == SourceType.USER_DEFINED) {
                    w.println("L\t" + hex(a) + "\t" + name + "\t" + (s.isPrimary() ? "P" : "S")); lab++;
                }
            }

            String[] cn = {"EOL", "PRE", "POST", "PLATE", "REPEATABLE"};
            CommentType[] ct = {CommentType.EOL, CommentType.PRE, CommentType.POST, CommentType.PLATE, CommentType.REPEATABLE};
            for (int t = 0; t < ct.length; t++) {
                AddressIterator ai = ls.getCommentAddressIterator(ct[t], mem, true);
                while (ai.hasNext()) {
                    Address a = ai.next();
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
                DataType dt = d.getDataType();
                String tc = typeCode(dt);
                if (tc != null) { w.println("D\t" + hex(d.getAddress()) + "\t" + tc); typ++; continue; }
                if (dt instanceof Undefined || dt instanceof DefaultDataType) continue;
                if (dt instanceof Pointer && ((Pointer) dt).getDataType() == null) continue;   // plain `pointer`: ours already
                if (dt instanceof Pointer && ((Pointer) dt).getDataType() instanceof Undefined) continue;
                w.println("X\t" + hex(d.getAddress()) + "\t" + esc(typeString(dt))); comp++;
            }
        }
        println("ExportAnnotations: blocks=" + blocks + " enums=" + enums + " structs=" + structs + " entries=" + entries
                + " functions=" + fn + " labels=" + lab + " comments=" + com + " bookmarks=" + bm
                + " scalarTypes=" + typ + " compositeTypes=" + comp + " -> " + out);
    }

    static boolean generic(String n) {
        return n.startsWith("FUN_") || n.startsWith("LAB_") || n.startsWith("DAT_") || n.startsWith("SUB_")
                || n.startsWith("thunk_") || n.startsWith("tbl_");
    }

    // name, with [n] for arrays (outer count last, as Ghidra names them) and " *" for pointers
    static String typeString(DataType dt) {
        if (dt instanceof Pointer) {
            DataType b = ((Pointer) dt).getDataType();
            return (b == null ? "pointer" : typeString(b) + " *");
        }
        if (dt instanceof Array) {
            Array ar = (Array) dt;
            return typeString(ar.getDataType()) + "[" + ar.getNumElements() + "]";
        }
        return dt.getName();
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
    static String esc(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace(";", "\\;").replace(":", "\\:"); }
}
