#!/usr/bin/env bash
# Install the CUB3D M-CORE SLEIGH processor module into the active Ghidra and
# compile it. Idempotent — re-run after a `brew upgrade ghidra` (which wipes the
# Cellar and the compiled .sla). Verified against Ghidra 12.2 on 2026-10-25.
#
# Language it adds:  MCORE:BE:32:default  (big-endian, 32-bit)
set -euo pipefail

SRC="$(cd "$(dirname "$0")/ghidra-mcore/MCORE" && pwd)"
GH_LIBEXEC="${GHIDRA_HOME:-/opt/homebrew/opt/ghidra/libexec}"   # arm64 brew; override with GHIDRA_HOME
PROC_DIR="$GH_LIBEXEC/Ghidra/Processors"
DST="$PROC_DIR/MCORE"

[ -d "$SRC" ]        || { echo "source module not found: $SRC" >&2; exit 1; }
[ -d "$PROC_DIR" ]   || { echo "Ghidra Processors dir not found: $PROC_DIR" >&2; exit 1; }

echo "Installing module -> $DST"
rm -rf "$DST"
cp -R "$SRC" "$DST"

# Our compiler spec (calling convention, return address) — the module ships none
# usable for this image, and it lives in a gitignored clone, so the tracked copy
# in tools/patches/ is authoritative.
cp "$(dirname "$0")/patches/mcore.cspec" "$DST/data/languages/mcore.cspec"

# Local fixes to the upstream module (tracked in tools/patches/).
for p in "$(dirname "$0")"/patches/*.patch; do
    [ -e "$p" ] || continue
    echo "Applying $(basename "$p")"
    patch -s -p1 -d "$DST/data/languages" < "$p"
done

echo "Compiling SLEIGH (.slaspec -> .sla) ..."
"$GH_LIBEXEC/support/sleigh" -a "$DST/data/languages"

echo
if [ -f "$DST/data/languages/mcore.sla" ]; then
    echo "OK: mcore.sla built. Language MCORE:BE:32:default is now available."
else
    echo "ERROR: mcore.sla was not produced." >&2
    exit 1
fi
