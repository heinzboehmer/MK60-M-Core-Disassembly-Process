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
# Convergence: one headless session = [recovery loop x passes] + [finish chain]. The finish
# chain can expose seeds the loop never saw, so the driver runs further ROUNDS (default 4)
# until the last pass of a round adds nothing. Once DataSectionClean has pinned dataLO, the
# value is saved to <proj_dir>/<prog>.bounds and reused as the recovery ceiling (CODE_HI) for
# every later round and re-run, so the top of the data section is not re-disassembled and
# re-cleaned each time. Delete the sidecar (or set CODE_HI) to start from the estimate again.
#
# Exit status: 0 = converged, 0 error bookmarks, 0 mis-disassembled tables.
#              1 = a headless session failed (see the round log in <proj_dir>).
#              3 = not an M·CORE image.   4 = finished but not clean (details printed).
#
# Chain: FixLiteralPools
#        -> [RecoverRefCode RecoverCodeRuns RecoverFlowCode FixJumpTables
#            FixLiteralPools FixResidual RetraceBodies FunctionizeOrphans] x passes  (codeLO..codeHI)
#        -> DataSectionClean {auto|DATA_LO} DATA_HI   (delete spurious fns, label tables)
#        -> FixStorage2 -> ClassifyData + ApplyDataTypes -> ClearAnalysisBookmarks
#        -> ScanMisdisasm -> CountErr                   (read-only health checks)
#        ] until converged, then once: AnalyzeSignatures (params/return/locals; SIGS=0 skips)
set -euo pipefail
[ $# -ge 3 ] || { sed -n '2,31p' "$0"; exit 2; }

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PROJ_DIR="$1"; NAME="$2"; HEX="$3"; PASSES="${4:-6}"
ROUNDS="${ROUNDS:-4}"
PROG="$(basename "$HEX")"
HEADLESS="${GHIDRA_HOME:-/opt/homebrew/opt/ghidra/libexec}/support/analyzeHeadless"
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home}"
SIDECAR="$PROJ_DIR/$PROG.bounds"

# --- bounds (auto unless overridden) ---
BND="$(python3 -I "$ROOT/tools/find_boundary.py" "$HEX" | grep '^BOUNDS' || true)"
echo ">> $BND"
if echo "$BND" | grep -q 'arch=unknown'; then
  echo "!! this image does not look like M·CORE (see find_boundary.py)." >&2
  [ "${FORCE:-}" = 1 ] || { echo "!! aborting; set FORCE=1 to override, or pass the right image." >&2; exit 3; }
  BND="BOUNDS codeLO=0x42000 codeHI=0xd7000 dataHI=0xf6a24"
fi
pick(){ echo "$BND" | sed -n "s/.*$1=0x\([0-9a-fA-F]*\).*/\1/p"; }
CODE_LO="${CODE_LO:-$(pick codeLO)}"; DATA_HI="${DATA_HI:-$(pick dataHI)}"
DLO="${DATA_LO:-auto}"
if [ -z "${CODE_HI:-}" ] && [ -f "$SIDECAR" ]; then
  CODE_HI="$(sed -n 's/^DATA_LO=\([0-9a-fA-F]*\).*/\1/p' "$SIDECAR")"
  echo ">> reusing pinned dataLO=$CODE_HI from $SIDECAR as the recovery ceiling (CODE_HI)"
fi
CODE_HI="${CODE_HI:-$(pick codeHI)}"
echo ">> bounds: codeLO=$CODE_LO codeHI=$CODE_HI dataLO=$DLO dataHI=$DATA_HI  passes=$PASSES rounds<=$ROUNDS"

# The per-script summary lines worth showing; the full log is kept per round.
SHOW='Recover|FixLiteralPools:|FixJumpTables:|FixResidual:|RetraceBodies:|FunctionizeOrphans:|DataSectionClean|DATA_LO=|FixStorage2:|ClassifyData:|ApplyDataTypes:|ClearAnalysisBookmarks:|ScanMisdisasm:|CountErr:|Error bookmarks=|Unable to lock|ERROR|Exception'

# newFunctions summed over the LAST pass of the loop (the scripts that create functions),
# plus functions RetraceBodies removed or re-traced. Zero means the loop had nothing left to do.
last_pass_work(){  # <log>
  local total=0 v
  for s in RecoverRefCode RecoverCodeRuns RecoverFlowCode FixJumpTables FunctionizeOrphans; do
    v="$(grep "$s:" "$1" | tail -1 | sed -n 's/.*newFunctions=\([0-9]*\).*/\1/p')"
    total=$((total + ${v:-0}))
  done
  v="$(grep 'RetraceBodies:' "$1" | tail -1 | sed -n 's/.*removedSpurious=\([0-9]*\).*/\1/p')"
  total=$((total + ${v:-0}))
  v="$(grep 'RetraceBodies:' "$1" | tail -1 | sed -n 's/.*retraced=\([0-9]*\).*/\1/p')"
  total=$((total + ${v:-0}))
  v="$(grep 'FixJumpTables:' "$1" | tail -1 | sed -n 's/.*misCodedReverted=\([0-9]*\).*/\1/p')"
  echo $((total + ${v:-0}))
}

