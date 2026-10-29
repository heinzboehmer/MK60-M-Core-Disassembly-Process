# MK60E5 M·CORE image recovery — the process

How to turn a fresh BMW MK60E5 ABS/DSC firmware image (`.0pa` or `.cpu.hex`) into a complete,
de-noised, labeled disassembly in Ghidra. This is the canonical description of the process; the
Claude skill at [`.claude/skills/mcore-image-recovery/SKILL.md`](.claude/skills/mcore-image-recovery/SKILL.md)
is a thin pointer to this file. Do the one-time toolchain setup in [`SETUP.md`](SETUP.md) first.

## Why a raw import isn't enough

The MK60E5 ECU runs a Motorola/Freescale **M·CORE** core (big-endian, fixed 16-bit instructions).
Stock Ghidra has no M·CORE; `SETUP.md` builds a patched one. Even with it, M·CORE's SLEIGH models
every branch (`bt/bf/br/bsr`) as a *computed* jump, so Ghidra's auto-analysis:

- leaves branch-/jump-/pointer-reached code as raw `??` bytes,
- falls through tail jumps into literal pools and flags them red ("bad instruction"),
- and **mis-decodes the data/calibration section into thousands of spurious functions**
  (bytes of a lookup curve that happen to decode as valid M·CORE).

The scripts here fix all of that. The boundaries that bound the work are found **programmatically**
(no hand-entered per-image addresses) — see Step 2.

> **No firmware is included** (BMW/ATE copyright). Bring your own `.0pa` or `.cpu.hex`. `.gitignore`
> already keeps images, `.bin`s, `.cpu.hex` and Ghidra `projects/` out of version control.

## Pipeline at a glance

```
 (1) mcore_import.sh    arch-check  →  parse .0pa --reloc  →  import MCORE:BE:32  →  CreateMemBlocks
 (2) mcore_recover.sh   find_boundary.py (auto codeHI/dataHI)
                        →  FixLiteralPools
                        →  [RecoverRefCode · RecoverCodeRuns · RecoverFlowCode ·
                            FixJumpTables · FixLiteralPools · FixResidual · RetraceBodies ·
                            FunctionizeOrphans] ×N
                        →  DataSectionClean auto  (auto-pins dataLO = last real fn end → <prog>.bounds)
                        →  FixStorage2 → ClassifyData · ApplyDataTypes → ClearAnalysisBookmarks
                        →  ScanMisdisasm · CountErr
                        ] × rounds, until the last recovery pass adds nothing
 (3) mcore_port.sh      (optional) carry names over from an already-named reference project
 (V) mcore_verify.sh    verify + quantify the memory map (read-only, GUI may be open)
```

Run everything from the repo root. Each driver prints a one-line usage if under-argued. In the
examples `<proj_dir>` is any directory you pick for the Ghidra project and `<name>` its project name.

Close the Ghidra GUI for any step that writes back (import and recover) — Ghidra takes an exclusive
project lock; a step that prints `Unable to lock` means the GUI is still open.

Every driver exits non-zero when its headless session fails or its result isn't clean (see each
script's header), and keeps the full Ghidra log under `<proj_dir>/` — so they are safe to chain and
a crash can't masquerade as success.

---

## Step 1 — Parse, arch-check & import

```bash
tools/ghidra/mcore_import.sh <image.0pa | image.cpu.hex> <proj_dir> <name>
```

- **`parse_0pa.py --reloc 0x48000:0x100000:-0x8000`** — the app is stored 0x8000 above where it
  runs (VBR=0x40000 set by crt0); this writes `<stem>.cpu.hex` at **CPU runtime addresses** next to
  the `.0pa`. *Analyzing at .0pa addresses makes every absolute call land mid-function — don't skip
  the reloc.* If you already have a `.cpu.hex`, pass it and this is skipped.
- **Arch sanity check** (`find_boundary.py`, non-gating) runs *before* the slow import and warns if
  the image doesn't look like M·CORE (see Step 2) — so you don't burn an analysis run on, say, an
  ARM or PowerPC image fed in by mistake.
