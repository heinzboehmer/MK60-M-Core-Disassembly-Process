#!/usr/bin/env bash
# STEP 4 of the M·CORE image-recovery pipeline: port function names from a NAMED reference
# image (usually the M3 DSCM90) onto a freshly-recovered target, by signature match.
# CLOSE THE GHIDRA GUI first (the ApplyPortedNames step writes back to the target).
#
# Usage:
#   tools/ghidra/mcore_port.sh <src_proj> <src_name> <src_prog> <dst_proj> <dst_name> <dst_prog> [min_instr]
# e.g.
#   tools/ghidra/mcore_port.sh build/ghidra_proj MK60_DSCM90 7846816A.cpu.hex \
#       alt-images/DSCM80-1M/ghidra_proj MK60_DSCM80 7846411A.cpu.hex
#
# Exports a relocation-invariant signature per function from both projects (ExportSigs),
# 1:1-matches them (port_symbols.py), applies the unambiguous names (ApplyPortedNames), and
# leaves the MANUAL TAIL in $WORK/tail.txt for hand review (ambiguous + unmatched targets).
# Exits 1 if either signature export or the apply step fails (logs are kept in $WORK).
#
# When source and target are the SAME image (hash-checked), a second pass replays every
# annotation of the source by address — ExportAnnotations / ApplyAnnotations: all function
# names (incl. short and split-at-label ones the signature match refuses), user labels (cal
# tables, RAM/MMIO variables), EOL/plate comments, bookmarks and concrete scalar types.
set -euo pipefail
[ $# -ge 6 ] || { sed -n '2,18p' "$0"; exit 2; }

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
SP="$1"; SN="$2"; SPROG="$3"; DP="$4"; DN="$5"; DPROG="$6"; MIN="${7:-8}"
HEADLESS="${GHIDRA_HOME:-/opt/homebrew/opt/ghidra/libexec}/support/analyzeHeadless"
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home}"
WORK="${WORK:-$(mktemp -d "${TMPDIR:-/tmp}/mk60port.XXXXXX")}"
SCR="$ROOT/tools/ghidra/scripts"

sigs() {  # <proj> <name> <prog> <out> ; read-only, GUI may stay open on the SOURCE
  local snap; snap="$(mktemp -d "${TMPDIR:-/tmp}/mk60snap.XXXXXX")"
  cp "$1/$2.gpr" "$snap/"; cp -R "$1/$2.rep" "$snap/"
  local log="$snap/export.log" rc=0
  "$HEADLESS" "$snap" "$2" -process "$3" -noanalysis -scriptPath "$SCR" \
      -postScript ExportSigs.java "$4" > "$log" 2>&1 || rc=$?
  grep -E "ExportSigs:|ERROR" "$log" || true
  if [ "$rc" -ne 0 ] || [ ! -s "$4" ]; then
    echo "!! ExportSigs failed for $1/$2 ($3): headless exit $rc, no signatures written" >&2
    grep -E 'ERROR|Exception' "$log" | head -10 >&2 || true
    rm -rf "$snap"; exit 1
  fi
  rm -rf "$snap"
}

echo ">> export signatures"
sigs "$SP" "$SN" "$SPROG" "$WORK/src.sigs"
sigs "$DP" "$DN" "$DPROG" "$WORK/dst.sigs"

echo ">> match"
python3 -I "$ROOT/tools/port_symbols.py" "$WORK/src.sigs" "$WORK/dst.sigs" \
    -o "$WORK/ported.tsv" --min-instr "$MIN" --tail "$WORK/tail.txt"

echo ">> apply to target (writes back -- GUI must be closed)"
APPLY_LOG="$WORK/apply.log"; rc=0
"$HEADLESS" "$DP" "$DN" -process "$DPROG" -noanalysis -scriptPath "$SCR" \
    -postScript ApplyPortedNames.java "$WORK/ported.tsv" > "$APPLY_LOG" 2>&1 || rc=$?
grep -E "ApplyPortedNames:|Unable to lock|ERROR" "$APPLY_LOG" || true
if grep -q 'Unable to lock' "$APPLY_LOG"; then
  echo "!! target project is locked — close the Ghidra GUI and re-run." >&2; exit 1
fi
if [ "$rc" -ne 0 ] || ! grep -q 'ApplyPortedNames:' "$APPLY_LOG"; then
  echo "!! ApplyPortedNames failed (headless exit $rc) — see $APPLY_LOG" >&2; exit 1
fi
echo ">> manual tail for review: $WORK/tail.txt"

# --- same-image annotation replay (names by address, labels, comments, bookmarks, types) ---
# Only valid when source and target are the SAME image: ApplyAnnotations compares an image
# hash and applies nothing otherwise, so this is safe to run on every port.
echo ">> export annotations from source (read-only snapshot)"
snap="$(mktemp -d "${TMPDIR:-/tmp}/mk60snap.XXXXXX")"
cp "$SP/$SN.gpr" "$snap/"; cp -R "$SP/$SN.rep" "$snap/"
EXP_LOG="$WORK/export_annotations.log"; rc=0
"$HEADLESS" "$snap" "$SN" -process "$SPROG" -noanalysis -scriptPath "$SCR" \
    -postScript ExportAnnotations.java "$WORK/annotations.tsv" > "$EXP_LOG" 2>&1 || rc=$?
rm -rf "$snap"
grep -E "ExportAnnotations:|ERROR" "$EXP_LOG" | sed -E 's/^INFO  [A-Za-z0-9]+\.java> //; s/ \(GhidraScript\) *$//' || true
if [ "$rc" -ne 0 ] || [ ! -s "$WORK/annotations.tsv" ]; then
  echo "!! ExportAnnotations failed (headless exit $rc) — see $EXP_LOG" >&2; exit 1
fi
echo ">> apply annotations to target (same image only; writes back)"
ANN_LOG="$WORK/apply_annotations.log"; rc=0
"$HEADLESS" "$DP" "$DN" -process "$DPROG" -noanalysis -scriptPath "$SCR" \
    -postScript ApplyAnnotations.java "$WORK/annotations.tsv" > "$ANN_LOG" 2>&1 || rc=$?
grep -E "ApplyAnnotations:|Unable to lock|ERROR" "$ANN_LOG" | sed -E 's/^INFO  [A-Za-z0-9]+\.java> //; s/ \(GhidraScript\) *$//' || true
if [ "$rc" -ne 0 ] || ! grep -q 'ApplyAnnotations:' "$ANN_LOG"; then
  echo "!! ApplyAnnotations failed (headless exit $rc) — see $ANN_LOG" >&2; exit 1
fi
