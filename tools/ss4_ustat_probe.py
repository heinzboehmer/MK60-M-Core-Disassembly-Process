#!/usr/bin/env python3
"""Read-only SS4 supervisor-escape probe for the E46 non-M MK60.

Builds a RAM-exec M·CORE stub and prints dry-run manufacturing frames for
the factory K-line path. It does not open a serial port, does not write
the flash controller, and does not erase or program flash.

The stub is executed from user mode by the mfg RAM jsr (class 0xA6 'O').
Its first halfword is 0x10A1, ``mfcr r1, SS4``. On this firmware a
user-mode execute of that halfword takes the privilege-violation handler,
which elevates and ``rte``s so the next instruction runs supervisor. That
instruction halfword-reads SGFMUSTAT at 0x00D0001C (stock SGFM block
0x00D00000 + 0x1C; idle status byte about 0xC0, CBEIF|CCIF), stores the
raw halfword at scratch+0 and marker 0x55A4 at scratch+2, then ``jmp r15``.

Upload is an 0xA5 write of that image. 0xA6 'K'/'L' are not emitted: 'K'
checks a live session byte XOR 0x23, and the notes do not specify the
escaped-frame body. Exec is 0xA6 'O'. Readback is 0xA4 of the four scratch
bytes. Protocol notes: analysis/mfg_test_cycle.md, analysis/nonm_mk60_image.md,
analysis/zcp_mfg_exec_path.md, analysis/e46_m3_mk60_kline_access_and_flash.md.

Usage:

  python3 tools/ss4_ustat_probe.py
  python3 tools/ss4_ustat_probe.py --variant 820.3
  python3 tools/ss4_ustat_probe.py --descriptor 0x401142 --scratch 0x401168
  python3 tools/ss4_ustat_probe.py --check

The framed hex is for an existing K-line sender. Length and the trailing
sum byte are stated with the assumptions below; drop them if the sender
adds its own.
"""

from __future__ import annotations

import argparse
import sys
from dataclasses import dataclass

# Encodings follow tools/ghidra-mcore/.../mcore.sinc (bit 0 is the LSB).
# mfcr r1, SS4 is the halfword the bench escape is built around.
SS4 = 10  # PSR=0, VBR=1, EPSR=2, FPSR=3, EPC=4, FPC=5, SS0..SS3=6..9, SS4=10

SGFM_BASE = 0x00D00000
SGFM_USTAT = 0x00D0001C
MARKER = 0x55A4

RAM_LO = 0x00400000
RAM_HI = 0x00401800  # exclusive; nam3forum window cited in the K-line note
WHEEL_LO = 0x00401700  # four 0x40-byte wheel objects
WHEEL_HI = RAM_HI
SESSION_LO = 0x00400500  # live mfg request/session block
SESSION_HI = 0x00400800
A5_U_MAX = 24

# level-3 'O' descriptor (818.3) and the 820.3 mfg_rx_descriptor buffer.
VARIANTS = {
    "818.3": 0x00401142,
    "820.3": 0x0040101C,
}


def enc_mfcr(reg: int, cr: int) -> int:
    return (0b0001000 << 9) | (cr << 4) | reg


def enc_lrw(reg: int, disp: int) -> int:
    if not 0 <= disp <= 0xFF:
        raise ValueError(f"lrw disp {disp} does not fit in 8 bits")
    return (0b0111 << 12) | (reg << 8) | disp


def enc_ldh(rz: int, rx: int, disp: int) -> int:
    if disp & 1 or not 0 <= disp <= 30:
        raise ValueError(f"ld.h disp {disp} is not an even 0..30")
    return (1 << 15) | (0b10 << 13) | (rz << 8) | ((disp >> 1) << 4) | rx


def enc_sth(rz: int, rx: int, disp: int) -> int:
    if disp & 1 or not 0 <= disp <= 30:
        raise ValueError(f"st.h disp {disp} is not an even 0..30")
    return (1 << 15) | (0b10 << 13) | (1 << 12) | (rz << 8) | ((disp >> 1) << 4) | rx


