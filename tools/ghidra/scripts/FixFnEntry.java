// Correct a function whose entry was seeded a few bytes late: re-disassemble from the true
// entry, drop the mis-placed function that sits inside its body, recreate the function at the
// true entry, and name it. Args: realEntryHex=name [...]. Read the cmg5461 symbol/arch map
// for the true entry; verify it is a prologue before using this.
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.symbol.SourceType;

public class FixFnEntry extends GhidraScript {
    @Override
    public void run() throws Exception {
        for (String arg : getScriptArgs()) {
            String[] kv = arg.split("=", 2);
            Address real = toAddr(Long.parseLong(kv[0], 16));
            String name = kv.length > 1 ? kv[1] : null;
            if (getInstructionAt(real) == null) { try { disassemble(real); } catch (Exception e) {} }
            // remove any function whose entry lies within [real, real+32) but isn't `real`
            for (long o = real.getOffset(); o < real.getOffset() + 32; o += 2) {
                Function f = getFunctionAt(toAddr(o));
                if (f != null && o != real.getOffset()) { removeFunction(f); println("  removed mis-placed fn @" + toAddr(o)); }
            }
            Function f = getFunctionAt(real);
            if (f == null) f = createFunction(real, name);
            if (f == null) { println("  " + kv[0] + ": createFunction FAILED"); continue; }
            if (name != null) f.setName(name, SourceType.USER_DEFINED);
            println("  " + kv[0] + " -> " + f.getName() + " @" + f.getEntryPoint()
                    + "  body=" + f.getBody().getMinAddress() + ".." + f.getBody().getMaxAddress());
        }
    }
}
