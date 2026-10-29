#!/usr/bin/env bash
# STEP 1 of the M·CORE image-recovery pipeline (see .claude/skills/mcore-image-recovery).
# Parse a .0pa (if given one) to CPU-address Intel HEX, import it as M·CORE, auto-analyze,
# and add the RAM/MMIO blocks the flash image doesn't carry.
#
# Usage:
#   tools/ghidra/mcore_import.sh <image.0pa | image.cpu.hex> <proj_dir> <proj_name>
# e.g.
#   tools/ghidra/mcore_import.sh "SP Daten data/DSCM80/7846411A.0pa" alt-images/DSCM80-1M/ghidra_proj MK60_DSCM80
#
# A fresh import is NOT a finished disassembly: M·CORE models every branch as a computed
# jump, so auto-analysis leaves branch/jump/pointer-reached code raw AND mis-decodes the
# data section into spurious functions. Continue with mcore_recover.sh.
#
# Exit status: 0 = imported and RAM/MMIO blocks present; 1 = import failed (full log is
# kept at <proj_dir>/<prog>.import.log).
set -euo pipefail
[ $# -ge 3 ] || { sed -n '2,15p' "$0"; exit 2; }

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
IMAGE="$1"; PROJ_DIR="$2"; PROJ_NAME="$3"
HEADLESS="${GHIDRA_HOME:-/opt/homebrew/opt/ghidra/libexec}/support/analyzeHeadless"
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home}"

case "$IMAGE" in
  *.0pa)
    HEXDIR="$(dirname "$IMAGE")"
    STEM="$(basename "$IMAGE" .0pa)"
    echo ">> parse_0pa $IMAGE  (--reloc 0x48000:0x100000:-0x8000)"
    python3 -I "$ROOT/tools/parse_0pa.py" "$IMAGE" "$HEXDIR" --reloc 0x48000:0x100000:-0x8000
    HEX="$HEXDIR/$STEM.cpu.hex" ;;
  *) HEX="$IMAGE" ;;
esac
[ -f "$HEX" ] || { echo "missing $HEX" >&2; exit 1; }
PROG="$(basename "$HEX")"

# Architecture sanity check BEFORE the slow import — a non-M·CORE image wastes an
# analysis run. Non-gating: it only warns (set the arch straight if this fires).
echo ">> arch sanity check (find_boundary.py)"
python3 -I "$ROOT/tools/find_boundary.py" "$HEX" 2>/dev/null \
  | grep -E 'prologues found|WARNING|^BOUNDS' || true
python3 -I "$ROOT/tools/find_boundary.py" "$HEX" 2>/dev/null | grep -q 'arch=unknown' \
  && echo ">> NOTE: this may NOT be an M·CORE image — the pipeline assumes MCORE:BE:32. Continuing." || true

mkdir -p "$PROJ_DIR"
LOG="$PROJ_DIR/$PROG.import.log"
echo ">> import $HEX  ->  $PROJ_DIR / $PROJ_NAME   (log: $LOG)"
rc=0
"$HEADLESS" "$PROJ_DIR" "$PROJ_NAME" \
    -import "$HEX" \
    -loader IntelHexLoader \
    -processor "MCORE:BE:32:default" \
    -scriptPath "$ROOT/tools/ghidra/scripts" \
    -postScript CreateMemBlocks.java \
    -overwrite \
    -analysisTimeoutPerFile 1200 > "$LOG" 2>&1 || rc=$?

# Auto-analysis prints a handful of "Failed to create function ... since its body contains
# referring thunk" errors on every M·CORE image: tail-call stubs it mistakes for thunks.
# They are expected here and mcore_recover.sh re-traces those bodies; show a count, not noise.
THUNKS="$(grep -c 'referring thunk' "$LOG" || true)"
[ "${THUNKS:-0}" -gt 0 ] && echo ">> $THUNKS 'referring thunk' CreateFunctionCmd errors during auto-analysis (expected; mcore_recover.sh fixes them)"
grep -E "CreateMemBlocks|created |skip |FAILED |Unable to lock|ERROR|Exception" "$LOG" \
  | grep -v 'referring thunk' | grep -vE '^INFO  (REPORT|SCRIPT): ' \
  | sed -E 's/^INFO  [A-Za-z0-9]+\.java> //; s/ \(GhidraScript\) *$//' || true

if grep -q 'Unable to lock' "$LOG"; then
  echo "!! project is locked — close the Ghidra GUI and re-run." >&2; exit 1
fi
if grep -q 'MCORE:BE:32:default.*not found\|Language.*MCORE.*not found\|No language' "$LOG"; then
  echo "!! language MCORE:BE:32:default not available — run tools/install_mcore.sh (same GHIDRA_HOME)." >&2; exit 1
fi
if [ "$rc" -ne 0 ] || ! grep -q 'CreateMemBlocks.java>' "$LOG" || grep -q 'CreateMemBlocks.java>   FAILED' "$LOG"; then
  echo "!! import failed (headless exit $rc) — see $LOG" >&2
  grep -E 'ERROR|Exception' "$LOG" | grep -v 'referring thunk' | head -20 >&2 || true
  exit 1
fi
echo ">> imported.  program name in project = $PROG"
