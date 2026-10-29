// Read-only: list every memory block, then a full landscape scan across ALL
// initialized blocks classifying each maximal run as CODE (instructions),
// DATA (defined non-undefined), or UNDEF (undefined/undefined-typed). No hardcoded bounds.
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.*;
import ghidra.program.model.data.Undefined;

public class ImageMap extends GhidraScript {
    @Override public void run() throws Exception {
        Memory mem = currentProgram.getMemory();
        println("=== BLOCKS ===");
        for (MemoryBlock b : mem.getBlocks()) {
            println(String.format("%-14s %08x - %08x  %7d B  %s%s%s%s",
                b.getName(), b.getStart().getOffset(), b.getEnd().getOffset(), b.getSize(),
                b.isInitialized()?"I":"-", b.isRead()?"R":"-", b.isWrite()?"W":"-", b.isExecute()?"X":"-"));
        }
        println("\n=== LANDSCAPE (maximal runs, initialized blocks only) ===");
        Listing lst = currentProgram.getListing();
        for (MemoryBlock b : mem.getBlocks()) {
            if (!b.isInitialized()) continue;
            long start=b.getStart().getOffset(), end=b.getEnd().getOffset();
            long codeB=0, dataB=0, undefB=0;
            long a=start;
            // summary counts per block
            while (a<=end) {
                Address ad=toAddr(a);
                Instruction ins=getInstructionAt(ad);
                if (ins!=null){ long l=ins.getLength(); codeB+=l; a+=l; continue; }
                Data d=lst.getDataAt(ad);
                if (d!=null && d.isDefined() && !(d.getDataType() instanceof Undefined)){ long l=d.getLength(); dataB+=l; a+=l; continue; }
                undefB++; a+=1;
            }
            println(String.format("%-14s %08x-%08x  code=%d data=%d undef=%d", b.getName(), start, end, codeB, dataB, undefB));
        }
    }
}