def enc_jmp(reg: int) -> int:
    return (0b000000001100 << 4) | reg


def lrw_slot(pc: int, disp: int) -> int:
    """Pool address used by this repo's image notes: ((pc + 2) & ~3) + disp*4."""
    return ((pc + 2) & ~3) + (disp << 2)


def stub_size(base: int) -> int:
    """Bytes of code + align pad + three literal-pool words. Independent of the constants."""
    code = 16  # eight halfwords, see assemble_stub
    pool = (base + code + 3) & ~3
    return (pool - base) + 12


def assemble_stub(base: int, scratch: int, ustat: int, marker: int) -> bytes:
    """Stub image that must be placed at `base` (halfword-aligned).

    Layout: mfcr escape, lrw/ld.h of USTAT, lrw/st.h of the status halfword,
    lrw/st.h of the marker, jmp r15, then a word-aligned pool.
    """
    if base & 1:
        raise ValueError(f"stub base 0x{base:X} is not halfword-aligned")
    items: list[tuple] = [
        ("h", enc_mfcr(1, SS4)),
        ("lrw", 2, ustat),
        ("h", enc_ldh(3, 2, 0)),
        ("lrw", 2, scratch),
        ("h", enc_sth(3, 2, 0)),
        ("lrw", 3, marker & 0xFFFFFFFF),
        ("h", enc_sth(3, 2, 2)),
        ("h", enc_jmp(15)),
    ]
    code_len = 2 * len(items)
    pool_addr = (base + code_len + 3) & ~3
    code = bytearray(code_len)
    pool = bytearray()
    off = 0
    for item in items:
        if item[0] == "h":
            code[off : off + 2] = item[1].to_bytes(2, "big")
        else:
            _tag, reg, value = item
            slot = pool_addr + len(pool)
            disp = (slot - lrw_slot(base + off, 0)) >> 2
            code[off : off + 2] = enc_lrw(reg, disp).to_bytes(2, "big")
            pool += (value & 0xFFFFFFFF).to_bytes(4, "big")
        off += 2
    pad = b"\x00" * (pool_addr - (base + code_len))
    return bytes(code) + pad + bytes(pool)


@dataclass
class Probe:
    variant: str
    descriptor: int
    stub_base: int
    scratch: int
    marker: int
    ustat: int
    stub: bytes
    plant: list[tuple[int, bytes]]  # (address, bytes) written by A5


def default_scratch(descriptor: int, stub_base: int) -> int:
    n = stub_size(stub_base)
    if stub_base == descriptor + 6:
        end = descriptor + 6 + n
    else:
        end = max(descriptor + 2, stub_base + n)
    return (end + 4 + 3) & ~3


def plant_image(descriptor: int, stub_base: int, stub: bytes) -> list[tuple[int, bytes]]:
    """Bytes that make ``jsr`` land on the stub.

    mfg_rx_service does ``target = descriptor + ld.h(descriptor) + 6``.
    A zero halfword puts the stub at descriptor+6. The four bytes in
    between are not executed (r2 enters pointing at descriptor+2).
    """
    delta = stub_base - (descriptor + 6)
    if not -0x8000 <= delta <= 0x7FFF:
        raise SystemExit(
            f"stub 0x{stub_base:X} is more than a signed halfword from "
            f"descriptor+6 (0x{descriptor + 6:X})"
        )
    half = (delta & 0xFFFF).to_bytes(2, "big")
    if stub_base == descriptor + 6:
        return [(descriptor, half + b"\x00\x00\x00\x00" + stub)]
    return [(descriptor, half), (stub_base, stub)]


def _span_hits(addr: int, n: int, lo: int, hi: int) -> bool:
    return addr < hi and addr + n > lo


