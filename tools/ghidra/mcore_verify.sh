#!/usr/bin/env bash
# Verify + quantify a recovered project in ONE read-only run. Works with the Ghidra GUI open:
# the scanners run against a snapshot copy of the project (nothing is written back).
#
# Usage:
#   tools/ghidra/mcore_verify.sh <proj_dir> <name> <cpu_hex_path>
#
# Bounds come from <proj_dir>/<prog>.bounds (written by mcore_recover.sh) or, failing that,
# from find_boundary.py. Runs CountErr, ScanMisdisasm, ImageMap, RegionStats (the region
# below codeLO that the pipeline leaves alone) and DataTableInventory, then prints a summary.
# Set FULL=1 to also print every DataTableInventory row (one per code-referenced table base).
#
# Exit status: 0 = healthy (0 error bookmarks, 0 mis-disassembled tables, 0 code bytes in
# the data section, 0 referenced code runs owned by no function); 4 = one of those is
# non-zero (details printed).
set -euo pipefail
[ $# -ge 3 ] || { sed -n '2,14p' "$0"; exit 2; }

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PROJ_DIR="$1"; NAME="$2"; HEX="$3"; PROG="$(basename "$HEX")"
HEADLESS="${GHIDRA_HOME:-/opt/homebrew/opt/ghidra/libexec}/support/analyzeHeadless"
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home}"
SIDECAR="$PROJ_DIR/$PROG.bounds"

if [ -f "$SIDECAR" ]; then
  CODE_LO="$(sed -n 's/^CODE_LO=\([0-9a-fA-F]*\).*/\1/p' "$SIDECAR")"
  DATA_LO="$(sed -n 's/^DATA_LO=\([0-9a-fA-F]*\).*/\1/p' "$SIDECAR")"
  DATA_HI="$(sed -n 's/^DATA_HI=\([0-9a-fA-F]*\).*/\1/p' "$SIDECAR")"
  echo ">> bounds from $SIDECAR: codeLO=$CODE_LO dataLO=$DATA_LO dataHI=$DATA_HI"
else
  BND="$(python3 -I "$ROOT/tools/find_boundary.py" "$HEX" | grep '^BOUNDS' || true)"
  pick(){ echo "$BND" | sed -n "s/.*$1=0x\([0-9a-fA-F]*\).*/\1/p"; }
  CODE_LO="$(pick codeLO)"; DATA_LO="$(pick codeHI)"; DATA_HI="$(pick dataHI)"
  echo ">> no $SIDECAR (mcore_recover.sh not run yet?); using find_boundary estimate: codeLO=$CODE_LO codeHI=$DATA_LO dataHI=$DATA_HI"
fi
[ -n "$CODE_LO" ] && [ -n "$DATA_LO" ] && [ -n "$DATA_HI" ] || { echo "!! could not determine bounds" >&2; exit 1; }

SNAP="$(mktemp -d "${TMPDIR:-/tmp}/mk60snap.XXXXXX")"
trap 'rm -rf "$SNAP"' EXIT
cp "$PROJ_DIR/$NAME.gpr" "$SNAP/"
cp -R "$PROJ_DIR/$NAME.rep" "$SNAP/"

LOG="$(mktemp "$SNAP/verify.XXXXXX")"
rc=0
"$HEADLESS" "$SNAP" "$NAME" \
    -process "$PROG" -noanalysis \
    -scriptPath "$ROOT/tools/ghidra/scripts" \
    -postScript CountErr.java \
    -postScript ScanMisdisasm.java "$CODE_LO" "$DATA_LO" \
    -postScript ImageMap.java \
    -postScript RegionStats.java 40000 "$CODE_LO" \
    -postScript RegionStats.java "$CODE_LO" "$DATA_LO" \
    -postScript RegionStats.java "$DATA_LO" "$DATA_HI" \
    -postScript DataTableInventory.java "$DATA_LO" "$DATA_HI" > "$LOG" 2>&1 || rc=$?

out(){ sed -nE "s/^INFO  $1\.java> (.*) \(GhidraScript\) *$/\1/p" "$LOG"; }

if [ "$rc" -ne 0 ] || ! grep -q 'Error bookmarks=' "$LOG"; then
  echo "!! verify run failed (headless exit $rc)" >&2; grep -E 'ERROR|Exception' "$LOG" | head -20 >&2 || true; exit 1
fi

echo; echo "=== ImageMap ==="; out ImageMap
echo; echo "=== ScanMisdisasm (code region $CODE_LO..$DATA_LO) ==="; out ScanMisdisasm
echo; echo "=== RegionStats ==="; out RegionStats | grep -E '^RegionStats'
if [ "${FULL:-}" = 1 ]; then echo; echo "=== DataTableInventory ==="; out DataTableInventory; fi

ERRS="$(out CountErr | sed -n 's/.*Error bookmarks=\([0-9]*\).*/\1/p')"
FNS="$(out CountErr | sed -n 's/.*functions=\([0-9]*\).*/\1/p')"
MISRUNS="$(out ScanMisdisasm | sed -n 's/.*runs=\([0-9]*\).*/\1/p')"
BASES="$(out DataTableInventory | sed -n 's/^TOTAL_BASES\t\([0-9]*\).*/\1/p')"
DATACODE="$(out RegionStats | grep "^RegionStats 0*${DATA_LO}-" | sed -n 's/.*instrBytes=\([0-9]*\).*/\1/p')"
LOWFNS="$(out RegionStats | grep "^RegionStats 040000-" | sed -n 's/.*functions=\([0-9]*\).*/\1/p')"
CODELINE="$(out RegionStats | grep "^RegionStats 0*${CODE_LO}-")"
ORPHAN="$(echo "$CODELINE" | sed -n 's/.*orphanInstrBytes=\([0-9]*\).*/\1/p')"
ORPHANREFD="$(echo "$CODELINE" | sed -n 's/.*orphanRefdRuns=\([0-9]*\).*/\1/p')"
CODEBYTES="$(echo "$CODELINE" | sed -n 's/.*instrBytes=\([0-9]*\).*/\1/p')"

echo
echo "=== SUMMARY ($NAME / $PROG) ==="
printf '%-42s %s   (want 0)\n'  "error bookmarks"                         "${ERRS:-?}"
printf '%-42s %s   (want 0)\n'  "mis-disassembled pointer-table runs"     "${MISRUNS:-?}"
printf '%-42s %s   (want 0)\n'  "code bytes in data section $DATA_LO..$DATA_HI" "${DATACODE:-?}"
printf '%-42s %s   (want low thousands)\n' "functions"                    "${FNS:-?}"
printf '%-42s %s of %s   (unreferenced speculative decodes; informational)\n' "code bytes owned by no function" "${ORPHAN:-?}" "${CODEBYTES:-?}"
printf '%-42s %s   (want 0)\n'  "orphan code runs referenced by data/functions" "${ORPHANREFD:-?}"
printf '%-42s %s\n'             "code-referenced table bases"             "${BASES:-?}"
printf '%-42s %s   (auto-analysis noise below codeLO; left alone by design)\n' "functions in 40000..$CODE_LO" "${LOWFNS:-?}"

status=0
[ "${ERRS:-1}" -eq 0 ] || status=4
[ "${MISRUNS:-1}" -eq 0 ] || status=4
[ "${DATACODE:-1}" -eq 0 ] || status=4
[ "${ORPHANREFD:-1}" -eq 0 ] || status=4
[ "$status" -eq 0 ] && echo ">> HEALTHY" || echo "!! NOT CLEAN — re-run mcore_recover.sh (it resumes and converges)" >&2
exit "$status"
