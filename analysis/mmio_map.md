# MK60 (E46, SC550080) MMIO / peripheral map

2026-11-18

Memory-mapped I/O and peripheral register map for the E46 MK60 ABS/DSC ECU, from the 820.3 non-M image
(`projects/820.3_E46_non_m_mk60`) with cross-checks against the ZCP/non-ZCP images. Registers were located by
(1) clustering the code-section literal-pool pointers that fall in the peripheral band and (2) tracing the
acquisition/driver functions that read them. Items marked confirmed were read in the disassembly via
`tools/ghidra/dump.sh`; access sizes are the actual load/store widths in those functions.

## MCU

Freescale **SC550080MFU33** - custom ("SC" = customer-specific) Motorola/Freescale part, M-CORE core,
big-endian, QFP - confirmed from the board photo (`Harness/20261102_202717.jpg`). No public datasheet. The
flash controller, SRAM and all peripherals below are on-die. The external system ASIC (analog front end +
actuator drive) and a small serial EEPROM (95040, VIN/coding) are separate packages.

## Address map (overview)

Ranges are inclusive `[lo, hi]` - `hi` is the last byte in the range.

| Region | Range `[lo, hi]` | Contents |
|---|---|---|
| Flash (FW) | [0x00000000, 0x0005FFFF] | 256 KB program/cal (real data to ~0x3FC00), then erased 0xFF tail to the end of the readable window |
| Flash ECC mirror | [0x00100000, 0x0015FFFF] | per-halfword EVEN-PARITY of the flash (read-only shadow, one 16-bit cell per flash halfword). The lower part holds the parity bits; above where the flash data ends it reads 0 (parity of the 0xFF tail) |
| SRAM | [0x00400000, 0x004017FF] | ~6 KB work RAM (byte-measured; RAM-clear loop runs 0x400300..0x401800) |
| Timer twin page | [0x007F8000, 0x007F80FF] | mirror of the 0xFF8000 timer (~8 MB lower) |
| Reset/clock config | [0x00E60000, 0x00E6FFFF] | one-time reset/clock setup (registers 0xE6004D..0xE6015B) |
| Peripheral page (ports) | [0x00EF0000, 0x00EFFFFF] | GPIO/digital I/O, QSPI chip-selects, CRC, watchdog/clock-enable |
| Peripheral page (comms/timer) | [0x00FF0000, 0x00FFFFFF] | timer input-capture, QSPI/ASIC link, CAN |

Registers sit in the UPPER HALF (+0x8000) of each 64 KB page; the per-bank extents are in the next table.
Everything above SRAM except these banks reads no-reply to the mfg 0xA4 probe - see "Why MMIO can't be
block-scanned".

## Peripheral banks (ascending)

Module-level view in ascending base address, inclusive `[lo, hi]`. "Byte-readable" is the live `0xA4 'U'`
step-1 probe extent; "Referenced-only" is the register span known from the disassembly that does NOT answer
byte reads.

| Base | Bank / module | What it is | Byte-readable `[lo, hi]` | Referenced-only `[lo, hi]` |
|---|---|---|---|---|
| 0x007F8000 | Timer B (input-capture) | second capture timer, a full mirror of 0xFF8000; driver-init `{0x7F8000 -> 0x3CDB0}` (FUN_00016bce) | [0x007F8010, 0x007F803D] | - |
| 0x00E60000 | System / clock config | registers 0xE6004D/86/102/120/15A written once at CPU init (FUN_00014e70); inert at runtime | none | [0x00E6004D, 0x00E6015B] |
| 0x00EF8000 | Watchdog / clock-enable + CRC | 0xEF8200/8500 peripheral-enable & watchdog gating (FUN_00016e0c); 0xEF8400 CRC engine (FUN_00015464). **DO NOT PROBE - HARD-HANG**: a live read survives a KL15 cycle and recovers only on removing KL30. Disassembly-only and INCOMPLETELY mapped (bytes also respond below 0xEF8200, e.g. 0xEF8104/8120, before the hang) | do not probe | [0x00EF8200, 0x00EF8503] |
| 0x00EFFF00 | Digital I/O + peripheral control | input port 0xEFFF28/2A/2B (buttons, FUN_00016fca); QSPI chip-select 0xEFFF21; control/status configured at reset with a config-then-poll handshake (0xEFFF18/1A/40, FUN_00014e70) | [0x00EFFF20, 0x00EFFF3F] | [0x00EFFF00, 0x00EFFF1F] |
| 0x00FF8000 | Timer A - wheel-speed capture | capture 0xFF8014/18 (word) -> wheel RAM 0x400664; status 0xFF8032; counter 0xFF8024; cal 0xFF8010; ISR FUN_00017cb6 | [0x00FF8010, 0x00FF803D] | - |
| 0x00FFFA00 | QSPI / ASIC link | on-chip SPI master to the external system ASIC (analog front end): control 0xFFFA1C (bit15 start), status 0xFFFA1F (bit7 done), config 0xFFFA1A; data 0xFFFB00 (digitized ASIC result) / 0xFFFB20 (tx, channel); driver FUN_0003c360 | [0x00FFFA08, 0x00FFFB3F] | - |
| 0x00FFFC80 | CAN controller | TX mailbox at 0xFFFC80; RX mailbox array 0xFFFD00..0xFFFDFF = 16 x 16-byte buffers (control/ID/8 data), FlexCAN-style | [0x00FFFC86, 0x00FFFDFD] | - |

