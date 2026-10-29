// Read-only: count what the listing holds in an address range — functions (with their body
// sizes when there are few), instructions, instruction bytes, undefined bytes. Used by
// mcore_verify.sh for the region BELOW codeLO (VBR table + calibration directory + OSEK init
// stubs), which the recovery pipeline leaves alone, and for the data section (want
// instrBytes=0 there). Args: LO HI (hex, no 0x) [LIST_MAX (default 8)].
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import java.util.ArrayList;

public class RegionStats extends GhidraScript {
    @Override
    public void run() throws Exception {
        String[] a = getScriptArgs();
        if (a.length < 2) { println("RegionStats: args LO HI (hex)"); return; }
        long lo = Long.parseLong(a[0], 16), hi = Long.parseLong(a[1], 16);
        int listMax = a.length > 2 ? Integer.parseInt(a[2]) : 8;
        AddressSpace sp = currentProgram.getAddressFactory().getDefaultAddressSpace();
        Address L = sp.getAddress(lo), H = sp.getAddress(hi - 1);
        AddressSet set = new AddressSet(L, H);
        Listing ls = currentProgram.getListing();

        int ninstr = 0; long ibytes = 0;
        for (Instruction i : ls.getInstructions(set, true)) { ninstr++; ibytes += i.getLength(); }

        ArrayList<String> fns = new ArrayList<>();
        for (Function f : currentProgram.getFunctionManager().getFunctions(L, true)) {
            if (f.getEntryPoint().compareTo(H) > 0) break;
            fns.add(f.getName() + "(" + f.getBody().getNumAddresses() + "B)");
        }
        long undef = ls.getUndefinedRanges(set, false, monitor).getNumAddresses();

        println(String.format("RegionStats %06x-%06x: functions=%d instructions=%d instrBytes=%d undefBytes=%d",
                lo, hi, fns.size(), ninstr, ibytes, undef));
        if (!fns.isEmpty() && fns.size() <= listMax) println("  " + String.join(" ", fns));
    }
}
