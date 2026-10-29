# MK60 M·CORE disassembly process

A self-contained, repeatable process for turning a **BMW MK60E5 ABS/DSC firmware image** into a
complete, de-noised, labeled disassembly in Ghidra. The MK60E5 ECU runs a Motorola/Freescale
**M·CORE** core (big-endian, fixed 16-bit instructions), which stock Ghidra doesn't understand and
which defeats naïve auto-analysis — so a raw import is a mess of red errors, truncated functions,
raw `??` data, and thousands of fake functions in the calibration tables. This repo fixes all of
that with three driver scripts; the address bounds are worked out programmatically, so it handles
an **arbitrary** MK60E5 image, not a hardcoded one.

It is written to be **handed to a context-less agent** (Claude or otherwise): the full method is
in **[PROCESS.md](PROCESS.md)** at the repo root, and a Claude skill
([`.claude/skills/mcore-image-recovery/SKILL.md`](.claude/skills/mcore-image-recovery/SKILL.md))
points to it so Claude Code auto-discovers it.

## Quickstart

1. **Setup (once):** follow [SETUP.md](SETUP.md) — install Ghidra + a JDK, then
   `tools/install_mcore.sh` to build the patched `MCORE:BE:32:default` language.
2. **Recover an image** (bring your own `.0pa` — none is included):

   ```bash
   # 1. arch-check + parse + import + map RAM/MMIO
   tools/ghidra/mcore_import.sh /path/to/<part>.0pa projects/MK60 MK60

   # 2. recover + finish (GUI closed). Bounds are detected automatically.
   tools/ghidra/mcore_recover.sh projects/MK60 MK60 /path/to/<part>.cpu.hex

   # 3. (optional) carry names over from an already-named reference project
   tools/ghidra/mcore_port.sh projects/REF REF ref.cpu.hex projects/MK60 MK60 <part>.cpu.hex
   ```

   The reasoning, per-script reference, verification, and gotchas are in **[PROCESS.md](PROCESS.md)**.

## Layout

```
PROCESS.md                                      the process, step by step (canonical)
SETUP.md                                        one-time toolchain setup
LICENSE                                         MIT (your code); vendored module stays Apache-2.0
CREDITS.md                                      third-party components & licenses
.claude/skills/mcore-image-recovery/SKILL.md    thin pointer to PROCESS.md (Claude auto-discovery)
tools/
  install_mcore.sh        build the patched M·CORE SLEIGH into your Ghidra
  parse_0pa.py            BMW .0pa  ->  CPU-address Intel HEX + region .bin files
  find_boundary.py        prologue-scan: arch check + programmatic code/data boundary
  port_symbols.py         cross-image symbol porting by function signature
  patches/                mcore.patch, mcore.cspec, make_mcore_patch.py
  ghidra-mcore/           vendored CUB3D M·CORE Ghidra module (Apache-2.0)
  ghidra/
    mcore_import.sh  mcore_recover.sh  mcore_port.sh  dump.sh
    scripts/*.java        the Ghidra analysis scripts the drivers run
```

## Scope

- **Read-only** reverse engineering of firmware you are authorized to analyze, as a reference.
- **No firmware images** are included or tracked — they are BMW/ATE copyrighted. See [SETUP.md](SETUP.md).
- Developed against and cross-validated with **cmg5461's [`mk60e5-firmware`](https://github.com/cmg5461/mk60e5-firmware)** —
  the M·CORE reference decoder, symbol/memory maps and signature analysis this process rests on.
- The code here is our own (except the vendored CUB3D Ghidra module) and is **MIT-licensed**
  ([LICENSE](LICENSE)); the vendored module stays Apache-2.0. See [CREDITS.md](CREDITS.md) for full attribution.