Internal holes (no-reply inside a byte-readable extent): GPIO [0x00EFFF34, 0x00EFFF37] (word-only); QSPI
[0x00FFFA10, 0x00FFFA13] and [0x00FFFA20, 0x00FFFAFF]; CAN [0x00FFFCA8, 0x00FFFCFF] except a short live span
[0x00FFFCB6, 0x00FFFCBB], with mailbox data filling [0x00FFFD00, 0x00FFFDFD].

One-line architecture: a custom Freescale SC550080 M-CORE with twin input-capture timers (wheel speed), a QSPI
link to the analog ASIC (no on-chip ADC), a GPIO/peripheral-control block, a CAN controller, and a
watchdog/clock/CRC supervisory block - all in the upper half of their 64 KB pages near the top of the 16 MB
space.

## Named registers

### Analog acquisition - there is NO on-chip ADC
No general SAR ADC with a conversion-result register exists in this image. Analog comes in two ways:

**Supply / A5 ignition voltage via the system ASIC over QSPI** (driver FUN_0003c360, 0xFF bank):

| register | addr | size | role |
|---|---|---|---|
| result (digitized word) | 0xFFFB00 | halfword | ASIC conversion result; channel chosen by the tx command |
| tx (command / channel) | 0xFFFB20 | halfword | |
| status | 0xFFFA1F | byte | bit 7 = transfer done (busy-wait poll) |
| control | 0xFFFA1C | halfword | bit 15 = start / clock-enable |
| config | 0xFFFA1A | halfword | |
| status/data aliases | 0xFFFA0A / 0C / 0E | halfword | |
| QSPI chip-select | 0xEFFF21 | byte | a GPIO line in the 0xEF bank |

The A5 sense and the rails are digitized by the ASIC and read back as 0xFFFB00; KL15/A5 status is assembled
into RAM 0x40063B by the per-tick sampler FUN_00016e0c.

**Wheel speed via on-chip timer input-capture** (ISR FUN_00017cb6, 0xFF bank; full twin at base 0x7F8000):

| register | addr | size | role |
|---|---|---|---|
| capture A / B | 0xFF8014 / 0xFF8018 | word | capture results -> wheel RAM 0x400664 |
| status / flag | 0xFF8032 | halfword | IRQ flag (read-to-clear bit 6; sibling clears bit 4) |
| compare / reload | 0xFF8020 | halfword | |
| counter | 0xFF8024 | word | |
| cal / reference (read-once) | 0xFF8010 / 0x7F8010 | word | app init -> RAM 0x40068C / 0x40068E |

### Digital input / button port (0xEF bank)

| register | addr | size | role |
|---|---|---|---|
| input port bytes | 0xEFFF28 / 0xEFFF2A / 0xEFFF2B | byte | sampled by FUN_00016fca -> RAM 0x40064a..0x40064c |

Linkage confirmed end to end: FUN_00016fca copies 0xEFFF2A -> RAM 0x40064b; the button getter FUN_000170e4
(0x1711C) reads 0x40064b and tests bit 7 / bit 6. So the DSC / M-track button line is hardware input port
**0xEFFF2A**. Output/config siblings in the same block: 0xEFFF06/18/1A/24-2B/3D/3F/40 (0xEFFF21 is the QSPI
chip-select above).

### Other 0xEF-bank blocks
- 0xEF8200 / 0xEF8500 - peripheral-enable / watchdog gating (`st.w 1` each tick in FUN_00016e0c); 0xEF8200
  also appears in the CPU-init fault path (FUN_00014e70).
- 0xEF8400 - CRC / checksum engine (write 1, feed words, read result at +4; FUN_00015464).

### CAN (0xFF bank)
- TX mailbox region ~0xFFFC80. Frame/signal detail in `zcp_can_frames.md`.

### Reset / clock (0xE60000 bank)
- 0xE60086 / 0xE6009A / 0xE60102 / 0xE60120 / 0xE6015A - one-time reset/clock config (FUN_00014e70).

