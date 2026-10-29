// Recover code that Ghidra left as raw bytes because disassembly flow was never followed
// into it. Iteratively, for every instruction, seed:
//   - its fall-through (non-terminators only; terminators have none, so literal pools
//     that sit after a tail jmpi/br/rts are left alone);
//   - its resolved flow targets (getFlows());
//   - for a branch (bt/bf/br/bsr) whose getFlows() is EMPTY, the PC-relative target
//     computed straight from the 16-bit encoding (branch=(0,10) signed, tgt=addr+2+
//     (disp<<1)). The M-CORE SLEIGH models branches as *computed* jumps that only
//     auto-analysis resolves; in code analysis never reached (e.g. the mfg command
//     handlers, entered through the formerly-broken jmpi/jsri) the target is unresolved,
//     so getFlows()/xrefs are empty and we must compute it ourselves;
//   - for an indirect jmpi/jsri whose getFlows() is EMPTY, the pointer read from its
//     literal-pool slot (its read/data reference target).
// A seed is disassembled only if it is raw undefined bytes or an `undefined`-typed item
// (blocker) in the code range -- never real pointer/array/struct/int/cal data.
//
// It also RECORDS the resolved target as a flow reference on the instruction (the branch's
// own flow type: CONDITIONAL_COMPUTED_JUMP / COMPUTED_JUMP / COMPUTED_CALL). Ghidra only
// materializes computed-flow references during auto-analysis, so code disassembled by these
// scripts had branches with no reference at all: function-body tracing, RetraceBodies and the
// decompiler all stopped at the first bt/bf of such a function, leaving the rest of it as
// instructions owned by no function. With the reference in place getFlows() resolves.
// Follow with FixLiteralPools/FixResidual to tidy pools the new code pulls in.
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.app.util.PseudoDisassembler;
import ghidra.app.util.PseudoInstruction;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.DefaultDataType;
import ghidra.program.model.data.Undefined;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.symbol.Reference;
import ghidra.program.model.symbol.RefType;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.model.listing.CodeUnit;
import java.util.*;

public class RecoverFlowCode extends GhidraScript {
    private long LO = 0x42000L, HI = 0xD7000L;  // override via args: LO HI (hex)
    private Memory mem;
    private PseudoDisassembler pd;
    private final ArrayList<long[]> tableRanges = new ArrayList<>();  // {start, endExclusive}

    @Override
    public void run() throws Exception {
        String[] _bnd = getScriptArgs(); if (_bnd.length>=2) { LO=Long.parseLong(_bnd[0],16); HI=Long.parseLong(_bnd[1],16); }
        Listing listing = currentProgram.getListing();
        mem = currentProgram.getMemory();
        pd = new PseudoDisassembler(currentProgram);
        findTableRanges();          // runs of >=4 consecutive code-pointer words = jump tables; never code
        int errBefore = errorCount();
        int totalDisasm = 0, totalFns = 0, refsAdded = 0;

        for (int pass = 0; pass < 12; pass++) {
            LinkedHashSet<Long> seeds = new LinkedHashSet<>();
            HashSet<Long> callTargets = new HashSet<>();
            InstructionIterator it = listing.getInstructions(true);
            while (it.hasNext()) {
                Instruction ins = it.next();
                Address ft = ins.getFallThrough();            // null for terminators
                if (ft != null && seedable(ft)) seeds.add(ft.getOffset());

                boolean isCall = ins.getFlowType().isCall();
                Address[] flows = ins.getFlows();
                for (Address t : flows) {
                    if (seedable(t)) { seeds.add(t.getOffset()); if (isCall) callTargets.add(t.getOffset()); }
                }
                if (flows.length > 0) continue;               // resolved already

                // Unresolved computed flow: recover the target ourselves.
                String m = ins.getMnemonicString();
                Address t = null; boolean call = false;
                if (m.equals("bt") || m.equals("bf") || m.equals("br") || m.equals("bsr")) {
                    t = branchTarget(ins);
                    call = m.equals("bsr");
                } else if (m.equals("jmpi") || m.equals("jsri")) {
                    t = indirectTarget(ins);
                    call = m.equals("jsri");
                }
                if (t == null) continue;
                // Record the flow so Ghidra (body tracing, decompiler, xrefs) can follow it.
                if (mem.contains(t) && !inTable(t.getOffset())) {
                    boolean indirect = m.equals("jmpi") || m.equals("jsri");
                    try {
                        currentProgram.getReferenceManager().addMemoryReference(ins.getAddress(), t,
                                ins.getFlowType(), SourceType.ANALYSIS, indirect ? CodeUnit.MNEMONIC : 0);
                        refsAdded++;
                    } catch (Exception e) {}
                }
                if (seedable(t)) { seeds.add(t.getOffset()); if (call) callTargets.add(t.getOffset()); }
            }
            if (seeds.isEmpty()) break;
            int made = 0;
            for (long off : seeds) {
                Address a = toAddr(off);
                if (!cleanDecode(a)) continue;        // reject targets that land in a pool/data
                Data d = getDataAt(a);
                if (d != null && d.isDefined()) clearListing(a, d.getMaxAddress());  // only undefined-typed reaches here
                if (getInstructionAt(a) != null) continue;
                try { disassemble(a); } catch (Exception e) {}
                if (getInstructionAt(a) != null) {
                    made++;
                    if (callTargets.contains(off) && getFunctionAt(a) == null) {
                        try { if (createFunction(a, null) != null) totalFns++; } catch (Exception e) {}
                    }
                }
            }
            totalDisasm += made;
            if (made == 0) break;
        }

        int errAfter = errorCount();
        println("RecoverFlowCode: seedsDisassembled=" + totalDisasm + " newFunctions=" + totalFns
                + " flowRefsAdded=" + refsAdded + " errorBookmarks " + errBefore + " -> " + errAfter);
    }