def check_placement(probe: Probe) -> None:
    blobs = list(probe.plant) + [(probe.scratch, b"\x00\x00\x00\x00")]
    for addr, data in blobs:
        end = addr + len(data)
        if addr < RAM_LO or end > RAM_HI:
            raise SystemExit(
                f"0x{addr:X}..0x{end - 1:X} is outside RAM "
                f"0x{RAM_LO:X}..0x{RAM_HI - 1:X}"
            )
        if _span_hits(addr, len(data), WHEEL_LO, WHEEL_HI):
            raise SystemExit(
                f"0x{addr:X} overlaps the wheel objects at 0x{WHEEL_LO:X}; "
                "pass a different --scratch or --stub-base"
            )
        if _span_hits(addr, len(data), SESSION_LO, SESSION_HI):
            raise SystemExit(
                f"0x{addr:X} overlaps the live mfg session/request block "
                f"0x{SESSION_LO:X}..0x{SESSION_HI - 1:X}"
            )
    # Scratch must not sit inside a planted image.
    s0, s1 = probe.scratch, probe.scratch + 4
    for addr, data in probe.plant:
        if _span_hits(addr, len(data), s0, s1):
            raise SystemExit("scratch overlaps the planted stub; pick another --scratch")


def simulate(stub: bytes, base: int, scratch: int, ustat: int, marker: int) -> None:
    """Walk the stub. The escape halfword is consumed; later insns must
    halfword-load `ustat` and halfword-store that value plus the marker.
    """
    if stub[:2] != enc_mfcr(1, SS4).to_bytes(2, "big"):
        raise SystemExit("stub does not start with mfcr r1, SS4 (0x10A1)")
    regs = [0] * 16
    writes: list[tuple[int, int]] = []
    loaded: int | None = None
    pc = base
    saw_jmp = False
    for _ in range(24):
        off = pc - base
        if off < 0 or off + 2 > len(stub):
            raise SystemExit(f"pc 0x{pc:X} left the stub image")
        hw = int.from_bytes(stub[off : off + 2], "big")
        if pc == base:
            if hw != 0x10A1:
                raise SystemExit(f"first halfword is 0x{hw:04X}, expected 0x10A1")
            pc += 2
            continue
        if hw == enc_jmp(15):
            saw_jmp = True
            break
        if (hw & 0xF000) == 0x7000:
            reg = (hw >> 8) & 0xF
            slot = lrw_slot(pc, hw & 0xFF)
            rel = slot - base
            if rel < 0 or rel + 4 > len(stub):
                raise SystemExit(f"lrw at 0x{pc:X} pool 0x{slot:X} is outside the stub")
            regs[reg] = int.from_bytes(stub[rel : rel + 4], "big")
            pc += 2
            continue
        top = hw >> 12
        rz = (hw >> 8) & 0xF
        imm = (hw >> 4) & 0xF
        rx = hw & 0xF
        if top == 0xC:  # ld.h
            addr = (regs[rx] + (imm << 1)) & 0xFFFFFFFF
            if addr != ustat:
                raise SystemExit(f"ld.h from 0x{addr:X}, expected USTAT 0x{ustat:X}")
            loaded = 0xC0C0  # stand-in halfword; low 16 bits must be stored intact
            regs[rz] = loaded
            pc += 2
            continue
        if top == 0xD:  # st.h
            addr = (regs[rx] + (imm << 1)) & 0xFFFFFFFF
            writes.append((addr, regs[rz] & 0xFFFF))
            pc += 2
            continue
        raise SystemExit(f"unexpected halfword 0x{hw:04X} at 0x{pc:X}")
    if not saw_jmp:
        raise SystemExit("stub does not return with jmp r15")
    if loaded is None:
        raise SystemExit("stub never loaded USTAT")
    expect = [(scratch, loaded & 0xFFFF), (scratch + 2, marker & 0xFFFF)]
    if writes != expect:
        raise SystemExit(f"stores {writes} != {expect}")
    # No store may touch the flash-controller window.
    for addr, _value in writes:
        if SGFM_BASE <= addr < SGFM_BASE + 0x1000:
            raise SystemExit(f"stub stores to flash-controller address 0x{addr:X}")


