#!/usr/bin/env python3
"""Parse a BMW .0pa flash-data file (Intel HEX + vendor extensions) into flat binaries.

Observations (DSCM90/7846816A.0pa, verified 2026-10-25):
  - ASCII Intel HEX records, `$REFERENZ` header, `$CHECKSUMME` footer, `;` comments.
  - Record types seen: 00 (data), 01 (EOF), 02 (ext segment addr, always 0),
    04 (ext linear addr), and 10 (vendor: a data record in a second "block").
  - The record-type byte is (flag<<4 | std_type). Type 0x10 is a type-00 data
    record with the vendor flag set. Verified on all 46830 DSCM90 records: the
    flag is set on exactly the LAST record of each programming segment, where a
    segment is a contiguous run of records cut at 64 KB sector boundaries. It is
    an end-of-segment marker for the flash tool, not a second bank and not a
    per-sector checksum slot (the bytes at sector tops are ordinary code/data).

Run isolated:  python3 -I tools/parse_0pa.py <file.0pa> [out_dir] [--reloc LO:HI:DELTA ...]
Emits <stem>_<addr>.bin per region (.0pa addresses, gaps filled with the
unused-byte value), <stem>.clean.hex (.0pa addresses), and with --reloc a
<stem>.cpu.hex at CPU runtime addresses. stdlib only.

DSCM90: the application is stored 0x8000 above where it runs, so use
  --reloc 0x48000:0x100000:-0x8000
(VBR is set to 0x40000 by crt0, and the reset vector then points at crt0.)
"""
import sys, os

def parse(path):
    recs = []            # (block, type, addr32, data bytes)
    unused = 0xFF
    ulba = 0             # upper linear base address (type 04) << 16
    useg = 0             # segment base (type 02) << 4
    meta = {}
    errors = []
    with open(path, "r", newline="") as fh:
        for ln, raw in enumerate(fh, 1):
            line = raw.strip()
            if not line:
                continue
            if line.startswith("$"):
                parts = line[1:].split()
                if parts:
                    meta[parts[0]] = parts[1:] if len(parts) > 1 else []
                continue
            if line.startswith(";"):
                body = line[1:].strip()
                if body.startswith("$"):
                    p = body[1:].split()
                    if p:
                        meta[p[0]] = p[1:]
                continue
            if not line.startswith(":"):
                errors.append((ln, "non-record line: %r" % line[:40]))
                continue
            try:
                b = bytes.fromhex(line[1:])
            except ValueError:
                errors.append((ln, "bad hex"))
                continue
            if len(b) < 5:
                errors.append((ln, "short record"))
                continue
            n = b[0]
            addr = (b[1] << 8) | b[2]
            rtype = b[3]
            data = b[4:4 + n]
            chk = b[4 + n] if len(b) > 4 + n else None
            if len(data) != n:
                errors.append((ln, "len mismatch: declared %d got %d" % (n, len(data))))
            if chk is None or (sum(b[:4 + n]) + chk) & 0xFF != 0:
                errors.append((ln, "checksum fail"))
            std = rtype & 0x0F
            block = rtype >> 4
            if std == 0x00:              # data (any block)
                full = ulba + useg + addr
                recs.append((block, full, data))
            elif std == 0x01:            # EOF
                break
            elif std == 0x02:            # ext segment
                useg = ((data[0] << 8) | data[1]) << 4
            elif std == 0x04:            # ext linear
                ulba = ((data[0] << 8) | data[1]) << 16
                useg = 0
            else:
                errors.append((ln, "unhandled std type 0x%02X (rtype 0x%02X)" % (std, rtype)))
    return recs, meta, errors, unused

def merge(recs):
    """Merge ALL data records (any block) into one sparse map addr->byte.

    type-00 and type-0x10 are both data; 0x10 is the vendor end-of-segment
    flag (last record before a gap or a 64 KB boundary) and belongs in the same
    image. Returns (sparse, flagged_addrs, overlaps)."""
    sparse = {}
    flagged = set()          # addresses that came from a type-0x10 record
    overlaps = []
    for block, addr, data in recs:
        for i, byte in enumerate(data):
            a = addr + i
            if a in sparse and sparse[a] != byte:
                overlaps.append((block, a, sparse[a], byte))
            sparse[a] = byte
            if block:
                flagged.add(a)
    return sparse, flagged, overlaps

def split_gaps(sparse, gap):
    """Split the sparse map into regions wherever a gap >= `gap` occurs.
    Return list of (start, end_inclusive) covering the data (gaps < `gap`
    are kept inside a region and will be fill-padded in the bin)."""
    if not sparse:
        return []
    keys = sorted(sparse)
    regs = []
    start = prev = keys[0]
    for a in keys[1:]:
        if a - prev >= gap:
            regs.append((start, prev))
            start = a
        prev = a
    regs.append((start, prev))
    return regs

