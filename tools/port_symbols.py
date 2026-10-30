#!/usr/bin/env python3
"""Cross-image symbol porting by relocation-invariant function signature.

Reads two ExportSigs.java outputs (one line per function:
    entry<TAB>nInstr<TAB>sigHex<TAB>name<TAB>edges
where edges are the body's call targets / tail jumps / pointer tables in order, and sigHex is an FNV-1a over the masked instruction halfwords -- identical for
the same source compiled at a different address) and emits, for the TARGET image,
a `addr<TAB>name<TAB>method` table of names carried over from the (named) SOURCE image, for
`ApplyPortedNames.java` to apply.

A name is ported to a target function only when the match is UNAMBIGUOUS:
  - the source function carries a real (non-FUN_/LAB_/thunk) name,
  - its signature is unique among all named source functions,
  - the same signature occurs exactly once in the target,
  - both bodies have at least --min-instr instructions (short functions collide:
    many 3-5 instruction leaves hash alike and would mis-port).

Then PROPAGATION, to a fixpoint, from every matched pair (a signature match, or a
target that already carries the source's name): a matched caller's call targets
correspond position by position, and so do the slots of a pointer table it loads --
the compiler emits the same code for the same source, so the k-th call of the same
function is the same callee in both images.  This is a property of compiled code,
not of the architecture or of any particular image.  It is what reaches the short
helpers the signature floor refuses (a 2-instruction `return r3` has no unique
signature, but "the only callee of ccp_cmd_SET_MTA" does).  Guards: the two bodies
must have the same edge sequence (same kinds, same count), a table pair must have
the same length, a name is applied only to a generic (FUN_) target, a target that
two anchors name differently is a conflict and left alone, and a callee pair whose
signatures differ is accepted only when their sizes are within 25% (a variant that
changed the callee's body).  Table bases with a real source name are ported as labels.

Everything else is the MANUAL TAIL, reported (and, with --tail, written) so a human
can resolve it: `ambiguous` = signature seen more than once on a side; `nomatch` =
target function with no named source twin; `conflict` = propagation disagreed.

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
            or name.startswith("DAT_") or name.startswith("tbl_")
            or name.startswith("PTR_") or name.startswith("switchD_"))


def load(path):
    """Returns (function rows, {table base: label}) -- the sigs file carries function lines and,
    after them, `L<TAB>base<TAB>name` lines naming the pointer tables the edges refer to."""
    rows = []
    tables = {}
    with open(path) as fh:
        for ln in fh:
            p = ln.rstrip("\n").split("\t")
            if p and p[0] == "L" and len(p) >= 3:
                tables[p[1].strip()] = p[2].strip()
                continue
            if len(p) < 4:
                continue
            rows.append((p[0].strip(), int(p[1]), p[2].strip(), p[3].strip(),
                         parse_edges(p[4] if len(p) > 4 else "")))
    return rows, tables


def parse_edges(text):
    """'c:07aa36;t:07a720=07a932,07a7e0' -> [('c','07aa36',None), ('t','07a720',['07a932','07a7e0'])]"""
    edges = []
    for item in text.split(";"):
        if not item:
            continue
        kind, rest = item.split(":", 1)
        if kind == "t":
            base, slots = rest.split("=", 1)
            edges.append((kind, base, slots.split(",")))
        else:
            edges.append((kind, rest, None))
    return edges


def propagate(src_by_addr, dst_by_addr, pairs, taken, table_names):
    """Fixpoint anchor propagation. pairs: {src_addr: dst_addr} of matched functions;
    table_names: {src table base: its label} (from the sigs file's trailing L lines).
    Returns (new_names {dst_addr: (name, method)}, labels {dst_addr: name}, conflicts [...])."""
    names = {}         # dst_addr -> (name, method, src_addr)
    labels = {}        # dst table base -> name
    conflicts = []
    todo = list(pairs.items())
    seen = set(todo)
    while todo:
        s_addr, d_addr = todo.pop()
        s_edges = src_by_addr[s_addr][4]
        d_edges = dst_by_addr[d_addr][4]
        if len(s_edges) != len(d_edges) or any(a[0] != b[0] for a, b in zip(s_edges, d_edges)):
            continue      # body differs structurally: not an anchor
        for (kind, s_t, s_slots), (_, d_t, d_slots) in zip(s_edges, d_edges):
            if kind == "t":
                if len(s_slots) != len(d_slots):
                    continue
                if not is_generic(table_names.get(s_t, "")):
                    labels.setdefault(d_t, table_names[s_t])
                cands = list(zip(s_slots, d_slots))
            else:
                cands = [(s_t, d_t)]
            for s_c, d_c in cands:
                if s_c not in src_by_addr or d_c not in dst_by_addr:
                    continue
                s_row, d_row = src_by_addr[s_c], dst_by_addr[d_c]
                if d_c in taken or not is_generic(d_row[3]):
                    continue          # target already named (by a signature match or for real)
                if is_generic(s_row[3]):
                    continue
                if s_row[2] != d_row[2]:                      # signature differs: size guard
                    n_s, n_d = s_row[1], d_row[1]
                    if not (0.75 * n_s <= n_d <= 1.25 * n_s):
                        continue
                method = "table-slot" if kind == "t" else "call-site"
                prev = names.get(d_c)
                if prev is not None and prev[0] != s_row[3]:
                    conflicts.append((d_c, prev[0], s_row[3]))
                    continue
                if prev is None:
                    names[d_c] = (s_row[3], method, s_c)
                    key = (s_c, d_c)
                    if key not in seen:
                        seen.add(key); todo.append(key)
    for d_c, _, _ in conflicts:
        names.pop(d_c, None)
    return names, labels, conflicts


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

    src, src_tables = load(a.src)
    dst, _ = load(a.dst)

    # source: signature -> set of names, but only named functions above the size floor
    src_by_addr = {r[0]: r for r in src}
    dst_by_addr = {r[0]: r for r in dst}

    src_named = collections.defaultdict(set)
    for addr, n, sig, name, _ in src:
        if n >= a.min_instr and not is_generic(name):
            src_named[sig].add(name)

    # target: signature -> list of (addr, name)
    dst_by_sig = collections.defaultdict(list)
    for addr, n, sig, name, _ in dst:
        if n >= a.min_instr:
            dst_by_sig[sig].append((addr, name))

    ported = []          # (dst_addr, src_name, method)
    ambiguous = []       # (reason, sig, detail)
    already = []         # target already has a real name
    pairs = {}           # src_addr -> dst_addr, every matched pair (anchors for propagation)
    src_addr_of_name = {name: addr for addr, n, sig, name, _ in src if not is_generic(name)}
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
        if sname in src_addr_of_name:
            pairs[src_addr_of_name[sname]] = daddr
        if not is_generic(dname):
            already.append((daddr, dname, sname))
            continue
        ported.append((daddr, sname, "signature"))

    # anchors also include every target that already carries a source name (an earlier port)
    for addr, n, sig, name, _ in dst:
        if not is_generic(name) and name in src_addr_of_name:
            pairs.setdefault(src_addr_of_name[name], addr)

    taken = {d[0] for d in ported} | {d[0] for d in already}
    prop, labels, conflicts = propagate(src_by_addr, dst_by_addr, pairs, taken, src_tables)
    # one source name must not land on two different targets
    by_name = collections.defaultdict(set)
    for daddr, (name, method, saddr) in prop.items():
        by_name[name].add(daddr)
    for name, addrs in by_name.items():
        if len(addrs) > 1:
            for daddr in addrs:
                conflicts.append((daddr, name, "same name on %d targets" % len(addrs)))
                prop.pop(daddr, None)
    for daddr, (name, method, saddr) in prop.items():
        ported.append((daddr, name, method))
    for base, name in sorted(labels.items()):
        ported.append((base, name, "table-label"))

    ported.sort()
    with open(a.out, "w") as fh:
        for addr, name, method in ported:
            fh.write("%s\t%s\t%s\n" % (addr, name, method))

    # the manual tail: target functions nobody named
    named_targets = {d[0] for d in ported} | {d[0] for d in already}
    nomatch = [(addr, n) for addr, n, sig, name, _ in dst
               if n >= a.min_instr and is_generic(name) and addr not in named_targets]

    n_call = sum(1 for p in ported if p[2] == "call-site")
    n_slot = sum(1 for p in ported if p[2] == "table-slot")
    n_lab = sum(1 for p in ported if p[2] == "table-label")
    print("port_symbols: ported=%d (signature=%d call-site=%d table-slot=%d table-label=%d) "
          "already-named=%d src-ambiguous=%d dst-ambiguous=%d conflicts=%d unmatched-target=%d"
          % (len(ported), len(ported) - n_call - n_slot - n_lab, n_call, n_slot, n_lab,
             len(already),
             sum(1 for x in ambiguous if x[0] == "src-ambiguous"),
             sum(1 for x in ambiguous if x[0] == "dst-ambiguous"),
             len(conflicts), len(nomatch)))
    print("wrote %d names -> %s" % (len(ported), a.out))
    if a.tail:
        with open(a.tail, "w") as fh:
            fh.write("# MANUAL TAIL -- resolve by hand (anchor-propagation / inspection)\n")
            fh.write("## ambiguous (%d): one signature, several candidates\n" % len(ambiguous))
            for reason, sig, detail in sorted(ambiguous):
                fh.write("%s\t%s\t%s\n" % (reason, sig, detail))
            fh.write("\n## propagation conflicts (%d): two anchors disagree, nothing applied\n" % len(conflicts))
            for daddr, n1, n2 in sorted(conflicts):
                fh.write("conflict\t%s\t%s | %s\n" % (daddr, n1, n2))
            fh.write("\n## unmatched target functions (%d): no named source twin\n" % len(nomatch))
            for addr, n in sorted(nomatch):
                fh.write("%s\t%d instr\n" % (addr, n))
        print("wrote manual tail -> %s" % a.tail)


if __name__ == "__main__":
    main()
