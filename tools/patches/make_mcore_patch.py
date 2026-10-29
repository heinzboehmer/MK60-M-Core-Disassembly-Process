#!/usr/bin/env python3
"""Regenerate tools/patches/mcore.patch from the upstream CUB3D mcore.sinc.

Fixes (checked against binutils opcodes/mcore-opc.h and the DSCM90 image):
  1. addi/subi/cmplti take a one-based immediate (OIMM5 = field + 1).
  2. bmaski with field 0 means 32 (all ones), the usual way to load 0xFFFFFFFF.
  3. mulsh rx,ry (0x68yx): 16x16 signed multiply. Missing upstream.
  4. idly4 (0x0007). Missing upstream.
  5. jmpi/jsri constrain the wrong bitfield (op4_15 vs op8_15), so both never
     match and `lrw` swallows them -- M-CORE `lrw r0` IS jmpi and `lrw r15` IS
     jsri. Fixing this recovers thousands of indirect calls/tail-jumps.

Usage: python3 -I tools/patches/make_mcore_patch.py
"""
import difflib, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, "..", "ghidra-mcore", "MCORE", "data", "languages", "mcore.sinc")
OUT = os.path.join(HERE, "mcore.patch")

orig = open(SRC).read()
s = orig

def sub1(pattern, repl, flags=0):
    global s
    s, n = re.subn(pattern, repl, s, count=1, flags=flags)
    if n != 1:
        sys.exit("pattern not found: %r" % pattern)

# 1. one-based immediate operand
sub1(r"(attach variables \[ regY regX regZ \])",
     "# addi/subi/cmplti encode a one-based immediate (OIMM5 = field + 1, range 1..32),\n"
     "# per the M-CORE RM and binutils mcore-opc.h. The raw field alone is off by one.\n"
     "oneImm5: val is oimm5 [ val = oimm5 + 1; ] { export *[const]:4 val; }\n\n\\1")
for m in ("addi", "subi", "cmplti"):
    sub1(r"(:%s regX, )oimm5( is op9_15=0b\d+ & )oimm5( & regX \{\n\t[^\n]*?)oimm5" % m,
         r"\1oneImm5\2oneImm5\3oneImm5")

# 2. bmaski 32
sub1(r"(:bmaski regX, oimm5 is op9_15=0b0010110 & oimm5 & regX \{\n\tregX = \(1 << oimm5\) - 1;\n\})",
     "# bmaski field 0 encodes 32 (all ones).\n"
     ":bmaski regX, \"32\" is op9_15=0b0010110 & oimm5=0 & regX {\n"
     "\tregX = 0xFFFFFFFF;\n}\n\\1")

# 3. mulsh, 4. idly4 (appended next to mult / trap)
sub1(r"(:mult regX, regY is op8_15=0b00000011 & regY & regX \{\n\tregX = regX \* regY;\n\})",
     "\\1\n\n# mulsh / muls.h: 16x16 -> 32 signed multiply (binutils 0x6800, MULSH).\n"
     ":mulsh regX, regY is op8_15=0b01101000 & regY & regX {\n"
     "\tregX = sext(regX:2) * sext(regY:2);\n}")
sub1(r"(define pcodeop trap;)",
     "# idly4 (0x0007): idle-delay hint, no architectural effect we model.\n"
     ":idly4 is op0_15=0x0007 {\n}\n\n\\1")

# 5. jmpi/jsri bitfield: op4_15 (12-bit) can never equal an 8-bit value, so the
#    constructors are dead and lrw (op12_15=0b0111) decodes rz=0/rz=15 instead.
#    The correct constraint is op8_15 (bits 8-15).
sub1(r":jmpi disp8_label is op4_15=0b01110000 & disp8_label \{",
     ":jmpi disp8_label is op8_15=0b01110000 & disp8_label {")
sub1(r":jsri disp8_label is op4_15=0b01111111 & disp8_label \{",
     ":jsri disp8_label is op8_15=0b01111111 & disp8_label {")

# 6. psrclr/psrset (0x11F0-0x11FF): set/clear PSR fields. They occupy the mfcr
#    cr=31 encoding slot, but the low 4 bits are set/clear(bit3)+imm3, NOT a
#    register, so upstream wrongly decodes them as `mfcr rX,cr31` (inventing a
#    register write). Add fields + pcodeops + two constructors fixed on bits
#    15..3, which out-specify mfcr and win the 0x11F0-0x11FF slot.
sub1(r"(   op0_15 = \(0, 15\)\n)",
     r"\1   op3_15 = (3, 15)\n   psrimm = (0, 2)\n")
sub1(r"(oneImm5: val is oimm5 \[ val = oimm5 \+ 1; \] \{ export \*\[const\]:4 val; \}\n)",
     r"\1define pcodeop psrclr_op;\ndefine pcodeop psrset_op;\n")
sub1(r"(:mfcr regX, ctrlRegY is op9_15=0b0001000 & ctrlRegY & regX \{)",
     "# psrclr/psrset: imm3 selects the PSR field. More specific than mfcr\n"
     "# (bits 15..3 fixed), so they take the 0x11F0-0x11FF (cr=31) encodings.\n"
     ":psrclr psrimm is op3_15=0b0001000111110 & psrimm {\n"
     "\tpsrclr_op(psrimm:1);\n}\n"
     ":psrset psrimm is op3_15=0b0001000111111 & psrimm {\n"
     "\tpsrset_op(psrimm:1);\n}\n\n\\1")

diff = difflib.unified_diff(orig.splitlines(True), s.splitlines(True),
                            "a/mcore.sinc", "b/mcore.sinc")
open(OUT, "w").write("".join(diff))
print("wrote", OUT)
