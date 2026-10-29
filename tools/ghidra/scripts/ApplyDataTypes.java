// Replace the undefined1/2/4 data (from ClassifyData) with concrete C types:
//   - doc type from build/cmg_types.tsv (addr<TAB>u8|s8|u16|s16|u32|s32|bool|char)
//     when available (high precision: name-cell parentheticals / type columns),
//   - else a width-based unsigned type (uint8_t/uint16_t/uint32_t) from the
//     existing item size or the ld/st access width.
// Types are stdint-style typedefs (uint8_t = byte, int16_t = short, ...) so the
// listing/decompiler read like C. Size is capped so it never swallows a neighbour
// (any symbol -- DAT_/named -- or code).
// Usage: -postScript ApplyDataTypes.java <path cmg_types.tsv>
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressIterator;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.symbol.*;
import java.io.*;
import java.util.*;

public class ApplyDataTypes extends GhidraScript {
    private DataType u8, s8, u16, s16, u32, s32, boolT, charT;

    @Override
    public void run() throws Exception {
        Listing listing = currentProgram.getListing();
        Memory mem = currentProgram.getMemory();
        ReferenceManager rm = currentProgram.getReferenceManager();
        SymbolTable st = currentProgram.getSymbolTable();
        DataTypeManager dtm = currentProgram.getDataTypeManager();

        u8  = typedef(dtm, "uint8_t",  ByteDataType.dataType);
        s8  = typedef(dtm, "int8_t",   SignedByteDataType.dataType);
        u16 = typedef(dtm, "uint16_t", UnsignedShortDataType.dataType);
        s16 = typedef(dtm, "int16_t",  ShortDataType.dataType);
        u32 = typedef(dtm, "uint32_t", UnsignedIntegerDataType.dataType);
        s32 = typedef(dtm, "int32_t",  IntegerDataType.dataType);
        boolT = BooleanDataType.dataType;
        charT = CharDataType.dataType;

        // load doc types
        HashMap<Long,String> doc = new HashMap<>();
        String path = getScriptArgs().length > 0 ? getScriptArgs()[0] : "build/cmg_types.tsv";
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String ln;
            while ((ln = br.readLine()) != null) {
                String[] p = ln.trim().split("\t");
                if (p.length == 2) doc.put(Long.parseLong(p[0], 16), p[1]);
            }
        }

        // candidates = reference destinations + user symbols in data space
        LinkedHashSet<Address> cand = new LinkedHashSet<>();
        AddressIterator dests = rm.getReferenceDestinationIterator(mem, true);
        while (dests.hasNext()) cand.add(dests.next());
        for (Symbol s : st.getAllSymbols(false))
            if (s.getSource() == SourceType.USER_DEFINED && mem.contains(s.getAddress())) cand.add(s.getAddress());

        int docTyped = 0, widthTyped = 0, failed = 0;
        int skippedTyped = 0;
        for (Address a : cand) {
            if (!mem.contains(a) || listing.getInstructionContaining(a) != null) continue;
            // only (re)type UNDEFINED data or our own stdint typedefs -- never clobber
            // a pointer / array / struct / string / other meaningful type.
            Data cur = listing.getDefinedDataAt(a);
            if (cur != null) {
                DataType cdt = cur.getDataType();
                boolean ours = (cdt instanceof TypeDef) || (cdt instanceof Undefined)
                        || cdt instanceof DefaultDataType
                        // also re-type the plain unsigned scalars auto-analysis applies
                        // (byte/word/dword/qword) -- NOT char/pointer/array/struct/string/float
                        || cdt instanceof ByteDataType || cdt instanceof WordDataType
                        || cdt instanceof DWordDataType || cdt instanceof QWordDataType;
                if (!ours) { skippedTyped++; continue; }
            }
            String tc = doc.get(a.getOffset());
            int size; DataType dt;
            if (tc != null) { dt = dtFor(tc); size = sizeFor(tc); }
            else {
                Data ex = listing.getDefinedDataAt(a);
                size = (ex != null && ex.isDefined()) ? ex.getLength() : accessSize(rm, a);
                dt = widthType(size);
            }
            int fit = fitSize(listing, a, size);
            if (fit < size) { size = fit; dt = (tc != null ? signedWidth(tc, fit) : widthType(fit)); }
            try {
                clearListing(a, a.add(size - 1));
                createData(a, dt);
                if (tc != null) docTyped++; else widthTyped++;
            } catch (Exception e) { failed++; }
        }
        println("ApplyDataTypes: docTyped=" + docTyped + " widthTyped=" + widthTyped
                + " skippedTyped=" + skippedTyped + " failed=" + failed);
    }

    private DataType typedef(DataTypeManager dtm, String name, DataType base) {
        DataType ex = dtm.getDataType(new DataTypePath(CategoryPath.ROOT, name));
        if (ex != null) return ex;
        return dtm.addDataType(new TypedefDataType(CategoryPath.ROOT, name, base), DataTypeConflictHandler.DEFAULT_HANDLER);
    }
    private DataType dtFor(String tc) {
        switch (tc) { case "s8": return s8; case "u16": return u16; case "s16": return s16;
            case "u32": return u32; case "s32": return s32; case "bool": return boolT; case "char": return charT;
            default: return u8; }
    }
    private int sizeFor(String tc) {
        switch (tc) { case "u16": case "s16": return 2; case "u32": case "s32": return 4; default: return 1; }
    }
    private DataType widthType(int sz) { return sz == 4 ? u32 : sz == 2 ? u16 : u8; }
    private DataType signedWidth(String tc, int sz) {        // keep signedness when a doc type is shrunk to fit
        boolean signed = tc.startsWith("s");
        return sz == 4 ? (signed ? s32 : u32) : sz == 2 ? (signed ? s16 : u16) : (signed ? s8 : u8);
    }
    private int accessSize(ReferenceManager rm, Address a) {
        int best = 0;
        for (Reference r : rm.getReferencesTo(a)) {
            Instruction ins = getInstructionAt(r.getFromAddress());
            if (ins == null) continue;
            String m = ins.getMnemonicString();
            int s = (m.startsWith("ld.b") || m.startsWith("st.b")) ? 1
                  : (m.startsWith("ld.h") || m.startsWith("st.h")) ? 2
                  : (m.startsWith("ld.w") || m.startsWith("st.w")) ? 4 : 0;
            if (s > best) best = s;
        }
        return best == 0 ? 1 : best;
    }
    // largest size <= want that doesn't cross a neighbouring symbol or code
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
