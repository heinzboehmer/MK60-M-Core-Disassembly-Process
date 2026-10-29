// Recover a function whose body is mis-typed as DATA (e.g. uint32_t from ClassifyData,
// because it was referenced-but-undisassembled): incrementally clear the data item at the
// current PC and disassemble, following fall-through until a terminator, then functionize +
// name. Args: realEntryHex=name. Verify first (cmg5461 / a prologue or clean decode) that it
// really is code. Does NOT remove neighbouring functions.
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.SourceType;

public class ForceFn extends GhidraScript {
    @Override
    public void run() throws Exception {
        for (String arg : getScriptArgs()) {
            String[] kv = arg.split("=", 2);
            Address entry = toAddr(Long.parseLong(kv[0], 16));
            String name = kv.length > 1 ? kv[1] : null;
            Address pc = entry;
            for (int i = 0; i < 400; i++) {
                Data d = getDataAt(pc);
                if (d != null && d.isDefined()) clearListing(pc, d.getMaxAddress());
                if (getInstructionAt(pc) == null) { try { disassemble(pc); } catch (Exception e) {} }
                Instruction ins = getInstructionAt(pc);
                if (ins == null) break;                       // not decodable -> stop
                Address ft = ins.getFallThrough();
                if (ft == null) break;                        // terminator -> straight-line body ends
                pc = ft;
            }
            Function f = getFunctionAt(entry);
            if (f == null) f = createFunction(entry, name);
            if (f == null) { println("  " + kv[0] + ": createFunction FAILED (instr=" + getInstructionAt(entry) + ")"); continue; }
            if (name != null) f.setName(name, SourceType.USER_DEFINED);
            println("  " + kv[0] + " -> " + f.getName() + "  body " + f.getBody().getMinAddress() + ".." + f.getBody().getMaxAddress());
        }
    }
}
