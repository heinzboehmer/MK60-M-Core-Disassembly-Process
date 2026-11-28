# E46 MK60 818.3 (M3) — mfg `A4/A5 'X'` EEPROM read/write primitive

Image-analysis note (2026-11-26). Companion to `e46_m3_mk60_kline_access_and_flash.md` (NVM job
layer and the factory `0xA5 'X'` single-byte EEPROM write), `mfg_test_cycle.md` (the manufacturing
protocol state machine and its access levels), `zcp_mfg_exec_path.md` (the `A3/A5/A6` descriptor/exec
path) and `e46_mk60_eeprom_layout.md` (the 95040 contents, including the write-log ring at 0x044).

This describes **how the manufacturing protocol encodes a single-byte EEPROM read and write** on the
**E46 MK60 818.3 (M3)** image, from disassembly. It is a description of a protocol and a driver; it is
not a procedure for writing a module or a car. Confidence: **[C]** confirmed in the listing, **[I]**
inferred, **[?]** open.

## 1. mfg frame checksum is ADDITIVE

mfg frames on this image are framed as `len | payload | sum`, where the trailing byte is an **additive**
sum: `sum = (len + Σ payload) & 0xFF` — **not** a sum-to-zero / two's-complement check. [C]

Grounding is visible even without the frame-check routine in hand, directly in known-good traffic:
- a controller reply `03 E4 66 33 80` satisfies `(03+E4+66+33) & 0xFF = 0x80` (the trailing byte);
- a level-raise request `06 A3 33 00 00 31 36 43` ends in `(06+A3+33+00+00+31+36) & 0xFF = 0x43`.

A sum-to-zero reading of either frame gives a different final byte, and frames built that way draw no
reply. Any external encoder for this protocol must use the additive form.

## 2. `A4 'X'` and `A5 'X'` are standalone received bus frames

The 'X' EEPROM commands are dispatched as ordinary received mfg frames, not only as `A6`-staged
sub-commands:

- **Class jump table `0x1B044`** dispatches on the received class byte.
- **Hex parser `0x1B940`** populates the decode buffer at **`0x4005E9`** directly from the received
  frame, as: `[0]=class`, `[1]=sub`, `[2..9]=id` (8 ASCII-hex), `[10..17]=value` (8 ASCII-hex),
  `[18,19]=data` (2 ASCII-hex). The `id` field is the first 8-hex word. [C]
- **`A4 'X'` read handler `0x1B33C`**: sub `'X'` (0x58); **no access-level gate**; request length
  clamped to 4 (0x1B344–0x1B34A); `id = zexth(r13)`; calls the NVM request entry `0x16042` with
  **mode 10** (read), destination `0x4005C2`; reply byte 1 is a done flag. [C]
- **`A5 'X'` write handler `0x1B418`**: sub `'X'`; **access level must be exactly 2**
  (`0x1B418 cmpnei r1,0x2`); builds ONE data byte from the two trailing ASCII-hex chars at
  buffer `+18/+19` (0x1B41C–0x1B440); `id = zexth(r13)`; calls `0x16042` **mode 2, len 1**
  (0x1B44A–0x1B44E), then waits on job **0xF0** via `0x161E4` (0x1B454–0x1B458). [C]
- **Driver chain**: `0x16042` → job worker `0x161B4` → `eeprom_write 0x16334` (QSPI ch2, writes the
  bit-inverted data) / `eeprom_read 0x163A6` (returns the bit-inverted rx). [C]

Because the parser fills `0x4005E9` from the received frame and both handlers read their fields from
that same buffer, `A4 'X'`/`A5 'X'` are received-frame primitives in their own right. The access-level
split is notable: the read is ungated, the write requires level 2 exactly.

## 3. EEPROM is 512 bytes, 9-bit addressing

`eeprom_read 0x163A6` and `eeprom_write 0x16334` test only **address bit 8** (`btsti r2,0x8`) and fold
A8 into the serial opcode — read `0x03`/`0x0B`, write `0x02`/`0x0A` — then emit a **single** address
byte. That is the 95-series 512-byte density (95040): one A8 opcode bit plus eight address bits = 9-bit
space `0x000..0x1FF`. There is no byte above `0x1FF`; an `id` with bits above bit 8 set wraps mod 512. [C]

## 4. The `0x050` ignition ring-buffer (power-cycle bookkeeping)

Companion to the write-log ring at `0x044–0x04D` documented in `e46_mk60_eeprom_layout.md`, a second
rotating region sits just above it:
- **`0x050–0x05E`**: an ignition/session ring-buffer whose index at `0x050–0x054` is a **mod-5 rotating
  pointer** that advances one step per power cycle. [C, from repeated power-cycle dumps of a bench unit]
- **`0x074`**: alternates `0C`↔`04` on each power cycle (a 1-bit cycle toggle). [C]

These two regions change on **every** ignition cycle, independently of any EEPROM write. When diffing
dumps to validate a single-byte write, `0x050–0x05E` and `0x074` are expected to move from power cycling
alone and must not be mistaken for write collateral; the write itself touches only its target byte.
