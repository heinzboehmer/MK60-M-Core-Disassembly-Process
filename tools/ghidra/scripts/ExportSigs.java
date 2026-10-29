// Read-only: emit a relocation-invariant signature per function for cross-image symbol porting.
// Signature = FNV-1a hash of the masked instruction halfword sequence (mask lrw/jmpi/jsri disp8 and
// branch disp, which differ between variants because pools/functions sit at different addresses;
// everything else — opcodes, regs, immediates, ld/st offsets — is identical for the same source).
// Line: entry \t nInstr \t sigHex \t name. Args: out-file.
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.Memory;
import java.io.*;

public class ExportSigs extends GhidraScript {
    @Override public void run() throws Exception {
        String out = getScriptArgs()[0];
        Memory mem = currentProgram.getMemory();
        Listing lst = currentProgram.getListing();
        BufferedWriter w = new BufferedWriter(new FileWriter(out), 1 << 20);
        for (Function f : currentProgram.getFunctionManager().getFunctions(true)) {
            long h = 0xcbf29ce484222325L;            // FNV-1a 64
            int n = 0;
            InstructionIterator it = lst.getInstructions(f.getBody(), true);
            while (it.hasNext()) {
                Instruction in = it.next();
                int len = in.getLength();
                for (int off = 0; off + 2 <= len; off += 2) {
                    int hw;
                    try { hw = mem.getShort(in.getAddress().add(off)) & 0xFFFF; } catch (Exception e) { hw = 0; }
                    int top = hw >> 12;
                    int masked;
                    if (top == 0x7) masked = hw & 0xFF00;          // lrw/jmpi/jsri: drop disp8 (pool slot)
                    else if (top == 0xE || top == 0xF) masked = hw & 0xF800; // bt/bf/br/bsr: drop disp
                    else masked = hw;
                    h = (h ^ (masked & 0xFF)) * 0x100000001b3L;
                    h = (h ^ ((masked >> 8) & 0xFF)) * 0x100000001b3L;
                }
                n++;
            }
            String nm = f.getName();
            w.write(String.format("%06x\t%d\t%016x\t%s%n", f.getEntryPoint().getOffset(), n, h, nm));
        }
        w.close();
        println("ExportSigs: wrote " + out);
    }
}
