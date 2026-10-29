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
  "$HEADLESS" "$snap" "$2" -process "$3" -noanalysis -scriptPath "$SCR" \
      -postScript ExportSigs.java "$4" 2>&1 | grep -E "ExportSigs:|ERROR" || true
  rm -rf "$snap"
}

echo ">> export signatures"
sigs "$SP" "$SN" "$SPROG" "$WORK/src.sigs"
sigs "$DP" "$DN" "$DPROG" "$WORK/dst.sigs"

echo ">> match"
python3 -I "$ROOT/tools/port_symbols.py" "$WORK/src.sigs" "$WORK/dst.sigs" \
    -o "$WORK/ported.tsv" --min-instr "$MIN" --tail "$WORK/tail.txt"

echo ">> apply to target (writes back -- GUI must be closed)"
"$HEADLESS" "$DP" "$DN" -process "$DPROG" -noanalysis -scriptPath "$SCR" \
    -postScript ApplyPortedNames.java "$WORK/ported.tsv" 2>&1 \
  | grep -E "ApplyPortedNames:|Unable to lock|ERROR" || true
echo ">> manual tail for review: $WORK/tail.txt"
