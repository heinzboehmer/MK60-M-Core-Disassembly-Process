# Setup — get the tools into a usable state

One-time setup. After this, the `mcore-image-recovery` skill (in
`.claude/skills/mcore-image-recovery/SKILL.md`) runs end to end. Everything was verified on
**macOS (Apple Silicon)**; Linux works with the path overrides noted below.

The whole pipeline depends on one thing that doesn't exist in stock Ghidra: the
**`MCORE:BE:32:default`** processor language. Step 4 below builds it.

## 1. Ghidra 12.2+

```bash
brew install --cask ghidra          # Apple Silicon: installs under /opt/homebrew
```

or download from <https://github.com/NationalSecurityAgency/ghidra/releases> and unzip.

The scripts call Ghidra's headless launcher and SLEIGH compiler by path. They default to the
arm64 Homebrew location:

```
GHIDRA_HOME = /opt/homebrew/opt/ghidra/libexec
```

> **Apple Silicon gotcha:** two Homebrews can coexist — arm64 `/opt/homebrew` and Intel
> `/usr/local` — and `brew --prefix` may return the Intel one. The scripts pin `/opt/homebrew`;
> if your Ghidra is elsewhere, export `GHIDRA_HOME` to the dir that contains `support/analyzeHeadless`
> and `support/sleigh`.

## 2. JDK 21+ (Temurin 25 verified)

Ghidra 12 needs a modern JDK.

```bash
brew install --cask temurin        # or temurin@25
```

The scripts default to:

```
JAVA_HOME = /Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home
```

Override `JAVA_HOME` if yours differs (`/usr/libexec/java_home -V` lists installed JDKs).

> Ghidra 12 uses PyGhidra, not Jython, so the analysis scripts in `tools/ghidra/scripts/` are
> **Java** and run via `-postScript`. You don't compile them — Ghidra does, on the fly.

## 3. Python 3

Any Python 3 (stdlib only — no pip packages). Used by `parse_0pa.py`, `find_boundary.py`, and
`port_symbols.py`.

## 4. Build the patched M·CORE SLEIGH  ← the important one

```bash
tools/install_mcore.sh
```

This:

1. copies the vendored CUB3D M·CORE module (`tools/ghidra-mcore/MCORE/`, Apache-2.0) into
   `$GHIDRA_HOME/Ghidra/Processors/MCORE`,
2. drops in the local compiler spec (`tools/patches/mcore.cspec`) and applies the local
   SLEIGH fixes (`tools/patches/mcore.patch` — one-based addi/subi/cmplti immediates,
   `bmaski 32`, `mulsh`, `idly4`, the `jmpi`/`jsri` bitfield, `psrclr`/`psrset`),
3. runs Ghidra's `sleigh` compiler to produce `mcore.sla`.

Success prints `OK: mcore.sla built. Language MCORE:BE:32:default is now available.`

It's **idempotent** — re-run it any time, and **you must re-run it after `brew upgrade ghidra`**
(a Ghidra upgrade wipes the Cellar, taking the installed module and compiled `.sla` with it).

> **Why patched?** The upstream CUB3D module has a few opcode/bitfield bugs that matter for this
> image — most importantly `jmpi`/`jsri` constrain the wrong bitfield, so thousands of indirect
> calls and tail-jumps decode as bogus `lrw` loads. `tools/patches/make_mcore_patch.py`
> regenerates `mcore.patch` from the upstream `mcore.sinc` if you ever need to (documented inline).

## 5. Verify

Point the pipeline at any MK60E5 `.0pa` (none ships with this repo — see the note below):

```bash
tools/ghidra/mcore_import.sh /path/to/<part>.0pa projects/smoke SMOKE
```

A good import first prints the arch sanity check (`prologues found: …` and a `BOUNDS arch=mcore …`
line), then logs `CreateMemBlocks:` adding the RAM/MMIO blocks, with no `Unable to lock` /
language-not-found error. (The project will be full of red errors at this stage — that's expected;
`mcore_recover.sh` clears them.) You can then open it in the Ghidra GUI to confirm the listing shows
M·CORE mnemonics (`lrw`, `jmpi`, `jsri`, `bsr`, `jmp r15`).

> **Fed the wrong image?** `mcore_import.sh` runs `find_boundary.py` up front and warns
> `this may NOT be an M·CORE image` when it finds almost no M·CORE function prologues — so you catch
> a mis-identified (ARM/PPC/SH/…) image before burning an analysis run. It's a warning, not a gate.

## Optional: deeper architecture identification with `cpu_rec`

The built-in prologue check above is an M·CORE-specific signal and is enough in practice. If you
have a *raw* binary of unknown architecture and want an independent opinion, run
[`cpu_rec`](https://github.com/airbus-seclab/cpu_rec) on it — it statistically classifies the ISA
and will say `M-CORE` (or flag something else) for a region. It's an external tool with its own
corpus; this repo doesn't vendor it, and it never gates the pipeline.

## Environment variables

| var | default | when to set |
|---|---|---|
| `GHIDRA_HOME` | `/opt/homebrew/opt/ghidra/libexec` | Ghidra installed elsewhere |
| `JAVA_HOME` | `.../temurin-25.jdk/Contents/Home` | different JDK |

`mcore_recover.sh` also accepts bound overrides via env (`CODE_LO`/`CODE_HI`/`DATA_HI`/`DATA_LO`,
hex, no `0x`) and `FORCE=1` — see [PROCESS.md](PROCESS.md) Step 2. You normally don't need them.

## Troubleshooting

- **`Unable to lock project`** — the Ghidra GUI has the project open. Close it; the write-back
  steps need an exclusive lock.
- **`sleigh: command not found` / Processors dir not found** — `GHIDRA_HOME` is wrong. It must be
  the dir containing `support/analyzeHeadless` and `support/sleigh`.
- **`Language MCORE:BE:32:default not found` on import** — step 4 didn't run (or ran against a
  different `GHIDRA_HOME` than the import). Re-run `tools/install_mcore.sh` with the same env.
- **Patch fails to apply in `install_mcore.sh`** — the vendored module got modified. Restore it
  (`git checkout tools/ghidra-mcore`) and re-run; the patch is written against the pristine clone.

## No firmware here

This repo is the **process**, not the firmware. MK60E5 images are BMW/ATE copyrighted and are not
redistributed. Bring your own `.0pa` / `.cpu.hex`. `.gitignore` already excludes images, extracted
`.bin`s, `.cpu.hex`/`.clean.hex`, and Ghidra `projects/`.
