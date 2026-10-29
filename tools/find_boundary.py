#!/usr/bin/env python3
"""Programmatically locate the code/data boundary of an MK60E5 M·CORE image, and
sanity-check that the image even IS M·CORE — directly from a CPU-address Intel HEX
(the `.cpu.hex` that parse_0pa.py emits). No Ghidra, no third-party code.

How: M·CORE functions almost always open with the frame-setup prologue

    subi  r0, N        halfword  (h & 0xFE0F) == 0x2400     (r0 is the stack pointer)
    stm   rX-r15, (r0) halfword  (h & 0xFFF0) == 0x0070

This pair is a strong, architecture-specific signal: it is dense and continuous
through the code, and essentially never occurs by chance in the calibration/lookup
data above the code (two constrained big-endian halfwords in a row, p ~ 1e-6 per
position). So:

  * where the prologue density per bucket COLLAPSES to ~0 marks the end of the code;
  * the highest prologue overall is the last function's ENTRY — its body + pool run a
    few KB higher, so the exact `dataLO` is pinned on the Ghidra side afterward
    (FindBoundary.java), but this gives the recovery ceiling and the data-section top;
  * if there are very few prologues at all, the image is probably NOT M·CORE — warn,
    don't proceed as if it were (an ARM/PPC/SH image fed in by mistake wastes hours).

Emits a `BOUNDS ...` line (machine-readable) and a ready-to-run `mcore_recover.sh`
command. Run isolated:  python3 -I tools/find_boundary.py <image.cpu.hex>
stdlib only; read-only.

Credit: the function-prologue seed heuristic and the idea of finding the code end from
where linear-sweep function seeds stop come from cmg5461's `mcore_dis.py`
(https://github.com/cmg5461/mk60e5-firmware). This is an independent reimplementation
against the M·CORE encoding (binutils opcodes/mcore-opc.h); no code was copied.
"""
import sys, collections

CODE_LO = 0x42000          # constant for MK60E5: VBR table + calibration dir sit below this
BUCKET = 0x2000
GAP = 0x1000               # addresses this far apart start a new memory segment


def parse_ihex(path):
    """Intel HEX -> {addr: byte}. Handles record types 00/01/02/04."""
    mem = {}
    base = 0
    with open(path) as fh:
        for ln in fh:
            ln = ln.strip()
            if not ln.startswith(":"):
                continue
            b = bytes.fromhex(ln[1:])
            n, a_hi, a_lo, rtype = b[0], b[1], b[2], b[3]
            addr = (a_hi << 8) | a_lo
            data = b[4:4 + n]
            if rtype == 0x00:
                for i, v in enumerate(data):
                    mem[base + addr + i] = v
            elif rtype == 0x01:
                break
            elif rtype == 0x02:
                base = ((data[0] << 8) | data[1]) << 4
            elif rtype == 0x04:
                base = ((data[0] << 8) | data[1]) << 16
    return mem


def main_segment(mem):
    """The contiguous segment (allowing <GAP gaps) that contains CODE_LO — the main
    code+data image, not the stray low/high config blocks."""
    addrs = sorted(mem)
    if not addrs:
        sys.exit("no data records in hex")
    segs, start, prev = [], addrs[0], addrs[0]
    for a in addrs[1:]:
        if a - prev > GAP:
            segs.append((start, prev))
            start = a
        prev = a
    segs.append((start, prev))
    for lo, hi in segs:
        if lo <= CODE_LO <= hi:
            return lo, hi
    return segs[0]            # fallback: first/largest


def be16(mem, a):
    hi, lo = mem.get(a), mem.get(a + 1)
    if hi is None or lo is None:
        return None
    return (hi << 8) | lo


def main():
    if len(sys.argv) < 2:
        sys.exit("usage: find_boundary.py <image.cpu.hex>")
    mem = parse_ihex(sys.argv[1])
    seg_lo, seg_hi = main_segment(mem)
    data_hi = (seg_hi + 1 + 1) & ~1                  # end of image, 2-byte aligned

    # prologue scan over the code-and-data span
    buckets = collections.Counter()
    prologues = []
    a = CODE_LO
    while a < data_hi - 2:
        h0 = be16(mem, a)
        if h0 is not None and (h0 & 0xFE0F) == 0x2400:
            h1 = be16(mem, a + 2)
            if h1 is not None and (h1 & 0xFFF0) == 0x0070:
                buckets[a & ~(BUCKET - 1)] += 1
                prologues.append(a)
        a += 2

    total = len(prologues)
    print("=== M·CORE prologue scan of %s ===" % sys.argv[1])
    print("image segment: 0x%x .. 0x%x   (dataHI = 0x%x)" % (seg_lo, seg_hi, data_hi))
    print("prologues found: %d" % total)
    for b in sorted(buckets):
        bar = "#" * min(60, buckets[b])
        print("  %08x-%08x : %3d %s" % (b, b + BUCKET - 1, buckets[b], bar))

    # arch sanity: a real MK60E5 M·CORE image has hundreds+ of these
    if total < 50:
        print()
        print("!! WARNING: only %d prologues found — this does NOT look like an MK60E5" % total)
        print("!! M·CORE image. Check the architecture (e.g. `cpu_rec`) and the --reloc")
        print("!! before continuing; the pipeline assumes MCORE:BE:32 and will produce")
        print("!! garbage on another architecture.")
        print("BOUNDS arch=unknown")
        return

    hp = prologues[-1]                                # highest prologue = last fn entry
    # recovery ceiling: several buckets above the last entry so the last function's
    # body + pool is comfortably inside the range (bodies run some KB above the entry;
    # e.g. 1M entry 0xd5998, body end ~0xd8118). Capped below dataHI. The exact dataLO
    # is pinned Ghidra-side afterward (DataSectionClean `auto` = last real fn body end).
    code_hi = min(((hp & ~(BUCKET - 1)) + 3 * BUCKET), data_hi)
    print()
    print("highest prologue (last function entry) : 0x%x" % hp)
    print("recovery ceiling codeHI (estimate)     : 0x%x" % code_hi)
    print("NOTE: the exact dataLO = end of the last real function's body is pinned")
    print("      Ghidra-side (DataSectionClean `auto`); mcore_recover.sh does this for you.")
    print()
    print("BOUNDS arch=mcore codeLO=0x%x codeHI=0x%x dataHI=0x%x" % (CODE_LO, code_hi, data_hi))
    print()
    print("# recommended (auto-bounds):")
    print("#   tools/ghidra/mcore_recover.sh <proj_dir> <name> %s" % sys.argv[1])
    print("# or explicit:")
    print("#   tools/ghidra/mcore_recover.sh <proj_dir> <name> %s %x %x %x"
          % (sys.argv[1], CODE_LO, code_hi, data_hi))


if __name__ == "__main__":
    main()
