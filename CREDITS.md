# Credits & third-party components

This repo vendors one third-party component (below). The driver scripts, the Ghidra analysis
scripts, `parse_0pa.py`, `find_boundary.py`, `port_symbols.py`, the patches and compiler spec, and
the documentation are our own code — but the *understanding* they encode leans heavily on prior
work, acknowledged next.

## Acknowledgment — cmg5461's `mk60e5-firmware`

<https://github.com/cmg5461/mk60e5-firmware>

This entire process was developed against, and cross-validated with, cmg5461's MK60E5 analysis.
Even where no code is copied, the following learnings come from that repo and made this possible:

- **`mcore_dis.py`** — an independent linear-sweep M·CORE disassembler, and the reference listing
  `7846816A_main.lst`. We cross-checked our Ghidra decode against it byte-for-byte, and
  `find_boundary.py`'s prologue-seed boundary method is modeled on its function-seeding.
- **`7846816A_symbols.md` / `7846411A_symbols.md`** — documented function/data symbol names and the
  memory map. The `KNOWN` table-base names in `DataSectionClean.java`, and the names
  `port_symbols.py` carries between images, ultimately originate here.
- **`bmy_resign.py`** and the analysis docs (ABS algorithm, CAN maps, flash plan) — the BMY RSA-512
  signature + MISR self-test scheme and the broader firmware understanding.

If you build on this repo, please credit cmg5461's work as well.

## Third-party components

## CUB3D M·CORE Ghidra module — `tools/ghidra-mcore/`

- Upstream: <https://github.com/CUB3D/ghidra-mcore>
- License: **Apache License 2.0** (see `tools/ghidra-mcore/LICENSE`)
- Vendored pristine. Local fixes are **not** edited into it; they are applied at install time from
  `tools/patches/mcore.patch` (+ `tools/patches/mcore.cspec`) by `tools/install_mcore.sh`, so the
  upstream source stays identifiable and the patch stays reviewable
  (`tools/patches/make_mcore_patch.py` documents and regenerates it).

## Boundary detection

`tools/find_boundary.py` locates the code/data boundary and sanity-checks the architecture by
scanning for the M·CORE function prologue (`subi r0,N; stm rX-r15,(r0)`). The prologue encoding is
an architectural fact (consistent with GNU binutils `opcodes/mcore-opc.h`); the tool is original and
carries no third-party code.

## Firmware

No BMW/ATE firmware is included. The MK60E5 images are copyrighted and are not redistributed.