def verify(probe: Probe) -> None:
    if probe.marker > 0xFFFF:
        raise SystemExit("marker must fit in the halfword stored at scratch+2")
    if probe.marker in (0x0000, 0xA5A5, 0xFFFF):
        raise SystemExit(
            f"marker 0x{probe.marker:04X} is a poor witness "
            "(zero, erased, or the 0xA5A5 stack fill)"
        )
    if probe.scratch & 1 or probe.ustat & 1:
        raise SystemExit("scratch and USTAT must be halfword-aligned")
    check_placement(probe)
    simulate(probe.stub, probe.stub_base, probe.scratch, probe.ustat, probe.marker)
    # The planted image must actually contain this stub.
    found = False
    for addr, data in probe.plant:
        rel = probe.stub_base - addr
        if 0 <= rel and data[rel : rel + len(probe.stub)] == probe.stub:
            found = True
    if not found:
        raise SystemExit("plant image does not contain the stub at stub_base")


def build(
    variant: str,
    descriptor: int | None = None,
    stub_base: int | None = None,
    scratch: int | None = None,
    marker: int = MARKER,
    ustat: int = SGFM_USTAT,
) -> Probe:
    if variant not in VARIANTS and descriptor is None:
        raise SystemExit(f"unknown variant {variant!r}; use 818.3 or 820.3, or pass --descriptor")
    if descriptor is None:
        descriptor = VARIANTS[variant]
    if stub_base is None:
        stub_base = descriptor + 6
    if stub_base & 1 or descriptor & 1:
        raise SystemExit(
            f"descriptor 0x{descriptor:X} and stub base 0x{stub_base:X} must be halfword-aligned"
        )
    if scratch is None:
        scratch = default_scratch(descriptor, stub_base)
    stub = assemble_stub(stub_base, scratch, ustat, marker)
    probe = Probe(
        variant=variant,
        descriptor=descriptor,
        stub_base=stub_base,
        scratch=scratch,
        marker=marker,
        ustat=ustat,
        stub=stub,
        plant=plant_image(descriptor, stub_base, stub),
    )
    verify(probe)
    return probe


def hex_bytes(data: bytes) -> str:
    return " ".join(f"{b:02X}" for b in data)


def addr8(addr: int) -> bytes:
    return f"{addr:08X}".encode("ascii")


def framed(payload: bytes) -> bytes:
    """length | payload | sum.

    ``length`` counts payload bytes only. An 0xA6 'Z' request of length 2
    is class+sub and selects command 0x7B (nonm_mk60_image.md), so the
    length byte is not counting itself or the sum.

    The sum byte makes the unsigned sum of the whole frame 0. The notes
    call this a one-byte sum check; they do not publish a second's-complement
    versus low-8-bits variant. Drop the last byte if the sender adds its own.
    """
    if not 1 <= len(payload) <= 255:
        raise ValueError(f"payload length {len(payload)} does not fit one length byte")
    body = bytes([len(payload)]) + payload
    return body + bytes([(-sum(body)) & 0xFF])


def frames_a5_u(addr: int, data: bytes) -> list[bytes]:
    """0xA5 'U' chunks.

    Request layout from the non-M buffer offsets: eight ASCII-hex address
    characters at 0x4005FC, copy source at 0x400605. The byte between them
    (0x400604) is sent as the raw count N, 1..24, and N bytes follow.
    """
    out = []
    off = 0
    while off < len(data):
        chunk = data[off : off + A5_U_MAX]
        params = addr8(addr + off) + bytes([len(chunk)]) + chunk
        out.append(framed(bytes([0xA5, ord("U")]) + params))
        off += len(chunk)
    return out


def frame_a3(level: int, extra: bytes = b"") -> bytes:
    return framed(bytes([0xA3, ord(str(level))]) + extra)


def frame_a4(sub: str, addr: int, length: int) -> bytes:
    params = addr8(addr) + f"{length:08X}".encode("ascii")
    return framed(bytes([0xA4, ord(sub)]) + params)


def frame_a6_o() -> bytes:
    return framed(bytes([0xA6, ord("O")]))


