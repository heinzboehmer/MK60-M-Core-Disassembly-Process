---
name: mcore-image-recovery
description: >
  Recover a complete, de-noised, labeled Ghidra disassembly from a fresh BMW MK60E5
  (Motorola/Freescale M·CORE, big-endian) ABS/DSC firmware image — .0pa or .cpu.hex.
  Use when importing a new MK60E5 / DSCMxx image (DSCM90, DSCM80/1M, 335i DSC90, …) and
  a raw Ghidra import is full of red "bad instruction" errors, truncated functions, raw
  `??` data and spurious functions in the calibration section.
---

# M·CORE MK60E5 image recovery

The full process lives at the repo root in **[`PROCESS.md`](../../../PROCESS.md)** — read it and
follow it. One-time toolchain setup (build the patched `MCORE:BE:32:default` SLEIGH) is in
**[`SETUP.md`](../../../SETUP.md)**; do it first.

In short, from the repo root (bring your own image — none is included):

```bash
# 1. arch-check + parse + import + map RAM/MMIO
tools/ghidra/mcore_import.sh <image.0pa> <proj_dir> <name>

# 2. recover + finish (GUI closed). Bounds are detected automatically; repeats until converged.
tools/ghidra/mcore_recover.sh <proj_dir> <name> <image.cpu.hex>

# 3. verify (read-only, GUI may be open). Exit 0 == clean; the summary is the report.
tools/ghidra/mcore_verify.sh <proj_dir> <name> <image.cpu.hex>

# 4. (optional) port names from an already-named reference project
tools/ghidra/mcore_port.sh <src_proj> <src_name> <src.cpu.hex> <proj_dir> <name> <image.cpu.hex>
```

`PROCESS.md` has the reasoning (why a raw import fails, how the code/data boundary is found
programmatically), the per-script reference, verification, and the gotchas. Don't skip it.