## Why MMIO can't be block-scanned
The mfg `0xA4 'U'` probe reads a 16-byte block at a word boundary. Peripheral registers are size-restricted
(ports byte-only, QSPI data halfword-only, captures word-only) and several are read-to-clear / read-to-pop
(status flags, the SPI RX FIFO). A blind block read lands on a wrong-width or read-sensitive register, takes a
bus fault, and the module stops replying (no-reply) - and as a side effect can clear an interrupt flag or pop
the ASIC SPI FIFO. That is why the live 0xA4 memory map shows the whole peripheral band as no-reply while the
static pool-word scan reads it fine. Locate MMIO statically from the firmware's own pointer loads; do not
sweep it with the read primitive. The only safe live probe is a targeted single read at the register's native
width of a side-effect-free register, e.g. a byte read of the input port 0xEFFF2A.

## Empirical probe results (live byte scan, 0xA4 'U' step 1, 820.3 bench unit)

Byte-read probe of each bank over the K-line. "readable" = responds to a 1-byte read (value shows as
DATA/00/FF); "no-reply" = the access faults (width-restricted or write-only register).

| Bank | Readable (byte) | Notes |
|---|---|---|
| 0xEFFFxx ports | 0xEFFF20..0xEFFF33, 0xEFFF38..0xEFFF3F | input port 0xEFFF28/2A/2B confirmed; 0xEFFF34..0xEFFF37 no-reply (word-only/write-only) |
| 0xFF8000 timer | 0xFF8010..0xFF803D | below 0xFF8010 and from 0xFF803E no-reply |
| 0x7F8000 timer twin | 0x7F8010..0x7F803D | identical to 0xFF8000 - confirms a live mirror instance |
| 0xFFFAxx QSPI ctrl/status | 0xFFFA08..0xFFFA0F, 0xFFFA14..0xFFFA1F | holes at 0xFFFA10..13, 0xFFFA20..0xFFFAFF |
| 0xFFFBxx QSPI data | 0xFFFB00..0xFFFB3F | reading 0xFFFB00 pops the ASIC SPI FIFO |
| 0xFFFCxx CAN TX mailbox | 0xFFFC86..0xFFFCA7 | |
| 0xFFFDxx CAN mailbox array | 0xFFFD00..0xFFFDFF | **16 mailboxes x 16 bytes**; each readable at offsets 0..D, no-reply at E..F (word-only timestamp/reserved). FlexCAN-style message-buffer layout |
| 0xE60xxx reset/clock | none | all no-reply to byte reads (write-only/width-restricted); inert, no hang |
| **0xEF8xxx wdog/clock/enable** | **DO NOT PROBE** | byte-reading this block HARD-HANGS the module: survives a KL15 cycle, recovers only on removing KL30. Not a permanent brick, but do not probe live |

So byte reads (0xA4 'U' len 1) reach most of the peripheral space - ports, both timers, QSPI control/status/data,
the CAN mailboxes. The remaining no-reply registers (0xEFFF34, timer/QSPI holes, CAN E..F fields) are
width-restricted or write-only; the mfg protocol has no native-width READ (0xA4 lacks an 'R' sub-command - 'R'
is write-only on 0xA5), so reading those would require the code-exec path (upload a ld.w/ld.h stub, run via
0xA6 'O', read the result from RAM). Reads are brick-safe EXCEPT the 0xEF8xxx hard-hang above.

## See also

Elevated halfword sweeps on an 818.3 spare (2026-11-26), after `mfcr r1,ss4` (`0x10A1`),
re-checked these banks. The known islands stayed live. `0xEF8304`–`0xEF830A` hung (12 V drop;
KL30 to resume) — do not probe `0xEF8200`–`0xEF8503`. Record:
`e46_m3_mk60_elevated_mmio_findings.md`.

## Provenance / confidence
- Banks and register offsets: code-section pool-word clustering (scan of the raw bin) plus dump.sh on the
  reader functions listed above.
- Button port 0xEFFF2A; wheel capture 0xFF8014/18 + status 0xFF8032; QSPI roles 0xFFFB00 / 0xFFFA1F /
  0xFFFA1C - HIGH (read end to end in the disassembly).
- 0xFFFB00 == the A5 voltage specifically - MEDIUM (generic ASIC read word; the channel is selected by the tx
  command, not a per-signal register).
- "No on-chip ADC" - MEDIUM-HIGH (no polled result register in any code-referenced peripheral cluster; the one
  caveat is a peripheral addressed only through a base register with no literal pool word, which a pool scan
  cannot see).

Companion documents: `nonm_mk60_image.md`, `zcp_can_frames.md`, `zcp_mfg_exec_path.md`, `mfg_test_cycle.md`, `e46_m3_mk60_elevated_mmio_findings.md`.
