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

# Architecture sanity check BEFORE the slow import — a non-M·CORE image wastes an
# analysis run. Non-gating: it only warns (set the arch straight if this fires).
echo ">> arch sanity check (find_boundary.py)"
python3 -I "$ROOT/tools/find_boundary.py" "$HEX" 2>/dev/null \
  | grep -E 'prologues found|WARNING|^BOUNDS' || true
python3 -I "$ROOT/tools/find_boundary.py" "$HEX" 2>/dev/null | grep -q 'arch=unknown' \
  && echo ">> NOTE: this may NOT be an M·CORE image — the pipeline assumes MCORE:BE:32. Continuing." || true

mkdir -p "$PROJ_DIR"
echo ">> import $HEX  ->  $PROJ_DIR / $PROJ_NAME"
"$HEADLESS" "$PROJ_DIR" "$PROJ_NAME" \
    -import "$HEX" \
    -loader IntelHexLoader \
    -processor "MCORE:BE:32:default" \
    -scriptPath "$ROOT/tools/ghidra/scripts" \
    -postScript CreateMemBlocks.java \
    -overwrite \
    -analysisTimeoutPerFile 1200 2>&1 \
  | grep -E "CreateMemBlocks|created |skip |FAILED |Unable to lock|ERROR|Exception" || true
echo ">> imported.  program name in project = $(basename "$HEX")"
