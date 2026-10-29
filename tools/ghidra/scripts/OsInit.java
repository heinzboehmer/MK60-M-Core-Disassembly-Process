// Disassemble + functionize specific OS-init entries (via DisassembleCommand, which follows
// flow robustly) in the sub-0x42000 region the main pipeline (LO=0x42000) skipped. Skips any
// cmg5461 D(data) address. Args: tsv then hex addresses.
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.app.cmd.disassemble.DisassembleCommand;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Data;
import java.io.*;
import java.util.*;

public class OsInit extends GhidraScript {
    @Override
    public void run() throws Exception {
        String[] a = getScriptArgs();
        HashSet<Long> dsym = new HashSet<>();
        BufferedReader br = new BufferedReader(new FileReader(a[0]));
        String ln;
        while ((ln = br.readLine()) != null) {
            String[] c = ln.split("\t");
            if (c.length > 1 && c[1].equals("D")) { try { dsym.add(Long.parseLong(c[0].trim(), 16)); } catch (Exception e) {} }
        }
        br.close();
        int dis = 0, fn = 0;
        for (int i = 1; i < a.length; i++) {
            long o = Long.parseLong(a[i], 16);
            if (dsym.contains(o)) { println("  skip D " + a[i]); continue; }
            Address ad = toAddr(o);
            if (getInstructionAt(ad) == null) {
                Data d = getDataAt(ad);
                if (d != null && d.isDefined()) clearListing(ad, d.getMaxAddress());
                DisassembleCommand cmd = new DisassembleCommand(ad, null, true);
                cmd.applyTo(currentProgram, monitor);
            }
            if (getInstructionAt(ad) != null) {
                dis++;
                if (getFunctionAt(ad) == null) { try { if (createFunction(ad, null) != null) fn++; } catch (Exception e) {} }
            }
            println("  " + a[i] + ": instr=" + (getInstructionAt(ad) != null));
        }
        println("OsInit: disassembled=" + dis + " newFns=" + fn);
    }
}
