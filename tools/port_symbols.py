#!/usr/bin/env python3
"""Cross-image symbol porting by relocation-invariant function signature.

Reads two ExportSigs.java outputs (one line per function:
    entry<TAB>nInstr<TAB>sigHex<TAB>name
where sigHex is an FNV-1a over the masked instruction halfwords -- identical for
the same source compiled at a different address) and emits, for the TARGET image,
a `addr<TAB>name` table of names carried over from the (named) SOURCE image, for
`ApplyPortedNames.java` to apply.

A name is ported to a target function only when the match is UNAMBIGUOUS:
  - the source function carries a real (non-FUN_/LAB_/thunk) name,
  - its signature is unique among all named source functions,
  - the same signature occurs exactly once in the target,
  - both bodies have at least --min-instr instructions (short functions collide:
    many 3-5 instruction leaves hash alike and would mis-port).

Everything else is the MANUAL TAIL, reported (and, with --tail, written) so a human
can resolve it: `ambiguous` = signature seen more than once on a side; `nomatch` =
target function with no named source twin. Anchor-propagation (naming a target fn
from its already-named call neighbours) is the usual way to chip away at `nomatch`;
it is not automated here.

Credit: the function/data symbol names this carries over ultimately originate in cmg5461's
documented analysis (7846816A_symbols.md et al., https://github.com/cmg5461/mk60e5-firmware).
The signature-matching method is original; the names are theirs.

Run isolated:
  python3 -I tools/port_symbols.py SRC.sigs DST.sigs -o ported.tsv [--min-instr 8] [--tail tail.txt]
stdlib only; read-only (writes only the output files you name).
"""
import argparse, collections, sys


def is_generic(name):
    return (not name or name.startswith("FUN_") or name.startswith("LAB_")
            or name.startswith("SUB_") or name.startswith("thunk")
            or name.startswith("DAT_") or name.startswith("tbl_"))


def load(path):
    rows = []
    with open(path) as fh:
        for ln in fh:
            p = ln.rstrip("\n").split("\t")
            if len(p) < 4:
                continue
            rows.append((p[0].strip(), int(p[1]), p[2].strip(), p[3].strip()))
    return rows


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("src", help="ExportSigs .sigs from the NAMED reference image")
    ap.add_argument("dst", help="ExportSigs .sigs from the TARGET image")
    ap.add_argument("-o", "--out", required=True, help="output addr<TAB>name table")
    ap.add_argument("--min-instr", type=int, default=8,
                    help="skip functions with fewer instructions (collision guard; default 8)")
    ap.add_argument("--tail", help="optional file: write the ambiguous/no-match report here")
    a = ap.parse_args()

    src = load(a.src)
    dst = load(a.dst)

    # source: signature -> set of names, but only named functions above the size floor
    src_named = collections.defaultdict(set)
    for addr, n, sig, name in src:
        if n >= a.min_instr and not is_generic(name):
            src_named[sig].add(name)

    # target: signature -> list of (addr, name)
    dst_by_sig = collections.defaultdict(list)
    for addr, n, sig, name in dst:
        if n >= a.min_instr:
            dst_by_sig[sig].append((addr, name))

    ported = []          # (dst_addr, src_name)
    ambiguous = []       # (reason, sig, detail)
    already = []         # target already has a real name
    for sig, names in src_named.items():
        tgts = dst_by_sig.get(sig, [])
        if len(names) > 1:
            ambiguous.append(("src-ambiguous", sig, ",".join(sorted(names))))
            continue
        if not tgts:
            continue      # source-only (variant-specific to the reference)
        if len(tgts) > 1:
            ambiguous.append(("dst-ambiguous", sig,
                              "%s -> %s" % (next(iter(names)),
                                            ",".join(t[0] for t in tgts))))
            continue
        (daddr, dname), = tgts
        sname = next(iter(names))
        if not is_generic(dname):
            already.append((daddr, dname, sname))
            continue
        ported.append((daddr, sname))

    ported.sort()
    with open(a.out, "w") as fh:
        for addr, name in ported:
            fh.write("%s\t%s\n" % (addr, name))

    # the manual tail: target functions nobody named
    named_targets = {d[0] for d in ported} | {d[0] for d in already}
    nomatch = [(addr, n) for addr, n, sig, name in dst
               if n >= a.min_instr and is_generic(name) and addr not in named_targets]

    print("port_symbols: ported=%d already-named=%d src-ambiguous=%d dst-ambiguous=%d "
          "unmatched-target=%d" % (len(ported), len(already),
                                   sum(1 for x in ambiguous if x[0] == "src-ambiguous"),
                                   sum(1 for x in ambiguous if x[0] == "dst-ambiguous"),
                                   len(nomatch)))
    print("wrote %d names -> %s" % (len(ported), a.out))
    if a.tail:
        with open(a.tail, "w") as fh:
            fh.write("# MANUAL TAIL -- resolve by hand (anchor-propagation / inspection)\n")
            fh.write("## ambiguous (%d): one signature, several candidates\n" % len(ambiguous))
            for reason, sig, detail in sorted(ambiguous):
                fh.write("%s\t%s\t%s\n" % (reason, sig, detail))
            fh.write("\n## unmatched target functions (%d): no named source twin\n" % len(nomatch))
            for addr, n in sorted(nomatch):
                fh.write("%s\t%d instr\n" % (addr, n))
        print("wrote manual tail -> %s" % a.tail)


if __name__ == "__main__":
    main()
