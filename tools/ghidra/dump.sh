#!/usr/bin/env bash
# Dump disassembly from the analyzed project, e.g.:
#   tools/ghidra/dump.sh 6C906:60 3AC:40
# Runs DumpAt.java against a snapshot copy of build/ghidra_proj (so it works
# while the GUI has the project open). Nothing is written back.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HEADLESS="${GHIDRA_HOME:-/opt/homebrew/opt/ghidra/libexec}/support/analyzeHeadless"
export JAVA_HOME="${JAVA_HOME:-/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home}"

SNAP="$(mktemp -d "${TMPDIR:-/tmp}/mk60snap.XXXXXX")"
trap 'rm -rf "$SNAP"' EXIT
cp "$ROOT/build/ghidra_proj/MK60_DSCM90.gpr" "$SNAP/"
cp -R "$ROOT/build/ghidra_proj/MK60_DSCM90.rep" "$SNAP/"

"$HEADLESS" "$SNAP" MK60_DSCM90 \
    -process 7846816A.cpu.hex -noanalysis \
    -scriptPath "$ROOT/tools/ghidra/scripts" \
    -postScript DumpAt.java "$@" 2>&1 \
  | sed -nE 's/^INFO  DumpAt\.java> (.*) \(GhidraScript\) *$/\1/p'
