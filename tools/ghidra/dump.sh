#!/usr/bin/env bash
# Dump disassembly from an analyzed project, e.g.:
#   tools/ghidra/dump.sh <proj_dir> <name> <cpu_hex_path> 6C906:60 3AC:40
# Runs DumpAt.java against a snapshot copy of the project (so it works while the
# GUI has the project open). Nothing is written back.
set -euo pipefail
[ $# -ge 4 ] || { sed -n '2,5p' "$0"; exit 2; }

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PROJ_DIR="$1"; NAME="$2"; PROG="$(basename "$3")"; shift 3
HEADLESS="${GHIDRA_HOME:-/opt/homebrew/opt/ghidra/libexec}/support/analyzeHeadless"
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home}"

SNAP="$(mktemp -d "${TMPDIR:-/tmp}/mk60snap.XXXXXX")"
trap 'rm -rf "$SNAP"' EXIT
cp "$PROJ_DIR/$NAME.gpr" "$SNAP/"
cp -R "$PROJ_DIR/$NAME.rep" "$SNAP/"

"$HEADLESS" "$SNAP" "$NAME" \
    -process "$PROG" -noanalysis \
    -scriptPath "$ROOT/tools/ghidra/scripts" \
    -postScript DumpAt.java "$@" 2>&1 \
  | sed -nE 's/^INFO  DumpAt\.java> (.*) \(GhidraScript\) *$/\1/p'