def disassemble(stub: bytes, base: int) -> list[str]:
    lines = []
    off = 0
    while off + 2 <= len(stub):
        pc = base + off
        hw = int.from_bytes(stub[off : off + 2], "big")
        if off == 0 and hw == 0x10A1:
            lines.append(f"  {pc:08X}: 10A1  mfcr r1, ss4     ; privilege escape, rte to next")
            off += 2
            continue
        if hw == enc_jmp(15):
            lines.append(f"  {pc:08X}: 00CF  jmp r15")
            off += 2
            break
        if (hw & 0xF000) == 0x7000:
            reg = (hw >> 8) & 0xF
            slot = lrw_slot(pc, hw & 0xFF)
            rel = slot - base
            word = int.from_bytes(stub[rel : rel + 4], "big") if 0 <= rel <= len(stub) - 4 else None
            shown = f"0x{word:08X}" if word is not None else "?"
            lines.append(f"  {pc:08X}: {hw:04X}  lrw r{reg}, 0x{slot:08X}  ; = {shown}")
            off += 2
            continue
        top = hw >> 12
        rz = (hw >> 8) & 0xF
        imm = (hw >> 4) & 0xF
        rx = hw & 0xF
        if top == 0xC:
            lines.append(f"  {pc:08X}: {hw:04X}  ld.h r{rz}, (r{rx}, {imm << 1})")
            off += 2
            continue
        if top == 0xD:
            lines.append(f"  {pc:08X}: {hw:04X}  st.h r{rz}, (r{rx}, {imm << 1})")
            off += 2
            continue
        lines.append(f"  {pc:08X}: {hw:04X}  .half")
        off += 2
    while off + 2 <= len(stub) and (base + off) & 3:
        pc = base + off
        hw = int.from_bytes(stub[off : off + 2], "big")
        lines.append(f"  {pc:08X}: {hw:04X}  .align          ; not executed")
        off += 2
    while off + 4 <= len(stub):
        pc = base + off
        word = int.from_bytes(stub[off : off + 4], "big")
        lines.append(f"  {pc:08X}: {word:08X}  .word")
        off += 4
    return lines


def _frame_block(title: str, frame: bytes) -> list[str]:
    payload = frame[1:-1]
    return [
        f"  {title}",
        f"    payload {hex_bytes(payload)}",
        f"    frame   {hex_bytes(frame)}",
    ]


