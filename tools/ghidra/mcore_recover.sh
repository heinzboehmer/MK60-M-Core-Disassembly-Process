#!/usr/bin/env bash
# Recover + finish: turn a raw import into a complete, de-noised disassembly. CLOSE THE
# GHIDRA GUI first (writes back, needs the exclusive project lock). Idempotent.
#
# Usage:
#   tools/ghidra/mcore_recover.sh <proj_dir> <name> <cpu_hex_path> [passes]
#
# Bounds are detected automatically: find_boundary.py gives codeLO/codeHI/dataHI from the
# .cpu.hex, and the EXACT data boundary (dataLO) is pinned Ghidra-side by DataSectionClean's
# `auto` mode (end of the last real prologue function). Override any bound via env (hex, no
# 0x): CODE_LO, CODE_HI, DATA_HI; set DATA_LO=<hex> to pin it yourself instead of `auto`.
# Set FORCE=1 to proceed even if the image doesn't look like M·CORE.
#
# Chain: FixLiteralPools
#        -> [RecoverRefCode RecoverCodeRuns RecoverFlowCode FixJumpTables
#            FixLiteralPools FixResidual RetraceBodies] x passes   (over codeLO..codeHI)
#        -> DataSectionClean {auto|DATA_LO} DATA_HI   (delete spurious fns, label tables)
#        -> FixStorage2 -> ClassifyData + ApplyDataTypes -> ClearAnalysisBookmarks -> CountErr
set -euo pipefail
[ $# -ge 3 ] || { sed -n '2,20p' "$0"; exit 2; }

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PROJ_DIR="$1"; NAME="$2"; HEX="$3"; PASSES="${4:-6}"
PROG="$(basename "$HEX")"
HEADLESS="${GHIDRA_HOME:-/opt/homebrew/opt/ghidra/libexec}/support/analyzeHeadless"
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home}"

# --- bounds (auto unless overridden) ---
BND="$(python3 -I "$ROOT/tools/find_boundary.py" "$HEX" | grep '^BOUNDS' || true)"
echo ">> $BND"
if echo "$BND" | grep -q 'arch=unknown'; then
  echo "!! this image does not look like M·CORE (see find_boundary.py)." >&2
  [ "${FORCE:-}" = 1 ] || { echo "!! aborting; set FORCE=1 to override, or pass the right image." >&2; exit 3; }
  BND="BOUNDS codeLO=0x42000 codeHI=0xd7000 dataHI=0xf6a24"
fi
pick(){ echo "$BND" | sed -n "s/.*$1=0x\([0-9a-fA-F]*\).*/\1/p"; }
CODE_LO="${CODE_LO:-$(pick codeLO)}"; CODE_HI="${CODE_HI:-$(pick codeHI)}"; DATA_HI="${DATA_HI:-$(pick dataHI)}"
DLO="${DATA_LO:-auto}"
echo ">> bounds: codeLO=$CODE_LO codeHI=$CODE_HI dataLO=$DLO dataHI=$DATA_HI  passes=$PASSES"

ARGS=( -postScript FixLiteralPools.java )
for _ in $(seq 1 "$PASSES"); do
  ARGS+=( -postScript RecoverRefCode.java "$CODE_LO" "$CODE_HI"
          -postScript RecoverCodeRuns.java "$CODE_LO" "$CODE_HI"
          -postScript RecoverFlowCode.java "$CODE_LO" "$CODE_HI"
          -postScript FixJumpTables.java "$CODE_LO" "$CODE_HI"
          -postScript FixLiteralPools.java
          -postScript FixResidual.java
          -postScript RetraceBodies.java "$CODE_LO" "$CODE_HI" )
done
ARGS+=( -postScript DataSectionClean.java "$DLO" "$DATA_HI"
        -postScript FixStorage2.java
        -postScript ClassifyData.java
        -postScript ApplyDataTypes.java /dev/null
        -postScript ClearAnalysisBookmarks.java
        -postScript CountErr.java )

"$HEADLESS" "$PROJ_DIR" "$NAME" \
    -process "$PROG" -noanalysis \
    -scriptPath "$ROOT/tools/ghidra/scripts" \
    "${ARGS[@]}" 2>&1 \
  | grep -E "Recover|FixLiteralPools:|FixJumpTables:|FixResidual:|RetraceBodies:|DataSectionClean|FixStorage2:|ClassifyData:|ApplyDataTypes:|ClearAnalysisBookmarks:|CountErr:|Error bookmarks=|Unable to lock|ERROR|Exception" || true
