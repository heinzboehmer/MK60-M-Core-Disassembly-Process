// Read-only: inventory the data/table section by harvesting every pointer/reference FROM real
// code (< DATA_LO) INTO the data section [DATA_LO, DATA_HI). Two sources: (1) Ghidra refs whose
// target is in range and whose source is NOT in the data section (filters garbage self-refs from
// mis-disassembled data); (2) a 4-aligned scan of the code region for pointer words whose VALUE
// lands in range (literal-pool table bases, incl. those whose ref wasn't materialized). Clusters
// unique bases into tables (base -> next base) and names a reader function per base. Prints TSV.
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.address.AddressIterator;
import ghidra.program.model.listing.Function;
import ghidra.program.model.mem.*;
import ghidra.program.model.symbol.*;
import java.util.*;

public class DataTableInventory extends GhidraScript {
    @Override public void run() throws Exception {
        long DATA_LO = getScriptArgs().length>0 ? Long.parseLong(getScriptArgs()[0],16) : 0xD6710L;
        long DATA_HI = getScriptArgs().length>1 ? Long.parseLong(getScriptArgs()[1],16) : 0xF6A24L;
        Memory mem = currentProgram.getMemory();
        ReferenceManager rm = currentProgram.getReferenceManager();
        // base -> set of source addresses (where the pointer/ref lives)
        TreeMap<Long,TreeSet<Long>> bases = new TreeMap<>();
        // 1) reference manager — iterate every reference source address
        AddressIterator srcIt = rm.getReferenceSourceIterator(currentProgram.getMemory(), true);
        while (srcIt.hasNext()) {
            Address from = srcIt.next();
            long f = from.getOffset();
            if (f >= DATA_LO && f < DATA_HI) continue;          // skip sources inside the data section
            for (Reference r : rm.getReferencesFrom(from)) {
                long t = r.getToAddress().getOffset();
                if (t >= DATA_LO && t < DATA_HI)
                    bases.computeIfAbsent(t, k->new TreeSet<>()).add(f);
            }
        }
        // 2) pointer-word scan of the code region (4-aligned)
        long scanLo = 0x40000L, scanHi = DATA_LO;
        for (long a = (scanLo+3)&~3L; a+4 <= scanHi; a += 4) {
            long v; try { v = mem.getInt(toAddr(a)) & 0xFFFFFFFFL; } catch (Exception e) { continue; }
            if (v >= DATA_LO && v < DATA_HI) bases.computeIfAbsent(v, k->new TreeSet<>()).add(a);
        }
        // cluster + report
        Long[] keys = bases.keySet().toArray(new Long[0]);
        println("BASE\tSIZE\tNREFS\tREADERS");
        for (int i=0;i<keys.length;i++){
            long base = keys[i];
            long size = (i+1<keys.length? keys[i+1] : DATA_HI) - base;
            TreeSet<Long> src = bases.get(base);
            // name readers: for each source, the function containing it; if source is data (a
            // pool slot), the functions that reference that slot
            LinkedHashSet<String> readers = new LinkedHashSet<>();
            for (long s : src) {
                Function fn = getFunctionContaining(toAddr(s));
                if (fn != null) { readers.add(fn.getName()); }
                else {
                    for (Reference r2 : rm.getReferencesTo(toAddr(s))) {
                        Function fn2 = getFunctionContaining(r2.getFromAddress());
                        if (fn2 != null) readers.add(fn2.getName());
                    }
                }
                if (readers.size()>=4) break;
            }
            StringBuilder rd = new StringBuilder();
            int n=0; for (String s: readers){ if(n++>0) rd.append(","); rd.append(s); if(n>=4) break; }
            println(String.format("%06x\t%d\t%d\t%s", base, size, src.size(), rd.length()==0?"-":rd.toString()));
        }
        println("TOTAL_BASES\t"+keys.length);
    }
}