def format_report(probe: Probe) -> str:
    delta = probe.stub_base - (probe.descriptor + 6)
    lines = [
        "SS4 supervisor-escape USTAT probe (read-only)",
        "Does not write the flash controller and does not erase or program.",
        f"variant     {probe.variant}",
        f"descriptor  0x{probe.descriptor:08X}   u16 offset 0x{delta & 0xFFFF:04X}",
        f"stub        0x{probe.stub_base:08X}   jsr target = descriptor + offset + 6",
        f"scratch     0x{probe.scratch:08X}   +0 status halfword, +2 marker 0x{probe.marker:04X}",
        f"USTAT       0x{probe.ustat:08X}   idle byte about 0xC0 (CBEIF|CCIF); record the raw halfword",
        "",
        "stub bytes (big-endian, first halfword 0x10A1):",
        f"  {hex_bytes(probe.stub)}",
        "",
        "disassembly:",
        *disassemble(probe.stub, probe.stub_base),
        "",
        "plant these bytes with 0xA5, then 0xA6 'O'. The elevated PSR is still",
        "set when the stub returns; this stub does not clear the supervisor bit.",
    ]
    for addr, data in probe.plant:
        lines.append(f"  at 0x{addr:08X}, {len(data)} bytes:")
        lines.append(f"  {hex_bytes(data)}")
    lines += [
        "",
        "Bench (factory K-line, 8N1, knock baud about 4800, else 9600).",
        "One byte per 10 ms tick during the knock: 15-20 ms gaps. Repeat the",
        "knock about every 50 ms for a few seconds after the KL15 rising edge.",
        "Framed mode starts after the handshake counter passes 40 (~400 ms).",
        "Idle timeout on this unit is about 14 s; a valid frame reloads it.",
        "Do not set mode-byte bit 7 (0xA0 '4' parameter '1'); 0xA6 'O' requires it clear.",
        "",
        "Length counts class+sub+parameters. The last byte is a sum-to-zero",
        "check. 0xA5 'U' puts a raw count at the byte before the copy source",
        "(request offset 12 / RAM 0x400605) and then that many data bytes.",
        "0xA6 'K' and 'L' are not framed here.",
        "",
        "1. KL15 on, knock  40 01 02 30    (@ 0x01 0x02 '0')",
        "   '0' keeps the knock-phase baud. Expect 'j', then wait ~400 ms.",
        "",
    ]
    lines += _frame_block(
        "2. 0xA3 level 2 (free on this E46; 0xA5 needs it). Expect reply tag 'f' and digit '2'.",
        frame_a3(2),
    )
    lines.append("")
    lines += _frame_block(
        "3. 0xA3 level 3, fixed pair '16'. Expect 'f' '3'.",
        frame_a3(3, b"16"),
    )
    lines += [
        "   The level-3 transition runs the control reset and can clear the descriptor,",
        "   so plant only after that reply. Level 3 masks interrupts for the rest of the session.",
        "",
    ]
    for addr, data in probe.plant:
        off = 0
        chunk_i = 0
        frames = frames_a5_u(addr, data)
        for fr in frames:
            chunk = data[off : off + A5_U_MAX]
            lines += _frame_block(
                f"4.{chunk_i}  0xA5 'U'  {len(chunk)} bytes at 0x{addr + off:08X}",
                fr,
            )
            lines.append("")
            off += len(chunk)
            chunk_i += 1
    lines += _frame_block(
        f"5. 0xA4 'T' baseline of scratch (4 bytes as hex pairs). Marker 0x{probe.marker:04X} must be absent.",
        frame_a4("T", probe.scratch, 4),
    )
    lines.append("")
    lines += _frame_block(
        "5b. 0xA4 'U' of the same 4 bytes, if the reply should be raw instead of hex pairs.",
        frame_a4("U", probe.scratch, 4),
    )
    lines.append("")
    lines += _frame_block(
        "6. 0xA6 'O'  execute. Level is already >= 3 and mode bit 7 is clear.",
        frame_a6_o(),
    )
    lines.append("")
    lines += _frame_block(
        "7. Repeat the step-5 0xA4 'T' (or 'U').",
        frame_a4("T", probe.scratch, 4),
    )
    lines += [
        "",
        f"Readback: scratch+0 = SGFMUSTAT halfword, scratch+2 = 0x{probe.marker:04X}.",
        "Marker present means the stub stored and returned. The halfword is the",
        "raw supervisor read (0x00C0 or 0xC000 are the idle shapes to compare).",
        "Marker absent means the jsr or the escape did not run. Stop there.",
        "This probe has no program or erase command.",
    ]
    return "\n".join(lines) + "\n"


def _assert_frame(frame: bytes) -> None:
    if sum(frame) & 0xFF:
        raise SystemExit("frame sum is not 0")
    if frame[0] != len(frame) - 2:
        raise SystemExit("length byte does not count payload only")


