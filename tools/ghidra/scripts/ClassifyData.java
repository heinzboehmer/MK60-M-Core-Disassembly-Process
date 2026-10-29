// Tidy the data side of the listing:
//  Q1: revert pointer/addr data whose target is NOT in any mapped block (a data
//      word misread as a pointer) back to undefined4 -- kills the bogus XREFs.
//  Q2: give every referenced-but-undefined address (RAM vars, MMIO regs, flash
//      data) a sized undefined type from the access width of its referring
//      ld/st instruction (ld.b->1, ld.h->2, ld.w->4), capped so it never swallows
//      a neighbouring label. Turns raw "?? ??" bytes into one typed item each.
//  Q3: delete the pspec's VBR=0 default vector symbols (reset/trace/hwaccel/trapN
//      at 0x0-0x4C) -- this firmware uses VBR=0x40000 (real vectors are vec_*).
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressIterator;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.symbol.*;
import java.util.*;

public class ClassifyData extends GhidraScript {
    @Override
    public void run() throws Exception {
        Listing listing = currentProgram.getListing();
        Memory mem = currentProgram.getMemory();
        ReferenceManager rm = currentProgram.getReferenceManager();
        SymbolTable st = currentProgram.getSymbolTable();

        // ---- Q1: revert out-of-range pointers ----
        int reverted = 0;
        ArrayList<Address> badptr = new ArrayList<>();
        DataIterator di = listing.getDefinedData(true);
        while (di.hasNext()) {
            Data d = di.next();
            if (d.getDataType() instanceof Pointer) {
                Object v = d.getValue();
                if (v instanceof Address && !mem.contains((Address) v)) badptr.add(d.getMinAddress());
            }
        }
        for (Address a : badptr) {
            try { clearListing(a, a.add(3)); createData(a, Undefined4DataType.dataType); reverted++; }
            catch (Exception e) {}
        }

        // ---- Q2: size-classify referenced undefined data ----
        // gather candidate addresses: reference destinations + user symbols in data space
        LinkedHashSet<Address> cand = new LinkedHashSet<>();
        AddressIterator dests = rm.getReferenceDestinationIterator(mem, true);
        while (dests.hasNext()) cand.add(dests.next());
        for (Symbol s : st.getAllSymbols(false)) {
            if (s.getSource() == SourceType.USER_DEFINED && mem.contains(s.getAddress())) cand.add(s.getAddress());
        }

        int typed = 0, failed = 0;
        for (Address a : cand) {
            if (!mem.contains(a)) continue;
            if (listing.getDefinedDataAt(a) != null) continue;          // already data
            if (listing.getInstructionContaining(a) != null) continue;  // it's code
            int acc = accessSize(rm, a);
            int size = fitSize(listing, a, acc);
            try {
                DataType dt = size == 4 ? Undefined4DataType.dataType
                            : size == 2 ? Undefined2DataType.dataType
                            : Undefined1DataType.dataType;
                createData(a, dt);
                typed++;
            } catch (Exception e) { failed++; }
        }

        // ---- Q3: delete VBR=0 default vector symbols at 0x0-0x4C ----
        int dropped = 0;
        for (long off = 0x0; off <= 0x4C; off += 4) {
            for (Symbol s : st.getSymbols(toAddr(off))) {
                if (s.getSource() != SourceType.USER_DEFINED) { s.delete(); dropped++; }
            }
        }

        println("ClassifyData: Q1 revertedPtrs=" + reverted
                + " | Q2 typed=" + typed + " failed=" + failed
                + " | Q3 droppedDefaultVecSyms=" + dropped);
    }

    // max access width (1/2/4) over the ld/st instructions that reference `a`; 1 if unknown.
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

    // largest size <= acc that fits before the next symbol/defined unit.
    private int fitSize(Listing listing, Address a, int acc) {
        int size = acc;
        while (size > 1) {
            boolean ok = true;
            for (int k = 1; k < size; k++) {
                Address nx = a.add(k);
                if (getSymbolAt(nx) != null || listing.getDefinedDataAt(nx) != null
                        || listing.getInstructionContaining(nx) != null) { ok = false; break; }
            }
            if (ok) return size;
            size >>= 1;
        }
        return 1;
    }
}
