// After FixTruncated disassembles mid-function code that was mis-typed as data, the owning
// function's BODY does not auto-extend and the continuation may be a spurious separate
// function. This re-traces bodies: (1) remove unnamed functions whose entry is a fall-through
// target (= a mid-flow continuation, not a real entry, e.g. SeedCode's 0x73df4); (2) for each
// function whose last instruction falls through to code outside its body, remove+recreate it
// at its entry so createFunction re-traces the full body, restoring the name. Protects named
// (cmg5461) functions from removal in step 1.
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.SourceType;
import java.util.*;

public class RetraceBodies extends GhidraScript {
    private long LO = 0x42000L, HI = 0xD7000L;  // override via args: LO HI (hex)

    @Override
    public void run() throws Exception {
        String[] _bnd = getScriptArgs(); if (_bnd.length>=2) { LO=Long.parseLong(_bnd[0],16); HI=Long.parseLong(_bnd[1],16); }
        FunctionManager fm = currentProgram.getFunctionManager();

        // Step 1: remove spurious continuation functions (unnamed, entry is a fall-through target)
        ArrayList<Address> spurious = new ArrayList<>();
        for (Function f : fm.getFunctions(true)) {
            Address e = f.getEntryPoint();
            if (e.getOffset() < LO || e.getOffset() >= HI) continue;
            if (!f.getName().startsWith("FUN_")) continue;
            Instruction prev = getInstructionBefore(e);
            if (prev != null && e.equals(prev.getFallThrough())) spurious.add(e);
        }
        int removed = 0;
        for (Address e : spurious) { Function f = getFunctionAt(e); if (f != null) { removeFunction(f); removed++; } }

        // Step 2: collect truncated functions (last instr falls through to code outside the body)
        ArrayList<Object[]> trunc = new ArrayList<>();   // {entryOffset, name}
        for (Function f : fm.getFunctions(true)) {
            Address e = f.getEntryPoint();
            if (e.getOffset() < LO || e.getOffset() >= HI) continue;
            Instruction last = getInstructionContaining(f.getBody().getMaxAddress());
            if (last == null) continue;
            Address ft = last.getFallThrough();
            if (ft == null) continue;
            if (getInstructionAt(ft) == null) continue;      // continuation not code
            if (f.getBody().contains(ft)) continue;          // already contained
            trunc.add(new Object[]{e.getOffset(), f.getName()});
        }
        int retraced = 0, failed = 0;
        for (Object[] t : trunc) {
            Address e = toAddr((Long) t[0]);
            String name = (String) t[1];
            Function f = getFunctionAt(e);
            if (f != null) removeFunction(f);
            Function nf = null;
            try { nf = createFunction(e, null); } catch (Exception ex) {}
            if (nf == null) { failed++; try { createFunction(e, name); } catch (Exception ex) {} continue; }
            if (!name.startsWith("FUN_")) try { nf.setName(name, SourceType.USER_DEFINED); } catch (Exception ex) {}
            retraced++;
        }
        println("RetraceBodies: removedSpurious=" + removed + " truncatedFound=" + trunc.size()
                + " retraced=" + retraced + " failed=" + failed);
        // Name the few that were retraced so a function that re-appears here on EVERY pass
        // can be identified: that is the headless 2-byte-body quirk (createFunction leaves a
        // 2-byte body whose fall-through is always "outside"), harmless — see PROCESS.md.
        if (!trunc.isEmpty() && trunc.size() <= 8) {
            StringBuilder sb = new StringBuilder("RetraceBodies: retraced entries:");
            for (Object[] t : trunc) sb.append(' ').append(Long.toHexString((Long) t[0])).append('=').append((String) t[1]);
            println(sb.toString());
        }
    }
}