converged=0; ERRS=; FNS=; MISRUNS=
for round in $(seq 1 "$ROUNDS"); do
  LOG="$PROJ_DIR/$PROG.recover.round$round.log"
  echo ">> round $round / $ROUNDS  (codeHI=$CODE_HI)  log: $LOG"

  ARGS=( -postScript FixLiteralPools.java "$CODE_LO" "$CODE_HI" )
  for _ in $(seq 1 "$PASSES"); do
    ARGS+=( -postScript RecoverRefCode.java "$CODE_LO" "$CODE_HI"
            -postScript RecoverCodeRuns.java "$CODE_LO" "$CODE_HI"
            -postScript RecoverFlowCode.java "$CODE_LO" "$CODE_HI"
            -postScript FixJumpTables.java "$CODE_LO" "$CODE_HI"
            -postScript FixLiteralPools.java "$CODE_LO" "$CODE_HI"
            -postScript FixResidual.java
            -postScript RetraceBodies.java "$CODE_LO" "$CODE_HI"
            -postScript FunctionizeOrphans.java "$CODE_LO" "$CODE_HI" )
  done
  ARGS+=( -postScript DataSectionClean.java "$DLO" "$DATA_HI"
          -postScript FixStorage2.java
          -postScript ClassifyData.java
          -postScript ApplyDataTypes.java /dev/null
          -postScript ClearAnalysisBookmarks.java
          -postScript ScanMisdisasm.java "$CODE_LO" "$CODE_HI"
          -postScript CountErr.java )

  rc=0
  "$HEADLESS" "$PROJ_DIR" "$NAME" \
      -process "$PROG" -noanalysis \
      -scriptPath "$ROOT/tools/ghidra/scripts" \
      "${ARGS[@]}" > "$LOG" 2>&1 || rc=$?
  grep -E "$SHOW" "$LOG" | grep -vE '^INFO  (REPORT|SCRIPT): ' \
    | sed -E 's/^INFO  [A-Za-z0-9]+\.java> //; s/ \(GhidraScript\) *$//' || true

  if grep -q 'Unable to lock' "$LOG"; then
    echo "!! project is locked — close the Ghidra GUI and re-run." >&2; exit 1
  fi
  if [ "$rc" -ne 0 ] || ! grep -q 'Error bookmarks=' "$LOG"; then
    echo "!! headless session failed (exit $rc) or did not reach CountErr — see $LOG" >&2
    grep -E 'ERROR|Exception' "$LOG" | head -20 >&2 || true
    exit 1
  fi

  # Pin the exact code/data boundary found this round and reuse it as the ceiling.
  PINNED="$(grep '^INFO  DataSectionClean.java> DATA_LO=' "$LOG" | tail -1 | sed -n 's/.*DATA_LO=\([0-9a-fA-F]*\).*/\1/p')"
  if [ -n "$PINNED" ]; then
    printf 'CODE_LO=%s\nDATA_LO=%s\nDATA_HI=%s\n' "$CODE_LO" "$PINNED" "$DATA_HI" > "$SIDECAR"
    CODE_HI="$PINNED"
  fi

  ERRS="$(grep 'Error bookmarks=' "$LOG" | tail -1 | sed -n 's/.*Error bookmarks=\([0-9]*\).*/\1/p')"
  FNS="$(grep 'Error bookmarks=' "$LOG" | tail -1 | sed -n 's/.*functions=\([0-9]*\).*/\1/p')"
  MISRUNS="$(grep 'ScanMisdisasm:' "$LOG" | tail -1 | sed -n 's/.*runs=\([0-9]*\).*/\1/p')"
  work="$(last_pass_work "$LOG")"
  echo ">> round $round: lastPassWork=$work errorBookmarks=${ERRS:-?} misdisasmRuns=${MISRUNS:-?} functions=${FNS:-?} dataLO=${PINNED:-?}"
  if [ "$work" -eq 0 ]; then converged=1; break; fi
done

echo ">> bounds saved: $SIDECAR"
status=0
[ "$converged" -eq 1 ] || { echo "!! NOT converged after $ROUNDS rounds — re-run (it resumes) or raise ROUNDS/passes." >&2; status=4; }
[ "${ERRS:-1}" -eq 0 ] || { echo "!! error bookmarks remain: $ERRS" >&2; status=4; }
[ "${MISRUNS:-1}" -eq 0 ] || { echo "!! ScanMisdisasm found $MISRUNS mis-disassembled pointer-table run(s) — see the log." >&2; status=4; }

# --- signatures: parameters / return / stack locals on the recovered function set ---
# One more session, once the function set is final. The recovery sessions run -noanalysis,
# so without this every function is `undefined FUN_x(void)` and decompiled calls have no
# arguments. Ghidra's own stack + decompiler-parameter-ID commands; idempotent. SIGS=0 skips.
if [ "$status" -eq 0 ] && [ "${SIGS:-1}" != 0 ]; then
  LOG="$PROJ_DIR/$PROG.signatures.log"
  echo ">> signatures: stack frames + decompiler parameter ID over 40000..$DATA_HI  (log: $LOG)"
  rc=0
  "$HEADLESS" "$PROJ_DIR" "$NAME" \
      -process "$PROG" -noanalysis \
      -scriptPath "$ROOT/tools/ghidra/scripts" \
      -postScript AnalyzeSignatures.java 40000 "$DATA_HI" "${SIG_TIMEOUT:-60}" > "$LOG" 2>&1 || rc=$?
  grep -E "AnalyzeSignatures:|Unable to lock|ERROR|Exception" "$LOG" | grep -vE '^INFO  (REPORT|SCRIPT): ' \
    | sed -E 's/^INFO  [A-Za-z0-9]+\.java> //; s/ \(GhidraScript\) *$//' || true
  if [ "$rc" -ne 0 ] || ! grep -q 'AnalyzeSignatures:' "$LOG"; then
    echo "!! signature pass failed (headless exit $rc) — see $LOG" >&2; exit 1
  fi
fi

[ "$status" -eq 0 ] && echo ">> DONE: converged, 0 error bookmarks, 0 mis-disassembled tables, $FNS functions."
exit "$status"