- Import as `MCORE:BE:32:default` with `IntelHexLoader` + auto-analyze.
- **`CreateMemBlocks.java`** adds the RAM/MMIO blocks the flash image doesn't carry (so RAM and
  peripheral refs resolve). These ranges are the same across all MK60E5 variants (same SoC):
  RAM `0x400000–0x423FFF`; MMIO `0xD50000–0xDE0FFF`, `0xE00000–0xE10FFF`, `0xF50000–0xFF0FFF`,
  `0xFFF00000–0xFFF84FFF` (volatile).

The program's name inside the project is the `.cpu.hex` basename (e.g. `7846816A.cpu.hex`).

Auto-analysis logs a handful of `Failed to create function at … since its body contains referring
thunk` errors on every image (tail-call stubs it mistakes for thunks). The driver reports them as a
count and Step 2's `RetraceBodies` fixes them — they are not a problem with the import.

After this step the project is **red** (literal-pool errors) and the calibration section is full of
spurious functions. Expected — Step 2 clears it.

---

## Step 2 — Recover & finish (bounds found automatically)

```bash
tools/ghidra/mcore_recover.sh <proj_dir> <name> <cpu_hex_path> [passes]
```

One headless session runs the recovery loop (`passes` iterations, default 6) and then the finish
chain. The finish chain can expose seeds the loop never saw (a newly typed pointer, a cleaned table
edge), so the driver repeats that session — up to `ROUNDS` (env, default 4) — **until the last pass
of a round creates no functions and reverts nothing**. It prints `round N: lastPassWork=…` after
each round and `DONE: converged …` at the end; a `NOT converged` message means raise `ROUNDS` or
`passes` (or just re-run — it resumes).

### How the boundaries are found — no hardcoded addresses

The work is bounded by the code region `[codeLO, codeHI)` and the data section `[dataLO, dataHI)`,
where `codeHI == dataLO`. The driver determines them for *any* image:

- **`codeLO`** is always `0x42000` (below it, `0x40000–0x41FFF` is the VBR table + calibration
  directory + OSEK init stubs — handled separately by `OsInit.java` if needed).
- **`find_boundary.py`** reads the `.cpu.hex` and scans for the M·CORE frame prologue —
  `subi r0,N` (`h & 0xFE0F == 0x2400`) immediately followed by `stm rX-r15,(r0)`
  (`h & 0xFFF0 == 0x0070`). This pair is dense and continuous through code and essentially never
  occurs in the calibration data above it (two constrained big-endian halfwords in a row,
  p ≈ 1e-6 per position). It reports:
  - **`dataHI`** = end of the contiguous image segment that contains `codeLO` (the stray low-/high-
    memory config blocks are excluded), and
  - **`codeHI`** = a recovery ceiling a few KB above the highest prologue (function bodies/pools run
    above their entry). It also prints a prologue histogram and an **arch verdict** — too few
    prologues ⇒ "this isn't M·CORE".
- **`DataSectionClean auto`** then pins the **exact `dataLO`** Ghidra-side: the end of the last
  function that *starts with the prologue*. Because spurious data-region functions never start with
  the prologue, that's the true code/data boundary — and the clean step additionally **never deletes
  or clears a prologue-started function**, so a slightly-off estimate can't damage real code.

- The pinned `dataLO` is written to **`<proj_dir>/<prog>.bounds`** (`CODE_LO`/`DATA_LO`/`DATA_HI`).
  Every later round and re-run uses it as the recovery ceiling (`CODE_HI`), because `find_boundary`'s
  `codeHI` is only an estimate a few KB *above* the real boundary: without the sidecar each run
  re-disassembles that gap and `DataSectionClean` deletes it again (tens of functions, thousands of
  instructions of churn per run). `mcore_verify.sh` reads the same file. Delete it, or set `CODE_HI`,
  to start from the estimate again.

Override any bound via env (hex, no `0x`): `CODE_LO`, `CODE_HI`, `DATA_HI`; set `DATA_LO=<hex>` to
pin it yourself instead of `auto`; `FORCE=1` proceeds despite a non-M·CORE verdict. You can also run
`python3 -I tools/find_boundary.py <cpu_hex>` on its own to inspect the histogram before recovering.

