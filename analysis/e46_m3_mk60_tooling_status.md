# E46 M3 MK60 — M·CORE tooling and 818.3 coverage

2026-11-20. A short status note: what Motorola M·CORE (MMC2xxx) tooling exists, and what a Ghidra recovery of the 818.3 image actually covers. Companion: `e46_m3_mk60_mtrack_mode4.md`.

## What exists

| Tool | Where | Works on this 818.3 image? |
|---|---|---|
| Ghidra SLEIGH module | [CUB3D/ghidra-mcore](https://github.com/CUB3D/ghidra-mcore) (Apache-2.0), vendored in [heinzboehmer/MK60-M-Core-Disassembly-Process](https://github.com/heinzboehmer/MK60-M-Core-Disassembly-Process) as `tools/ghidra-mcore`, installed by `tools/install_mcore.sh` with `tools/patches/mcore.patch`. Language id `MCORE:BE:32:default`. Upstream: [NSA/ghidra#9270](https://github.com/NationalSecurityAgency/ghidra/pull/9270) (not in a released Ghidra as of this note). | **Yes.** A recovery of this image with that module and Heinz’s `tools/ghidra/mcore_import.sh`, `mcore_recover.sh` and `mcore_verify.sh` (repo commit `6ea098d`) produced **1585** functions and verified HEALTHY. |
| GNU binutils `mcore` / `mcore-elf-objdump` | `opcodes/mcore-dis.c` and `mcore-opc.h`, still in binutils 2.45, configured with `--target=mcore-elf`. | The opcode map matches the listings used here. A target-specific `mcore-elf-objdump` was not installed on the analysis host (the distro package is x86_64-only), so coverage was not measured with objdump itself. |
| IDA processor module | [MotoFanRu/M-CORE_IDA-Pro](https://github.com/MotoFanRu/M-CORE_IDA-Pro) (IDA 8.3 / 9.0) and [MotoFanRu/IDA_Decompiler_M-CORE](https://github.com/MotoFanRu/IDA_Decompiler_M-CORE). | Not installed, not tested on this image. |
| Binary Ninja | Native M·CORE; 6.0 “Krypton” fixed big-endian recognition ([release notes](https://binary.ninja/2026/09/03/binary-ninja-6.0-krypton.html)). | Not installed, not tested on this image. |

The nam3forum thread [Flashing the MK60 with ZCP software](https://nam3forum.com/forums/forum/special-interests/coding-tuning/172815-flashing-the-mk60-with-zcp-software) (pages 5–6) describes Ghidra plus the patched CUB3D module on MK60e5 and points at Heinz’s repo. No other public SLEIGH / IDA / BN module aimed at this ECU family turned up.

## Coverage on the 818.3 dump (measured)

The dump is **262 144 bytes** (`0x0`–`0x3FFFF`). The window `0x0`–`0x5FFFF` is 384 KiB; **`0x40000`–`0x5FFFF` is not in this file** (128 KiB missing). RAM on the ECU is at `0x400000`, not `0x40000`. The Ghidra recovery of this image places code at `0x200`–`0x3C910` and data at `0x3CCF6`–`0x40000`.

How measured: a 2-byte linear sweep with a 16-bit M·CORE disassembler that follows the same opcode map as GNU `opcodes/mcore-dis.c`; function entries, bounds and instruction counts from a Ghidra export of function signatures for this image (1585 functions); names ported from the ZCP image. A naive sweep treats in-line literal pools as failed instructions (they decode as `.half`, often the two halves of a `0x0040xxxx` pointer), so function-guided figures are the ones to use.

| Measurement | Result |
|---|---|
| Functions recovered (Ghidra, verify HEALTHY, Heinz pipeline at commit `6ea098d`) | **1585** |
| Exact relocation-masked signature match to the 817.3 ZCP (from the existing port; not re-counted for this note) | **1527 / 1585 (96 %)** |
| Code-window halfwords inside some function’s `entry … next-entry` span | **123 784 / 123 784 (100 %)** of `0x200`–`0x3C910` |
| Code-window halfwords inside signature bodies (`nins × 2`) | **106 164 / 123 784 (85.77 %)**. The rest is inter-function padding, literal pools, and embedded tables. |
| Inside those signature bodies, halfwords that decode as a real instruction | **104 535 / 106 745 (97.93 %)**; **2 210** `.half` (pools). **161 / 1585** functions have at least one `.half` in the `nins × 2` span. |
| Naive linear sweep of the code window (pools counted as failures) | **117 494 / 123 784 (94.92 %)** |
| Naive linear sweep of the whole 256 KiB file | **123 198 / 131 072 (93.99 %)** |
| Non-generic names in the Ghidra recovery | **58 / 1585 (3.7 %)**: 50 names ported from the ZCP image, plus `reset` and seven thunks. |

“Cleanly into functions” means the HEALTHY Ghidra recovery (1585 functions; 85.77 % of the code window inside signature bodies). It is not a claim that most of the image is understood.

Functions and areas already named or reduced in the notes and in Heinz’s public docs, beyond those 58 names, include the five driver-mode predicates, `driver_mode_get` (`0x21AE8`), the yaw-bar routine `0xE844`, `r_ref` (`0x29606`), `r_est` (`0x26258`), `interp_linear_xy` (`0x181F8`), the traction entry `0x3B080`, `tcs_driveslip_curve` (`0x3518C`, a name ported from the ZCP image), and the eleven mode-4 consumers listed in `e46_m3_mk60_mtrack_mode4.md`. Heinz `zcp_m_track_mode.md` documents the ZCP / non-M twins of all eleven. That is still a small, documented subset of the 1585 recovered functions.
