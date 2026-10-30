// WRITE: apply cross-image ported names. Args: tsv (addr<TAB>name[<TAB>method]). Guard: only names
// functions whose current name is FUN_/generic; marks each with a plate comment noting how it was
// ported (the optional 3rd column: signature / call-site / table-slot / table-label).
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.symbol.*;
import java.io.*;
public class ApplyPortedNames extends GhidraScript {
    @Override public void run() throws Exception {
        BufferedReader r = new BufferedReader(new FileReader(getScriptArgs()[0]));
        String ln; int applied=0, skipped=0;
        while ((ln=r.readLine())!=null) {
            String[] c = ln.split("\t"); if (c.length<2) continue;
            Address a = toAddr(Long.parseLong(c[0].trim(),16)); String name=c[1].trim();
            Function f = getFunctionAt(a);
            if (f==null && getInstructionAt(a)!=null) { try{f=createFunction(a,null);}catch(Exception e){} }
            Symbol p = currentProgram.getSymbolTable().getPrimarySymbol(a);
            if (p!=null && p.getSource()!=SourceType.DEFAULT && !p.getName().startsWith("FUN_")
                && !p.getName().startsWith("LAB_")) { skipped++; continue; }
            try { createLabel(a, name, true, SourceType.USER_DEFINED);
                  if (f!=null && f.getComment()==null) f.setComment("[auto-ported from the reference image by " + (c.length>2 ? c[2].trim() : "signature match") + "]");
                  applied++; } catch (Exception e) { skipped++; }
        }
        r.close();
        println("ApplyPortedNames: applied="+applied+" skipped="+skipped);
    }
}
