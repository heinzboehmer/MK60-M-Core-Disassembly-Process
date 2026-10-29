import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.*;
import ghidra.program.model.listing.*;
public class ForceData extends GhidraScript {
  public void run() throws Exception {
    for (String s: getScriptArgs()) {
      Address a=toAddr(Long.parseLong(s,16));
      Function f=getFunctionContaining(a);
      if(f!=null && f.getEntryPoint().equals(a)) removeFunction(f);
      // clear any instruction run starting at a up to next defined boundary (max 64B)
      Address p=a; for(int i=0;i<32;i++){ Instruction ins=getInstructionAt(p); if(ins==null)break; Address nx=ins.getMaxAddress().add(1); clearListing(p,ins.getMaxAddress()); p=nx; }
      try{ createData(a,new Undefined4DataType()); }catch(Exception e){}
      println("  forced data @ "+s+" instrAfter="+(getInstructionAt(a)!=null)+" dataAfter="+(getDataAt(a)!=null?getDataAt(a).getDataType().getName():"-"));
    }
  }
}
