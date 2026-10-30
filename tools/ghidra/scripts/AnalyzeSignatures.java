// WRITE: give every function a signature — parameters, return, stack locals — the way
// Ghidra's auto-analysis would, but on the RECOVERED listing. The recovery sessions all run
// with -noanalysis (auto-analysis is what mis-decodes an M-CORE image), so without this pass
// every function keeps the default `undefined FUN_x(void)` and the decompiler shows calls
// with no arguments. Runs Ghidra's own commands directly, nothing else:
//   NewFunctionStackAnalysisCmd  — stack frame: locals + stack params from the prologue
//   DecompilerParameterIdCmd     — register params/return from decompiler data flow
//                                  (source = ANALYSIS; user-set signatures are kept;
//                                  void returns ARE committed — without that flag no function
//                                  can ever become `void`, and 80% of them end up `undefined4`
//                                  returning whatever the last callee left in r2)
// Run ReadOnlyFlash first: with writable flash the decompiler cannot resolve pool-loaded
// callees, so parameter ID works against unknown call targets.
// Idempotent; re-run after any change to the function set. Args: LO HI (hex, no 0x); the
// range is [LO, HI) over function ENTRIES (use 40000 <dataHI> to include the init stubs).
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.app.cmd.function.DecompilerParameterIdCmd;
import ghidra.app.cmd.function.NewFunctionStackAnalysisCmd;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.SourceType;

public class AnalyzeSignatures extends GhidraScript {
    @Override
    public void run() throws Exception {
        String[] a = getScriptArgs();
        long lo = a.length > 0 ? Long.parseLong(a[0], 16) : 0x40000L;
        long hi = a.length > 1 ? Long.parseLong(a[1], 16) : 0xF6A24L;
        int timeout = a.length > 2 ? Integer.parseInt(a[2]) : 60;

        AddressSet entries = new AddressSet();
        int total = 0;
        for (Function f : currentProgram.getFunctionManager().getFunctions(true)) {
            long o = f.getEntryPoint().getOffset();
            if (o < lo || o >= hi) continue;
            entries.add(f.getEntryPoint());
            total++;
        }
        int[] before = count(lo, hi);

        NewFunctionStackAnalysisCmd stack = new NewFunctionStackAnalysisCmd(entries, true);
        boolean okStack = stack.applyTo(currentProgram, monitor);

        DecompilerParameterIdCmd pid = new DecompilerParameterIdCmd("AnalyzeSignatures", entries,
                SourceType.ANALYSIS, true, true, timeout);
        boolean okPid = pid.applyTo(currentProgram, monitor);

        int[] after = count(lo, hi);
        println("AnalyzeSignatures: functions=" + total + " stackCmd=" + (okStack ? "ok" : "FAILED")
                + " paramIdCmd=" + (okPid ? "ok" : "FAILED")
                + " withParams " + before[0] + " -> " + after[0]
                + " withLocals " + before[1] + " -> " + after[1]
                + " nonDefaultSignature " + before[2] + " -> " + after[2]);
    }

    private int[] count(long lo, long hi) {
        int params = 0, locals = 0, sig = 0;
        for (Function f : currentProgram.getFunctionManager().getFunctions(true)) {
            long o = f.getEntryPoint().getOffset();
            if (o < lo || o >= hi) continue;
            if (f.getParameterCount() > 0) params++;
            if (f.getLocalVariables().length > 0) locals++;
            if (f.getSignatureSource() != SourceType.DEFAULT) sig++;
        }
        return new int[]{params, locals, sig};
    }
}