def write_ihex(sparse, out_path):
    """Re-encode the merged sparse map as a standard Intel HEX file (record
    types 00/04/01 only; vendor type-0x10 flag dropped). Ghidra's 'Intel Hex'
    loader reads this and creates one memory block per contiguous region at the
    correct address — no base/offset needed."""
    def rec(n, addr, rtype, data):
        body = [n, (addr >> 8) & 0xFF, addr & 0xFF, rtype] + list(data)
        chk = (-sum(body)) & 0xFF
        return ":" + "".join("%02X" % b for b in body + [chk])
    lines = []
    cur_ulba = None
    for lo, hi in split_gaps(sparse, 0x10000):   # natural regions
        a = lo
        while a <= hi:
            ulba = (a >> 16) & 0xFFFF
            if ulba != cur_ulba:
                lines.append(rec(2, 0, 4, [(ulba >> 8) & 0xFF, ulba & 0xFF]))
                cur_ulba = ulba
            # emit up to 16 contiguous present bytes without crossing a 64 KB bound
            row = []
            start = a
            while (a <= hi and a in sparse and len(row) < 16
                   and ((a >> 16) & 0xFFFF) == ulba):
                row.append(sparse[a]); a += 1
            if row:
                lines.append(rec(len(row), start & 0xFFFF, 0, row))
            else:
                a += 1  # skip a hole
    lines.append(":00000001FF")
    with open(out_path, "w", newline="\n") as fo:
        fo.write("\n".join(lines) + "\n")
    return len(lines)

def main():
    args = sys.argv[1:]
    relocs = parse_relocs(args)
    if not args:
        print(__doc__); sys.exit(2)
    path = args[0]
    out = args[1] if len(args) > 1 else os.path.dirname(os.path.abspath(path))
    recs, meta, errors, unused = parse(path)
    uval = unused
    if "VALUE_UNUSED_BYTE" in meta and meta["VALUE_UNUSED_BYTE"]:
        try: uval = int(meta["VALUE_UNUSED_BYTE"][0], 16)
        except Exception: pass
    sparse, flagged, overlaps = merge(recs)
    stem = os.path.splitext(os.path.basename(path))[0]
    GAP = 0x4000   # split into separate bins across gaps >= 16 KB

    print("file        : %s (%d bytes)" % (path, os.path.getsize(path)))
    print("meta        : %s" % {k: v for k, v in meta.items()})
    print("records     : %d data records (%d bytes, %d from type-0x10 flag)"
          % (len(recs), len(sparse), len(flagged)))
    print("errors      : %d" % len(errors))
    for ln, msg in errors[:20]:
        print("   line %d: %s" % (ln, msg))
    if overlaps:
        print("OVERLAPS    : %d conflicting byte writes (showing 10)" % len(overlaps))
        for o in overlaps[:10]:
            print("   block %d addr 0x%06X: 0x%02X -> 0x%02X" % o)

    regs = split_gaps(sparse, GAP)
    print("\nregions (split across gaps >= 0x%X):" % GAP)
    for lo, hi in regs:
        present = sum(1 for a in range(lo, hi + 1) if a in sparse)
        flg = sum(1 for a in range(lo, hi + 1) if a in flagged)
        span = hi - lo + 1
        buf = bytearray([uval]) * span
        for a in range(lo, hi + 1):
            if a in sparse:
                buf[a - lo] = sparse[a]
        outp = os.path.join(out, "%s_%06X.bin" % (stem, lo))
        with open(outp, "wb") as fo:
            fo.write(buf)
        print("  0x%06X..0x%06X  span %7d  present %7d  flagged %5d  -> %s"
              % (lo, hi, span, present, flg, os.path.basename(outp)))

    hexp = os.path.join(out, "%s.clean.hex" % stem)
    nlines = write_ihex(sparse, hexp)
    print("\nclean Intel HEX (.0pa addresses): %s (%d lines)" % (hexp, nlines))

    if relocs:
        cpu = relocate(sparse, relocs)
        cpup = os.path.join(out, "%s.cpu.hex" % stem)
        nlines = write_ihex(cpu, cpup)
        for lo, hi, d in relocs:
            print("relocated   : .0pa 0x%06X..0x%06X -> CPU 0x%06X.." % (lo, hi - 1, lo + d))
        print("CPU-address Intel HEX: %s (%d lines)" % (cpup, nlines))
        print("Ghidra import: analyzeHeadless <proj> -import %s -loader IntelHexLoader "
              "-processor MCORE:BE:32:default" % os.path.basename(cpup))

def relocate(sparse, relocs):
    """Move bytes in [lo, hi) by delta, for each (lo, hi, delta). Bytes outside
    every range keep their address. Refuses to let a move overwrite data."""
    out = {}
    for a, v in sparse.items():
        for lo, hi, d in relocs:
            if lo <= a < hi:
                a += d
                break
        if a in out:
            sys.exit("relocation collision at 0x%06X" % a)
        out[a] = v
    return out

def parse_relocs(args):
    """--reloc LO:HI:DELTA (any int literal, e.g. 0x48000:0x100000:-0x8000)."""
    relocs = []
    while "--reloc" in args:
        i = args.index("--reloc")
        lo, hi, d = (int(x, 0) for x in args[i + 1].split(":"))
        relocs.append((lo, hi, d))
        del args[i:i + 2]
    return relocs

if __name__ == "__main__":
    main()
