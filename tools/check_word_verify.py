#!/usr/bin/env python3
"""Reproduce the E46 MK60 image check words (vector + main range).

The hardware residue at 0xEF8404 is a left-shift 32-bit MISR:

    s = 0
    for each big-endian 32-bit word in [start, end):
        if s bit 31 is set:
            s = ((s << 1) xor 0x00400007) & 0xFFFFFFFF
        else:
            s = (s << 1) & 0xFFFFFFFF
        s ^= word
    s equals the 32-bit word stored at end.

Read-only: this script never writes an image. It only verifies stored
words, or optionally prints the residue of a range.

Usage:

  python3 tools/check_word_verify.py IMAGE [IMAGE ...]
  python3 tools/check_word_verify.py --table 0x3DA0C IMAGE
  python3 tools/check_word_verify.py --misr 0x300 0x3FCE4 IMAGE

stdlib only.
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

MISR_POLY = 0x00400007
MASK32 = 0xFFFFFFFF
VEC_END = 0x2F8
MAIN_START = 0x300
TABLE_SPAN = 10 * 8

# Known range-table bases, used only as a search hint if --table is omitted.
KNOWN_TABLES = (0x3DA0C, 0x3DF34, 0x3DA4C)


def u32be(buf: bytes, off: int) -> int:
    return int.from_bytes(buf[off : off + 4], "big")


def misr32(data: bytes, start: int, end: int) -> int:
    """Left-shift MISR, poly 0x00400007, seed 0, over [start, end)."""
    if start < 0 or end > len(data) or start > end or (end - start) % 4:
        raise ValueError(
            f"range [0x{start:X}, 0x{end:X}) is not a 32-bit word span inside the image"
        )
    s = 0
    i = start
    while i < end:
        w = u32be(data, i)
        if s & 0x80000000:
            s = ((s << 1) ^ MISR_POLY) & MASK32
        else:
            s = (s << 1) & MASK32
        s ^= w
        i += 4
    return s


def parse_table(blob: bytes, table: int) -> list[tuple[int, int, int | None]]:
    if table < 0 or table + TABLE_SPAN > len(blob):
        raise ValueError(f"table 0x{table:X} is outside the image")
    recs = []
    for i in range(10):
        a = table + i * 8
        start, end = u32be(blob, a), u32be(blob, a + 4)
        word = u32be(blob, end) if 0 <= end <= len(blob) - 4 else None
        recs.append((start, end, word))
    return recs


def table_looks_valid(blob: bytes, table: int) -> bool:
    try:
        recs = parse_table(blob, table)
    except ValueError:
        return False
    start0, end0, word0 = recs[0]
    start9, end9, word9 = recs[9]
    if (start0, end0) != (0, VEC_END) or word0 is None:
        return False
    if start9 != MAIN_START or end9 <= MAIN_START or (end9 - start9) % 4 or word9 is None:
        return False
    for start, end, _word in recs[1:9]:
        if (start, end) != (0, 8):
            return False
    return True


def find_table(blob: bytes, hint: int | None) -> int:
    if hint is not None:
        if not table_looks_valid(blob, hint):
            raise SystemExit(f"no well-formed 10-record range table at --table 0x{hint:X}")
        return hint
    for t in KNOWN_TABLES:
        if table_looks_valid(blob, t):
            return t
    # Slow scan of 4-byte-aligned candidates: first record is (0, 0x2F8).
    needle = (0).to_bytes(4, "big") + VEC_END.to_bytes(4, "big")
    start = 0
    while True:
        i = blob.find(needle, start)
        if i < 0:
            break
        if i % 4 == 0 and table_looks_valid(blob, i):
            return i
        start = i + 1
    raise SystemExit("could not find a 10-record range table; pass --table")


def verify_image(path: Path, table_hint: int | None) -> int:
    blob = path.read_bytes()
    table = find_table(blob, table_hint)
    recs = parse_table(blob, table)
    print(f"{path}: {len(blob)} bytes, range table at 0x{table:X}")
    failed = 0
    labels = {0: "vector", 9: "main"}
    for i, (start, end, stored) in enumerate(recs):
        tag = labels.get(i, f"r{i}")
        if (start, end) == (0, 8):
            print(f"  [{i}] {tag:6s}  (0, 8)  unused")
            continue
        computed = misr32(blob, start, end)
        ok = stored is not None and computed == stored
        mark = "OK" if ok else "MISMATCH"
        stored_s = f"0x{stored:08X}" if stored is not None else "unreadable"
        print(
            f"  [{i}] {tag:6s}  [0x{start:X}, 0x{end:X})  "
            f"stored {stored_s}  computed 0x{computed:08X}  {mark}"
        )
        if not ok:
            failed += 1
    return failed


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("images", nargs="*", type=Path, help="raw MK60 dump(s), mapped at address 0")
    p.add_argument("--table", type=lambda s: int(s, 0), help="range-table address (optional)")
    p.add_argument(
        "--misr",
        nargs=2,
        metavar=("START", "END"),
        type=lambda s: int(s, 0),
        help="print the MISR of [START, END) and exit (no stored-word compare)",
    )
    args = p.parse_args(argv)

    if args.misr is not None:
        if len(args.images) != 1:
            p.error("--misr requires exactly one IMAGE")
        blob = args.images[0].read_bytes()
        start, end = args.misr
        print(f"0x{misr32(blob, start, end):08X}")
        return 0

    if not args.images:
        p.error("pass one or more IMAGE files")

    failed = 0
    for path in args.images:
        if not path.is_file():
            raise SystemExit(f"missing image: {path}")
        failed += verify_image(path, args.table)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
