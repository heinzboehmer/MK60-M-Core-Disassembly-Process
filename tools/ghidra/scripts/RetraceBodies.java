// After FixTruncated disassembles mid-function code that was mis-typed as data, the owning
// function's BODY does not auto-extend and the continuation may be a spurious separate
// function. This re-traces bodies: (1) remove unnamed functions whose entry is a fall-through
// target (= a mid-flow continuation, not a real entry, e.g. SeedCode's 0x73df4); (2) for each
// function whose body is TRUNCATED, remove+recreate it at its entry so createFunction re-traces
// the full body with today's references, restoring the name. A body is truncated when
//   - its last instruction falls through to code outside the body that no function owns
//     (a fall-through into the middle of ANOTHER function is a shared tail — bodies can't
//     overlap, so re-tracing can't change it and it is not counted), or
//   - any instruction in it has a JUMP flow (bt/bf/br/jmpi/jmp — M-CORE branches are modelled
//     as computed jumps, so the body was traced before their references existed) to code
//     outside the body that is not another function's entry or body.
// Without the second rule ~20% of the code region ends up as instructions owned by no
// function (bodies stop at the first branch). Protects named (cmg5461) functions from removal
// in step 1; skips 2-byte bodies in step 2 (the headless createFunction quirk — retracing
// those backfires, see PROCESS.md).
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.SourceType;
import java.util.*;

public class RetraceBodies extends GhidraScript {
    private long LO = 0x42000L, HI = 0xD7000L;  // override via args: LO HI (hex)

    @Override
    public void run() throws Exception {
        String[] _bnd = getScriptArgs(); if (_bnd.length>=2) { LO=Long.parseLong(_bnd[0],16); HI=Long.parseLong(_bnd[1],16); }
        FunctionManager fm = currentProgram.getFunctionManager();

        // Step 1: remove spurious continuation functions (unnamed, entry is a fall-through target).
        // Keep a fall-through-reached entry that is ALSO a real entry: referenced from data (a
        // function pointer / jump table), called, or jumped to from two or more different
        // functions (a shared tail — bodies can't overlap, so it must be its own function;
        // deleting it would make every function that jumps there look truncated, forever).
        ArrayList<Address> spurious = new ArrayList<>();
        for (Function f : fm.getFunctions(true)) {
            Address e = f.getEntryPoint();
            if (e.getOffset() < LO || e.getOffset() >= HI) continue;
            if (!f.getName().startsWith("FUN_")) continue;
            Instruction prev = getInstructionBefore(e);
            if (prev == null || !e.equals(prev.getFallThrough())) continue;
            if (isRealEntry(e, f)) continue;
            spurious.add(e);
        }
        int removed = 0;
        for (Address e : spurious) { Function f = getFunctionAt(e); if (f != null) { removeFunction(f); removed++; } }

        // Step 2: collect truncated functions
        ArrayList<Object[]> trunc = new ArrayList<>();   // {entryOffset, name}
        int byFallThrough = 0, byJump = 0;
        for (Function f : fm.getFunctions(true)) {
            Address e = f.getEntryPoint();
            if (e.getOffset() < LO || e.getOffset() >= HI) continue;
            AddressSetView body = f.getBody();
            if (body.getNumAddresses() <= 2) continue;        // 2-byte-body quirk: leave it
            boolean truncated = false;
            // (a) last instruction falls through to code outside the body
            Instruction last = getInstructionContaining(body.getMaxAddress());
            if (last != null) {
                Address ft = last.getFallThrough();
                if (ft != null && getInstructionAt(ft) != null && !body.contains(ft)
                        && getFunctionContaining(ft) == null) { truncated = true; byFallThrough++; }   // into another fn's body = shared tail, not fixable
            }
            // (b) a jump inside the body lands on code outside it that no function owns
            if (!truncated) {
                outer:
                for (Instruction ins : currentProgram.getListing().getInstructions(body, true)) {
                    if (!ins.getFlowType().isJump()) continue;
                    for (Address t : ins.getFlows()) {
                        long o = t.getOffset();
                        if (o < LO || o >= HI || body.contains(t)) continue;
                        if (getInstructionAt(t) == null) continue;           // not code
                        if (getFunctionContaining(t) != null) continue;      // another function's entry/body
                        truncated = true; byJump++; break outer;
                    }
                }
            }
            if (truncated) trunc.add(new Object[]{e.getOffset(), f.getName()});
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
                + " (fallThrough=" + byFallThrough + " jump=" + byJump + ") retraced=" + retraced + " failed=" + failed);
        // Name the few that were retraced so a function that re-appears here on EVERY pass
        // can be identified: that is the headless 2-byte-body quirk (createFunction leaves a
        // 2-byte body whose fall-through is always "outside"), harmless — see PROCESS.md.
        if (!trunc.isEmpty() && trunc.size() <= 8) {
            StringBuilder sb = new StringBuilder("RetraceBodies: retraced entries:");
            for (Object[] t : trunc) sb.append(' ').append(Long.toHexString((Long) t[0])).append('=').append((String) t[1]);
            println(sb.toString());
        }
    }

    // Referenced from data, called, or jumped to from >=2 distinct other functions.
    private boolean isRealEntry(Address e, Function self) {
        HashSet<Address> jumpers = new HashSet<>();
        for (ghidra.program.model.symbol.Reference r : getReferencesTo(e)) {
            Address from = r.getFromAddress();
            ghidra.program.model.symbol.RefType rt = r.getReferenceType();
            if (getInstructionAt(from) == null) return true;          // data reference
            if (rt.isCall()) return true;
            Function fo = getFunctionContaining(from);
            if (fo != null && !fo.getEntryPoint().equals(self.getEntryPoint())) jumpers.add(fo.getEntryPoint());
        }
        return jumpers.size() >= 2;
    }
}