    // PC-relative branch target from the 16-bit encoding: disp = bits[0..10] signed.
    private Address branchTarget(Instruction ins) {
        try {
            byte[] b = ins.getBytes();
            if (b.length < 2) return null;
            int w = ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
            int disp = w & 0x7FF;
            if ((disp & 0x400) != 0) disp -= 0x800;           // sign-extend 11-bit
            return ins.getAddress().add(2 + (disp << 1));
        } catch (Exception e) { return null; }
    }

    // Indirect jmpi/jsri: the pointer stored at the instruction's pool slot
    // (its in-memory read/data reference target).
    private Address indirectTarget(Instruction ins) {
        try {
            for (Reference r : ins.getReferencesFrom()) {
                RefType rt = r.getReferenceType();
                Address slot = r.getToAddress();
                if ((rt.isRead() || rt.isData()) && slot != null && mem.contains(slot)) {
                    long v = (mem.getInt(slot) & 0xFFFFFFFFL) & ~1L;
                    return toAddr(v);
                }
            }
        } catch (Exception e) {}
        return null;
    }

    // Trial-decode (no writes) from a: accept if a clean instruction stream reaches an
    // unconditional terminator (br/jmpi/jmp/rts) within 16 instrs, or 16 instrs decode
    // with no invalid; reject if an invalid decode appears first (= the target is data,
    // e.g. a literal pool a false branch/pointer lands in). Keeps the pass from fighting
    // FixLiteralPools over data targets.
    private boolean cleanDecode(Address a) {
        try {
            Address p = a;
            for (int i = 0; i < 16; i++) {
                PseudoInstruction ins = pd.disassemble(p);
                if (ins == null) return false;
                String m = ins.getMnemonicString();
                if (m.equals("br") || m.equals("jmpi") || m.equals("jmp") || m.equals("rts")) return true;
                int l = ins.getLength(); if (l <= 0) return false;
                p = p.add(l);
            }
            return true;
        } catch (Exception e) { return false; }
    }

    // Jump/pointer tables: runs of >=4 consecutive 4-byte code-range pointers. Their bytes
    // decode as valid instructions (idly4/movi/jmpi), so a false branch/indirect target
    // landing in one would be mis-disassembled -- exclude them from seeding entirely.
    private void findTableRanges() {
        long rs = -1; int rl = 0;
        for (long p = LO; p + 4 <= HI; p += 4) {
            long v;
            try { v = mem.getInt(toAddr(p)) & 0xFFFFFFFFL; } catch (Exception e) { v = 0; }
            boolean ptr = (v >= LO && v < HI);
            if (ptr) { if (rs < 0) { rs = p; rl = 0; } rl++; }
            else { if (rs >= 0 && rl >= 4) tableRanges.add(new long[]{rs, rs + rl * 4L}); rs = -1; rl = 0; }
        }
        if (rs >= 0 && rl >= 4) tableRanges.add(new long[]{rs, rs + rl * 4L});
    }

    private boolean inTable(long o) {
        for (long[] t : tableRanges) if (o >= t[0] && o < t[1]) return true;
        return false;
    }

    // seedable = in code range, not already an instruction, and not real (non-undefined) data
    private boolean seedable(Address a) {
        if (a == null) return false;
        long o = a.getOffset();
        if (o < LO || o >= HI) return false;
        if (inTable(o)) return false;
        if (getInstructionAt(a) != null) return false;
        Data d = getDataAt(a);
        if (d != null && d.isDefined()) {
            var dt = d.getDataType();
            if (!(dt instanceof Undefined || dt instanceof DefaultDataType)) return false;
        }
        // Reject addresses that an instruction reads as data (a literal-pool slot): these
        // are the same targets FixLiteralPools defines as data, so seeding them as code
        // would just oscillate. A false branch/pointer into a pool is thus ignored.
        for (Reference r : getReferencesTo(a)) {
            RefType rt = r.getReferenceType();
            if (rt.isRead() || (rt.isData() && !rt.isFlow() && !rt.isCall())) return false;
        }
        return true;
    }

    private int errorCount() {
        int n = 0;
        var it = currentProgram.getBookmarkManager().getBookmarksIterator(BookmarkType.ERROR);
        while (it.hasNext()) { it.next(); n++; }
        return n;
    }
}