### The recovery loop (over `[codeLO, codeHI)`, ×`passes`)

| script | what it recovers |
|---|---|
| `RecoverRefCode` | code reached through function pointers whose refs were never materialized; also seeds the VBR exception-vector table (`0x40000`, 128 entries — bit 0 of an entry is the alternate-register-file flag, not an address bit) |
| `RecoverCodeRuns` | undefined runs that classify as CODE (clean decode to a terminator, not a table/padding) |
| `RecoverFlowCode` | branch-only-reached blocks: computes the target of a `bt/bf/br/bsr` with empty `getFlows()` from the encoding, and the pool-slot pointer of an indirect `jmpi/jsri` — and **records it as a flow reference**. Ghidra only materializes computed-flow references during auto-analysis, so every branch in script-disassembled code had none: body tracing, `RetraceBodies` and the decompiler all stopped at the first `bt/bf` of such a function. The reference is what lets the rest of the loop (and the decompiler) follow it |
| `FixJumpTables` | reverts absolute-pointer jump tables mis-disassembled as code; re-disassembles genuine jump-table targets incl. `jmp rN` switches |
| `FixLiteralPools` | defines the PC-relative literal pools and clears the pool-as-code red; guarded not to re-type a non-terminator fall-through target (that's truncated code, not a pool) |
| `FixResidual` | mops up padding (`0x0000`/`0xFFFF`) and pool-edge spurious decodes |
| `RetraceBodies` | drops spurious continuation functions and re-traces truncated function bodies — truncated by fall-through *or* by a branch: M·CORE branches are computed jumps, so a body traced before `RecoverFlowCode` materialized their targets stops at the first `bt/bf/br`; without this rule ~20% of the code region ends up as instructions no function owns |
| `FunctionizeOrphans` | gives the remaining owner-less code a function: runs referenced from data (function pointers, vectors, jump tables), runs referenced from a function that tracing could not absorb, prologue-started runs, and *displaced entries* — a callback whose address is taken and passed as a parameter gets its first bytes auto-typed `uint8/16/32_t` by `ClassifyData`, so the run starts a few bytes after the real entry; the item is cleared and the function created at its address when the code decodes cleanly from there. Unreferenced speculative decodes are left alone and reported by `mcore_verify.sh` |

### Then the data section + finish (once)

| script | what it does |
|---|---|
| `DataSectionClean auto <dataHI>` | pins `dataLO`, deletes spurious functions in the data section, clears mis-disassembled instructions to `undefined`, and labels every code-referenced table base |
| `FixStorage2` | assigns return storage (`r2`) to recovered functions — kills `<UNASSIGNED> RETURN` warnings |
| `ClassifyData` | sizes referenced-but-undefined data to `undefined1/2/4` from ld/st access width; drops VBR=0 default vector symbols |
| `ApplyDataTypes /dev/null` | turns those into stdint typedefs (`uint8/16/32_t`) so the listing reads like C; guarded to only re-type Undefined/own-typedefs/auto byte-word-dword |
| `ClearAnalysisBookmarks` | removes the orange breadcrumb bookmarks (**must be last** of the writers) |
| `ScanMisdisasm LO HI` | read-only: pointer tables still sitting inside instructions (**want 0**) |
| `CountErr` | error-bookmark count (**want 0**) + function count |

**Done looks like:** the driver prints `DONE: converged, 0 error bookmarks, 0 mis-disassembled
tables` and exits 0; function count in the low thousands (not 8000+, which means the data section
wasn't cleaned). `CountErr = 0` on its own is **not** enough — it was already 0 after a run whose
last pass was still adding functions. If the driver exits 4, re-run it (idempotent; it resumes from
the sidecar bounds) or raise `ROUNDS`/`passes`.

---

## Step 3 — Port symbols from a named reference (optional; writes back; GUI closed)

Only if you have an already-named reference project (e.g. a finished DSCM90). Carries its names over
by relocation-invariant signature:

```bash
tools/ghidra/mcore_port.sh <src_proj> <src_name> <src_program> <dst_proj> <dst_name> <dst_program> [min_instr]
```

- **`ExportSigs`** emits an FNV-1a-of-masked-halfwords signature per function (masks the lrw/jsri
  pool slot and branch displacement — the only things that move between variants). Runs on a
  snapshot, so the source GUI may stay open.
- **`port_symbols.py`** ports a name only when the source name is real, its signature is unique among
  named source functions, it occurs exactly once in the target, and both bodies have ≥ `min_instr`
  (default 8) instructions. The rest is the **manual tail** it writes to `tail.txt`:
  - `src-ambiguous`/`dst-ambiguous` — one signature, several candidates; disambiguate by call-site
    context, add the pairs to `ported.tsv`, re-run `ApplyPortedNames`.
  - unmatched targets — variant-specific or never-named-in-source; chip at them by **anchor
    propagation** (a `FUN_` whose callers/callees are all named is usually nameable) in the GUI.
- **`ApplyPortedNames`** applies the unambiguous names (guarded to only rename `FUN_`/`LAB_`).

The image is already fully recovered after Step 2 — porting is about readability.

---

## Verify & quantify the memory map

```bash
tools/ghidra/mcore_verify.sh <proj_dir> <name> <image.cpu.hex>      # FULL=1 for every table row
```

One read-only run against a snapshot of the project (the GUI may stay open). It takes the bounds
from `<prog>.bounds`, runs the scanners below, and prints a summary — error bookmarks, mis-disassembled
table runs, code bytes in the data section, orphan code runs referenced by data or by a function
(all want 0), code bytes owned by no function at all (unreferenced speculative decodes;
informational), function count, table-base count, and the function count in the region below
`codeLO` — exiting 4 if anything gated isn't clean. The individual
scanners, if you want one on its own (`dump.sh` shows the snapshot idiom):

- **`CountErr.java`** — error bookmarks (want 0) + function count. The headline health check.
- **`ImageMap.java`** — every block + the code/defined-data/undefined landscape; confirms where
  defined bytes end and that the code region has little residual undefined.
- **`ScanMisdisasm.java`** — detects ≥4 consecutive code-pointer words inside instructions (a
  mis-disassembled jump table). **Must read 0.**
- **`DataTableInventory.java [DATA_LO DATA_HI]`** — harvests code-referenced table bases in the data
  section: how you quantify the data map (grouped by reader subsystem).
- **`UpperScan.java [LO]`** — classify undefined/code runs from an address up (PADDING / MONOTONIC /
  PTRTABLE / CODELIKE / OTHER).
- **`RegionStats.java LO HI`** — functions / instructions / undefined bytes in a range, plus
  `orphanInstrBytes` / `orphanRuns` / `orphanRefdRuns` (instructions no function owns); the verify
  driver uses it for the code region (want orphan ≈ 0), the data section (want `instrBytes=0`) and
  the region below `codeLO`.

Record, per image: the MISR-signed spans (from the in-ECU self-test range table), `find_boundary.py`'s
bounds, the `ImageMap` landscape, the `DataTableInventory` table count, and the RAM/MMIO blocks.

---

## What to watch for

- **"No errors" ≠ correct.** Pointer bytes decode as valid M·CORE and raise no error bookmark, so a
  mis-disassembled table is invisible to `CountErr`. Always run `ScanMisdisasm` (want 0).
- **"No errors" ≠ complete, either.** Code that is correctly disassembled but owned by no function
  (a body truncated at a branch) raises nothing and still counts as `code=` in `ImageMap`. It shows
  up as a thin symbol port (a named reference function with a smaller or missing twin) and as
  `orphanInstrBytes` in `RegionStats` — the verify summary reports it and `RetraceBodies` fixes it.
- **What is left as "code bytes owned by no function" after a clean run is small and worth a
  manual glance.** `mcore_verify.sh` prints the number; on a converged image it should be a few
  hundred bytes at most, all unreferenced. What it has turned out to be: junk decodes at the edge of
  a literal pool (a run that starts with `bkpt`/`rte`/`dect` and is a dozen bytes), a lone `br` that
  no body claims, and occasionally a genuinely unreferenced routine. To inspect, run `RegionStats`
  with a small range or `UpperScan`, and fix a real one with `ForceFn.java` (functionize) or
  `ForceData.java` (revert). Anything larger, or any run that *is* referenced, means a recovery rule
  is missing — see the three cases above (branch references, vector table, displaced entries), which
  is how this list was built.
- **Don't force-disassemble the residual `??`.** After recovery, remaining undefined in the code
  region is literal/inline pools (data), and `??` in RAM is uninitialized (correct). The data section
  is *supposed* to be undefined-then-labeled.
- **Below `codeLO` (0x40000–0x41FFF) is left as auto-analysis made it.** That region is the VBR
  table, the calibration directory and the OSEK init stubs; the pipeline never cleans it, so expect a
  few dozen tiny (2–24 byte) `FUN_` functions there and `0xFFFF` padding decoded as `bsr`. They raise
  no error bookmark and don't affect the code region. `mcore_verify.sh` reports the count so it is
  visible; `OsInit.java` disassembles the real init stubs if you need them, and `ForceData.java`
  reverts a specific spurious one.
- **Trust the auto boundary, but glance at it.** `DataSectionClean auto` prints the pinned `dataLO`;
  it's the end of the last prologue function. If a disassembly looks truncated at the top of the code,
  check that number and the `ImageMap` landscape.
- **Shared tails stay separate functions.** When several functions jump to one block (switch-case
  stubs converging on a common epilogue), Ghidra can't put it in all their bodies, so it stays its
  own small `FUN_` and the stubs end in a jump to it. `RetraceBodies` keeps such an entry even when
  it is also a fall-through target. It is also why a few reference functions still compare as
  "smaller" in a symbol port: the port signature covers the body, not the tail.
- **2-byte-body "quirk".** A scripted headless `createFunction` used to leave 2-byte function
  bodies that remove+recreate could not grow. The cause was the missing computed-flow references
  (above): an entry whose first instruction is a `br`/`bt` with no reference has nowhere to trace to.
  Now that `RecoverFlowCode` records them, such bodies retrace like any other; a retrace that comes
  back no larger is counted as `unchanged` in the `RetraceBodies` line and does not keep the
  convergence loop alive.
- **`FixLiteralPools: … ramRefsSkipped=N failed=M`.** Absolute `ld`/`st` references into RAM look
  like pool slots but are variables in an uninitialized block, so they are skipped and counted (large
  N is normal). `failed` is the rare slot that can't be typed at all (its 4 bytes run off the end of a
  block); the line names it with the exception. Both are constant across passes and not errors.
- **`Flow Override: CALL_RETURN (COMPUTED_CALL_TERMINATOR)`** on a `jmpi`/`br` is a benign tail call.
- **Single-site fixes:** `ForceFn.java` (clear+disassemble+functionize one address), `ForceData.java`
  (revert to data), `FixFnEntry.java` (re-root a late-seeded entry). Avoid whole-body "repair" clears —
  they break tail-call stub entries.

## Script / tool reference

### Drivers & standalone tools (`tools/`)

| path | role |
|---|---|
| `tools/install_mcore.sh` | install/rebuild the patched M·CORE SLEIGH (see SETUP.md) |
| `tools/parse_0pa.py` | .0pa → `.cpu.hex` (with `--reloc`) + region `.bin`s |
| `tools/find_boundary.py` | prologue-scan: arch check + programmatic codeHI/dataHI from a `.cpu.hex` |
| `tools/port_symbols.py` | signature 1:1 matcher; writes `ported.tsv` + `tail.txt` |
| `tools/ghidra/mcore_import.sh` | step 1: arch-check + parse + import + `CreateMemBlocks` |
| `tools/ghidra/mcore_recover.sh` | step 2: auto-bounds + recover + finish, repeated until converged; writes `<prog>.bounds` |
| `tools/ghidra/mcore_port.sh` | step 3: `ExportSigs` ×2 + match + `ApplyPortedNames` |
| `tools/ghidra/mcore_verify.sh` | V: read-only health check + memory-map numbers in one run (snapshot; GUI may be open) |
| `tools/ghidra/dump.sh <proj_dir> <name> <cpu_hex> ADDR:N …` | read-only disassembly dump from a snapshot (GUI may be open) |

### Ghidra scripts (`tools/ghidra/scripts/*.java`, run via `-postScript`)

The drivers run these for you; listed so you can run one on its own. Bound-configurable scripts take
`LO HI` (hex, no `0x`) and default to `0x42000`/`0xD7000` when called with no args.

| script | step / role | args |
|---|---|---|
| `CreateMemBlocks.java` | 1 — add RAM/MMIO blocks | — |
| `FixLiteralPools.java` | 2 — define pools, clear pool-as-code red | — |
| `FixResidual.java` | 2 — mop up padding / pool edges | — |
| `RecoverRefCode.java` | 2 — code reached via unmaterialized pointers | `LO HI` |
| `RecoverCodeRuns.java` | 2 — functionize CODE-classified runs | `LO HI` |
| `RecoverFlowCode.java` | 2 — branch-only-reached blocks | `LO HI` |
| `FixJumpTables.java` | 2 — revert table-as-code, redo jump-table targets | `LO HI` |
| `RetraceBodies.java` | 2 — drop spurious continuations, re-trace bodies | `LO HI` |
| `FunctionizeOrphans.java` | 2 — functionize referenced / prologue-started orphan code | `LO HI` |
| `DataSectionClean.java` | 2 — pin dataLO, delete spurious fns, label tables | `auto DATA_HI` \| `DATA_LO DATA_HI` |
| `FixStorage2.java` | 2 — assign return storage | — |
| `ClassifyData.java` | 2 — size referenced undefined data | — |
| `ApplyDataTypes.java` | 2 — undefined → `uint8/16/32_t` typedefs | `<types.tsv or /dev/null>` |
| `ClearAnalysisBookmarks.java` | 2 — drop breadcrumb bookmarks (**last**) | — |
| `CountErr.java` | 2/V — error-bookmark + function counts | — |
| `ExportSigs.java` | 3 — per-function porting signatures | `<out.sigs>` |
| `ApplyPortedNames.java` | 3 — apply `addr<TAB>name` table | `<ported.tsv>` |
| `ImageMap.java` | V — block list + code/data/undefined landscape | — |
| `ScanMisdisasm.java` | 2/V — detect table-as-code (want 0) | `[LO HI]` |
| `DataTableInventory.java` | V — harvest code-referenced table bases | `[DATA_LO DATA_HI]` |
| `UpperScan.java` | V — classify undefined/code runs from an address up | `[LO]` |
| `RegionStats.java` | V — functions/instructions/undefined/orphan-code bytes in a range | `LO HI [listMax]` |
| `DumpAt.java` | V — dump disassembly at `ADDR:N` (via `dump.sh`) | `ADDR:N …` |
| `ForceFn.java` | fix — clear+disassemble+functionize one address | `<addrHex=name …>` |
| `ForceData.java` | fix — revert addresses to data | `<addrHex …>` |
| `FixFnEntry.java` | fix — re-root a late-seeded function entry | `<addrHex=name …>` |
| `OsInit.java` | 1/fix — disassemble OSEK init stubs below `0x42000` | `<types.tsv> <addrHex …>` |

## Acknowledgment

This process was developed against and cross-validated with cmg5461's
[`mk60e5-firmware`](https://github.com/cmg5461/mk60e5-firmware): its independent M·CORE
disassembler (`mcore_dis.py`) and reference listing, its documented symbol and memory maps
(`*_symbols.md`), and its signature/self-test analysis (`bmy_resign.py`). The code in this repo is
our own, but the understanding it encodes rests on that work — credit it alongside this if you
build on it. See [CREDITS.md](CREDITS.md).
