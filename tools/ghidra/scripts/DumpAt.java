// Dump disassembly at given addresses. Headless usage:
//   tools/ghidra/dump.sh <proj_dir> <name> <cpu_hex_path> <addr[:count]> ...
// Each arg is "ADDR" or "ADDR:COUNT" (hex, default 40 instructions).
// Walks addresses sequentially: disassembles on demand, and prints undecodable
// halfwords as ".hword" instead of skipping ahead. Run on a snapshot (dump.sh).
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.symbol.*;

public class DumpAt extends GhidraScript {
    @Override
    public void run() throws Exception {
        Memory mem = currentProgram.getMemory();
        for (String arg : getScriptArgs()) {
            String[] p = arg.split(":");
            Address a = toAddr(Long.parseLong(p[0].replace("0x", ""), 16));
            int n = p.length > 1 ? Integer.parseInt(p[1]) : 40;
            Function f = getFunctionContaining(a);
            println(String.format("=== %s  (function: %s) ===", a,
                    f == null ? "none" : f.getName() + "@" + f.getEntryPoint()));
            for (Reference r : getReferencesTo(a)) {
                println("    xref from " + r.getFromAddress() + " " + r.getReferenceType());
            }
            for (int i = 0; i < n; i++) {
                if (!mem.contains(a)) { println("  " + a + " <unmapped>"); break; }
                String lbl = "";
                Symbol s = getSymbolAt(a);
                if (s != null) lbl = s.getName() + ":";
                if (getDataAt(a) == null && getInstructionAt(a) == null) {
                    disassemble(a);
                }
                Instruction ins = getInstructionAt(a);
                if (ins == null) {
                    int hw = ((mem.getByte(a) & 0xFF) << 8) | (mem.getByte(a.add(1)) & 0xFF);
                    println(String.format("  %s %-10s %04X     .hword", a, lbl, hw));
                    a = a.add(2);
                    continue;
                }
                StringBuilder hex = new StringBuilder();
                for (byte b : ins.getBytes()) hex.append(String.format("%02X", b & 0xFF));
                println(String.format("  %s %-10s %-8s %s", a, lbl, hex, ins));
                a = a.add(ins.getLength());
            }
        }
    }
}
