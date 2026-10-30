// Read-only: emit a relocation-invariant signature per function for cross-image symbol porting,
// plus the function's outgoing EDGES so port_symbols.py can propagate names from a matched
// function to its neighbours (call-site and table-slot propagation).
// Signature = FNV-1a hash of the masked instruction halfword sequence (mask lrw/jmpi/jsri disp8 and
// branch disp, which differ between variants because pools/functions sit at different addresses;
// everything else — opcodes, regs, immediates, ld/st offsets — is identical for the same source).
// Edges, in body order, ';'-separated:
//   c:<addr>              call target (bsr, or jsri resolved through its pool slot)
//   j:<addr>              jump to ANOTHER function's entry (a tail call / shared tail)
//   t:<base>=<a0>,<a1>,…  a pointer table the body loads through its pool: consecutive words
//                         that all point at an instruction (function entries or shared-tail
//                         labels; stops at the first that does not)
// Line: entry \t nInstr \t sigHex \t name \t edges. After the functions, one
//   L \t <base> \t <label>   per pointer table seen in an edge (its primary symbol), so a
//   named table (`ccp_command_table`) can be ported as a label onto the matched target table.
// Args: out-file.
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.symbol.*;
import java.io.*;
import java.util.*;

public class ExportSigs extends GhidraScript {
    private Memory mem; private Listing lst; private FunctionManager fm;
    private final TreeMap<Long,String> tables = new TreeMap<>();

    @Override public void run() throws Exception {
        String out = getScriptArgs()[0];
        mem = currentProgram.getMemory(); lst = currentProgram.getListing();
        fm = currentProgram.getFunctionManager();
        BufferedWriter w = new BufferedWriter(new FileWriter(out), 1 << 20);
        int edges = 0;
        for (Function f : fm.getFunctions(true)) {
            long h = 0xcbf29ce484222325L;            // FNV-1a 64
            int n = 0;
            StringBuilder e = new StringBuilder();
            InstructionIterator it = lst.getInstructions(f.getBody(), true);
            while (it.hasNext()) {
                Instruction in = it.next();
                int len = in.getLength();
                for (int off = 0; off + 2 <= len; off += 2) {
                    int hw;
                    try { hw = mem.getShort(in.getAddress().add(off)) & 0xFFFF; } catch (Exception ex) { hw = 0; }
                    int top = hw >> 12;
                    int masked;
                    if (top == 0x7) masked = hw & 0xFF00;          // lrw/jmpi/jsri: drop disp8 (pool slot)
                    else if (top == 0xE || top == 0xF) masked = hw & 0xF800; // bt/bf/br/bsr: drop disp
                    else masked = hw;
                    h = (h ^ (masked & 0xFF)) * 0x100000001b3L;
                    h = (h ^ ((masked >> 8) & 0xFF)) * 0x100000001b3L;
                }
                n++;
                for (Reference r : in.getReferencesFrom()) {
                    RefType rt = r.getReferenceType(); Address t = r.getToAddress();
                    String edge = null;
                    if (rt.isCall()) edge = "c:" + hex(t);
                    else if (rt.isJump()) { if (!f.getBody().contains(t) && fm.getFunctionAt(t) != null) edge = "j:" + hex(t); }
                    if (edge != null) { if (e.length() > 0) e.append(';'); e.append(edge); edges++; }
                }
                // lrw: the recovery types the pool slot but records no reference from the
                // instruction, so take the slot address from the operand itself.
                if (in.getMnemonicString().equals("lrw")) {
                    Address slot = null;
                    for (int op = 0; op < in.getNumOperands() && slot == null; op++)
                        for (Object o : in.getOpObjects(op)) {
                            if (o instanceof Address) slot = (Address) o;
                            else if (o instanceof ghidra.program.model.scalar.Scalar) slot = toAddr(((ghidra.program.model.scalar.Scalar) o).getUnsignedValue());
                        }
                    Data d = slot == null ? null : lst.getDataAt(slot);
                    Address tb = null;
                    if (d != null && d.isPointer() && d.getValue() instanceof Address) tb = (Address) d.getValue();
                    else if (slot != null) { try { long v = mem.getInt(slot) & 0xffffffffL; if (v >= 0x40000L && v < 0x100000L) tb = toAddr(v); } catch (Exception ex) {} }
                    String tbl = tb == null ? null : tableAt(tb);
                    if (tbl != null) {
                        if (e.length() > 0) e.append(';');
                        e.append("t:").append(hex(tb)).append('=').append(tbl); edges++;
                        Symbol ps = currentProgram.getSymbolTable().getPrimarySymbol(tb);
                        tables.put(tb.getOffset(), ps == null ? "" : ps.getName());
                    }
                }
            }
            w.write(String.format("%06x\t%d\t%016x\t%s\t%s%n", f.getEntryPoint().getOffset(), n, h, f.getName(), e));
        }
        for (Map.Entry<Long,String> t : tables.entrySet())
            w.write(String.format("L\t%06x\t%s%n", t.getKey(), t.getValue()));
        w.close();
        println("ExportSigs: wrote " + out + " (edges=" + edges + " tables=" + tables.size() + ")");
    }

    // A pointer table = at least 2 consecutive words that all point at instructions, outside any body.
    private String tableAt(Address base) {
        if (fm.getFunctionContaining(base) != null) return null;
        StringBuilder sb = new StringBuilder(); int k = 0;
        try {
            for (Address p = base; k < 512; p = p.add(4), k++) {
                long v = mem.getInt(p) & 0xffffffffL;
                if (lst.getInstructionAt(toAddr(v)) == null) break;   // entries may be labels (shared tails), not only function entries
                if (k > 0) sb.append(',');
                sb.append(String.format("%06x", v));
            }
        } catch (Exception ex) { /* ran off the block */ }
        return k >= 2 ? sb.toString() : null;
    }

    private static String hex(Address a) { return String.format("%06x", a.getOffset()); }
}
