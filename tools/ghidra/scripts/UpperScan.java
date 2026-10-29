// Read-only: classify the firmware tail. Walk every initialized exec block from LO up.
// Maximal UNDEFINED runs (>=6B) classified: PADDING(all 00/FF), MONOTONIC(sorted hwords=table),
// PTRTABLE(>=50% plausible addrs), CODELIKE(decodes to terminators cleanly), OTHER.
// Also report disassembled-CODE functions in range split by referenced vs orphan.
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.app.util.PseudoDisassembler;
import ghidra.app.util.PseudoInstruction;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.Undefined;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.*;
import ghidra.program.model.symbol.*;
import java.util.*;

public class UpperScan extends GhidraScript {
    PseudoDisassembler pd; Memory mem;
    boolean plausible(long v){ return (v>=0x40000L&&v<0xf7000L)||(v>=0x400000L&&v<0x424000L)||(v>=0xd50000L&&v<0x1000000L); }
    @Override public void run() throws Exception {
        pd=new PseudoDisassembler(currentProgram); mem=currentProgram.getMemory();
        long LO = getScriptArgs().length>0 ? Long.parseLong(getScriptArgs()[0],16) : 0xd0000L;
        Listing lst=currentProgram.getListing();
        long padding=0,mono=0,ptr=0,code=0,other=0; int runs=0;
        StringBuilder big=new StringBuilder();
        for (MemoryBlock b: mem.getBlocks()){
            if(!b.isInitialized()||!b.isExecute()) continue;
            long bs=b.getStart().getOffset(), be=b.getEnd().getOffset();
            if(be<LO) continue; long a=Math.max(bs,LO);
            while(a<=be){
                Address ad=toAddr(a);
                if(getInstructionAt(ad)!=null){ a+=getInstructionAt(ad).getLength(); continue; }
                Data d=lst.getDataAt(ad);
                if(d!=null&&d.isDefined()&&!(d.getDataType() instanceof Undefined)){ a+=d.getLength(); continue; }
                long s=a;
                while(a<=be){
                    if(getInstructionAt(toAddr(a))!=null) break;
                    Data dd=lst.getDataAt(toAddr(a));
                    if(dd!=null&&dd.isDefined()&&!(dd.getDataType() instanceof Undefined)) break;
                    a++;
                }
                long len=a-s; if(len<6) continue; runs++;
                String cls=classify(s,len);
                if(cls.equals("PADDING"))padding+=len; else if(cls.equals("MONOTONIC"))mono+=len;
                else if(cls.equals("PTRTABLE"))ptr+=len; else if(cls.equals("CODELIKE"))code+=len; else other+=len;
                if(len>=64 && (cls.equals("CODELIKE")||cls.equals("OTHER")))
                    big.append(String.format("  %08x len=%-5d %s%n",s,len,cls));
            }
        }
        println(String.format("UNDEF runs>=6B from %06x: count=%d | PADDING=%d MONOTONIC=%d PTRTABLE=%d CODELIKE=%d OTHER=%d",
            LO,runs,padding,mono,ptr,code,other));
        println("-- large CODELIKE/OTHER undefined runs (>=64B) --"); println(big.toString());
        // disassembled code in range: referenced vs orphan
        int refd=0, orphan=0; long orphanB=0;
        FunctionIterator fi=currentProgram.getFunctionManager().getFunctions(toAddr(LO),true);
        while(fi.hasNext()){ Function f=fi.next();
            int rc=getReferencesTo(f.getEntryPoint()).length;
            if(rc>0) refd++; else { orphan++; orphanB+=f.getBody().getNumAddresses(); }
        }
        println(String.format("functions>=%06x: referenced=%d orphan(no xref)=%d orphanBytes=%d",LO,refd,orphan,orphanB));
    }
    String classify(long s,long len){
        boolean z=true,f=true;
        try{ for(long p=s;p<s+Math.min(len,256);p++){int v=mem.getByte(toAddr(p))&0xff; if(v!=0)z=false; if(v!=0xff)f=false;} }catch(Exception e){}
        if(z||f) return "PADDING";
        // monotonic hwords?
        long a0=(s+1)&~1L; int n=0,incr=0,decr=0; int prev=-1;
        for(long p=a0;p+2<=s+len&&n<64;p+=2){ int v; try{v=mem.getShort(toAddr(p))&0xffff;}catch(Exception e){break;} if(prev>=0){ if(v>=prev)incr++; else decr++; } prev=v; n++; }
        if(n>=8 && (incr>=n-2||decr>=n-2)) return "MONOTONIC";
        // pointer table?
        long w0=(s+3)&~3L; int words=0,ptrs=0;
        for(long p=w0;p+4<=s+len;p+=4){ words++; try{ if(plausible(mem.getInt(toAddr(p))&0xffffffffL))ptrs++; }catch(Exception e){} }
        if(words>=2 && ptrs*2>=words) return "PTRTABLE";
        // code-like: decode cleanly to a terminator
        long es=(s+1)&~1L; Address p=toAddr(es); int ni=0;
        for(int i=0;i<24;i++){ if(p.getOffset()>=s+len) break; PseudoInstruction pi; try{pi=pd.disassemble(p);}catch(Exception e){pi=null;} if(pi==null) return ni>=8?"CODELIKE":"OTHER"; ni++; String m=pi.getMnemonicString(); if(m.equals("br")||m.equals("jmpi")||m.equals("rts")||m.equalsIgnoreCase("jmp")) return ni>=4?"CODELIKE":"OTHER"; int l=pi.getLength(); if(l<=0)break; p=p.add(l); }
        return "OTHER";
    }
}
