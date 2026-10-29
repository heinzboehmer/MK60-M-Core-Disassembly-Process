//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.program.model.data.Undefined4DataType;
import ghidra.program.model.lang.PrototypeModel;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.SourceType;
public class FixStorage2 extends GhidraScript {
    @Override public void run() throws Exception {
        PrototypeModel dc = currentProgram.getCompilerSpec().getDefaultCallingConvention();
        String conv = dc != null ? dc.getName() : null;
        println("default convention = " + conv);
        int fixed=0;
        for (Function f : currentProgram.getFunctionManager().getFunctions(true)) {
            Parameter r = f.getReturn();
            if (r == null || r.getVariableStorage() == null || !r.getVariableStorage().isUnassignedStorage()) continue;
            try {
                f.setCustomVariableStorage(false);
                if (conv != null) f.setCallingConvention(conv);
                f.setReturnType(Undefined4DataType.dataType, SourceType.USER_DEFINED);
                fixed++;
            } catch (Exception e) { println("  fail "+f.getEntryPoint()+": "+e.getMessage()); }
        }
        println("FixStorage2: fixed=" + fixed);
        for (long a : new long[]{0xb2096L,0x790feL,0xa37eeL}) {
            Function f=getFunctionAt(toAddr(a));
            println(String.format("  %06x %s conv=%s storage=%s", a, f.getName(), f.getCallingConventionName(), f.getReturn().getVariableStorage().toString()));
        }
    }
}