def self_check() -> None:
    if enc_mfcr(1, SS4) != 0x10A1:
        raise SystemExit("mfcr r1, SS4 did not encode as 0x10A1")
    if enc_jmp(15) != 0x00CF or enc_lrw(2, 3) != 0x7203:
        raise SystemExit("jmp/lrw encoding drifted")
    if enc_ldh(3, 2, 0) != 0xC302 or enc_sth(3, 2, 0) != 0xD302 or enc_sth(3, 2, 2) != 0xD312:
        raise SystemExit("ld.h/st.h encoding drifted")
    if lrw_slot(0x40114A, 3) != 0x401158:
        raise SystemExit("lrw slot formula drifted")

    a6 = frame_a6_o()
    if a6 != bytes.fromhex("02 A6 4F 09"):
        raise SystemExit(f"A6 O frame is {a6.hex()}, expected 02a64f09")
    _assert_frame(a6)
    _assert_frame(frame_a3(2))
    level3 = frame_a3(3, b"16")
    _assert_frame(level3)
    if b"16" not in level3:
        raise SystemExit("level-3 frame is missing the '16' pair")

    p818 = build("818.3")
    if p818.descriptor != 0x401142 or p818.stub_base != 0x401148:
        raise SystemExit("818.3 placement drifted")
    if p818.stub[:4] != bytes.fromhex("10A17203") or p818.stub[4:6] != bytes.fromhex("C302"):
        raise SystemExit("818.3 stub prefix is not escape + lrw + ld.h")
    pool_tail = (
        SGFM_USTAT.to_bytes(4, "big")
        + p818.scratch.to_bytes(4, "big")
        + MARKER.to_bytes(4, "big")
    )
    if p818.stub[-12:] != pool_tail:
        raise SystemExit("818.3 pool tail is not USTAT, scratch, marker")
    if p818.plant[0][0] != 0x401142 or p818.plant[0][1][6:8] != b"\x10\xA1":
        raise SystemExit("818.3 plant does not put 0x10A1 at descriptor+6")
    blob = p818.plant[0][1]
    covered = bytearray()
    for fr in frames_a5_u(p818.plant[0][0], blob):
        _assert_frame(fr)
        if fr[1] != 0xA5 or fr[2] != ord("U"):
            raise SystemExit("A5 U class/sub mismatch")
        count = fr[11]
        data = fr[12 : 12 + count]
        if len(data) != count or count > A5_U_MAX or fr[12 + count] != fr[-1]:
            raise SystemExit("A5 U count byte does not match the data")
        covered += data
    if bytes(covered) != blob:
        raise SystemExit("A5 U chunks do not rebuild the plant image")

    p820 = build("820.3")
    if p820.stub_base != 0x401022 or p820.stub[14:18] != bytes.fromhex("00CF0000"):
        raise SystemExit("820.3 unaligned stub did not pad after jmp r15")
    if p820.stub[:2] != b"\x10\xA1":
        raise SystemExit("820.3 stub does not start with 0x10A1")

    split = build("818.3", stub_base=0x401200, scratch=0x401220)
    if split.plant[0] != (0x401142, (0x401200 - 0x401148).to_bytes(2, "big")):
        raise SystemExit("split plant offset halfword is wrong")
    if split.plant[1][0] != 0x401200 or split.plant[1][1][:2] != b"\x10\xA1":
        raise SystemExit("split stub is not at the requested base")

    for kwargs in (
        {"scratch": 0x401700},
        {"scratch": 0x4005F0},
        {"stub_base": 0x401141},
        {"marker": 0xA5A5},
    ):
        try:
            build("818.3", **kwargs)
        except SystemExit:
            continue
        raise SystemExit(f"invalid probe was accepted: {kwargs}")


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    p.add_argument("--variant", choices=tuple(VARIANTS), default="818.3")
    p.add_argument("--descriptor", type=lambda s: int(s, 0), help="RAM descriptor address (default: variant)")
    p.add_argument("--stub-base", type=lambda s: int(s, 0), help="where the 0x10A1 stub is planted")
    p.add_argument("--scratch", type=lambda s: int(s, 0), help="status+marker word (default: just after the stub)")
    p.add_argument("--marker", type=lambda s: int(s, 0), default=MARKER, help="halfword witness (default 0x55A4)")
    p.add_argument("--ustat", type=lambda s: int(s, 0), default=SGFM_USTAT, help="halfword address to read")
    p.add_argument("--check", action="store_true", help="run the built-in encoding and frame checks")
    args = p.parse_args(argv)

    if args.check:
        self_check()
        print("ss4_ustat_probe: ok")
        return 0

    probe = build(
        args.variant,
        descriptor=args.descriptor,
        stub_base=args.stub_base,
        scratch=args.scratch,
        marker=args.marker,
        ustat=args.ustat,
    )
    sys.stdout.write(format_report(probe))
    return 0


if __name__ == "__main__":
    sys.exit(main())
