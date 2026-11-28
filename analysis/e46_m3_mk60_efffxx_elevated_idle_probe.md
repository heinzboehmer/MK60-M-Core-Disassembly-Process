# E46 MK60 818.3 (M3) — EFFFxx elevated idle probe (read-only)

Hand-written note (2026-11-27). Companion to `e46_m3_mk60_flash_programmability.md`,
`e46_m3_mk60_kline_access_and_flash.md`, and `mmio_map.md`. This records a **live,
read-only** elevated (10A1) halfword sweep of `0x00EFFF00`..`0x00EFFF5E` on the
**E46 MK60 818.3 (M3)** module. It is **not** a program/erase procedure; nothing was
written to the controller and no flash was programmed.

Confidence: **[C]** live bench read on this module, **[I]** interpretation of those
reads against the earlier static map, **[open]** not established.

## Method

- Tool: E46 MK60 Tool (`local/efffxx-probe`), elevated MMIO path.
- Session: mfg handshake → level 3, keep-session-alive; one knock per run.
- Stub: `mfcr r1,ss4` (`0x10A1`) then a read-only `ld` (halfword) per address.
- Range: `0x00EFFF00`..=`0x00EFFF5E`, step `0x2`, width halfword.
- Follow-ups: extent-map re-sweep `EFFF44`..`EFFF5E`; repeated `elevated mmio-read`
  of `EFFF40` (×4) and `EFFF42` (×3).

Every address in the range answered **MAPPED**. No FAULT, no hang. A FAULT after
elevate would mean wrong/protected map, not privilege; none occurred, so the
supervisor gate opened the block for read.

## Full idle sweep (session 1)

| Addr | Value | Notes |
|---|---|---|
| EFFF00 | `0x0618` | |
| EFFF02 | `0x1100` | |
| EFFF04 | `0x00C8` | |
| EFFF06 | `0xA8A6` | high byte `0xA8` = gating/arm, idle; low = EFFF07 |
| EFFF08 | `0x0008` | |
| EFFF0A | `0x0000` | |
| EFFF0C | `0x6D2C` | |
| EFFF0E | `0x0440` | |
| EFFF10 | `0x3F02` | BKSEL bank 0 |
| EFFF12 | `0x3F02` | BKSEL bank 1 |
| EFFF14 | `0x3F02` | BKSEL bank 2 |
| EFFF16 | `0x3F02` | BKSEL bank 3 |
| EFFF18 | `0x2041` | command register at idle |
| EFFF1A | `0xFB00` | status, idle/ready |
| EFFF1C | `0x0000` | |
| EFFF1E | `0x0008` | |
| EFFF20 | `0x0407` | |
| EFFF22 | `0x4281` | |
| EFFF24 | `0x0087` | |
| EFFF26 | `0x0C67` | |
| EFFF28 | `0xBF7F` | |
| EFFF2A | `0xF299` | |
| EFFF2C | `0xFF00` | |
| EFFF2E | `0x0000` | |
| EFFF30..36 | `0x0000` | |
| EFFF38 | `0xFF00` | |
| EFFF3A..3E | `0x0000` | |
| EFFF40 | `0x0631` | data port low |
| EFFF42 | `0x2C4D` | data port high |
| EFFF44 | `0x0000` | extent map |
| EFFF46 | `0x0005` | only non-zero extent entry this session |
| EFFF48..5E | `0x0000` | |

## Confirmed predictions **[C]**

- **Four-bank BKSEL fingerprint is live.** `EFFF10/12/14/16 = 0x3F02 ×4` —
  SGFM-class four-banked register signature, confirmed on the running module (not
  only inferred from the image).
- **Status idle.** `EFFF1A = 0xFB00`.
- **Gating byte idle.** `EFFF06` high byte `0xA8` (low byte `0xA6` is adjacent
  EFFF07). Gate sitting idle; nothing armed.
- **No privilege wall.** All 48 halfwords MAPPED after 10A1 elevate — supervisor
  gate opens this block read-only.

## Data-port / extent stability **[C]**

Repeated elevated reads in a later session (after a KL15 cycle between sweeps):

| Register | First sweep | Repeated reads (same live session) |
|---|---|---|
| EFFF40 | `0x0631` | `0x0641` ×4 (stable within the run) |
| EFFF42 | `0x2C4D` | `0x384D` ×3 (stable within the run) |
| EFFF46 | `0x0005` | `0x0006` on the extent re-sweep |

**Conclusion:** `EFFF40`/`EFFF42` and `EFFF46` are latched / per-session registers.
They refresh across an arm / KL15 cycle, then hold steady while you sit reading
them. They are **not** a free-running FIFO (no increment across back-to-back
reads in the same session).

Extent map `EFFF44`..`EFFF5E` is otherwise empty (`0x0000`).

## What this means for flash programming

**Reachable, not yet writable.** The on-chip flash/NVM program controller at
EFFFxx is live, mapped, and readable under our existing 10A1 / level-3 mfg
session. Flashing is not gated behind an unreachable privilege wall.

Register roles for a future write sequence (roles **[I]** from the idle map +
stability behaviour; **opcodes and arm order remain [open]**):

- **EFFF06** — arm / enable gate. Idle `0xA8`. A program/erase sequence would
  flip this before any write; nothing happens to flash while it reads `0xA8`.
- **EFFF18 / EFFF1A** — command in, status out. Poll `EFFF1A` for ready/busy/error.
- **EFFF40 / EFFF42** — data port (latched word staging), confirmed stable at rest.
- **EFFF10/12/14/16** — four-bank BKSEL select (`0x3F02` each at idle).

**Stop here for live work.** Turning this into a working flash write means sending
program/erase commands to EFFF06 / EFFF18. Wrong command sequence, bank select, or
data staging can brick the module — this is the live NVM controller, not RAM. We
have the register map and a read-only baseline; we do **not** yet have the exact
command opcodes or the required arm / unlock order. Those must come from the
disassembly (the routine that writes these registers) or a silicon doc — not from
guessing on the bench.

**Live base is EFFFxx — settled [C].** This probe proves the on-chip flash/NVM
controller the 818.3 (M3) actually exposes under supervisor is at EFFFxx (BKSEL
`0x3F02`×4, status `0xFB00`, gate `0xA8`, data port EFFF40/42). For programming,
drive EFFFxx.

Earlier notes place the MMC2114-family SGFM base at `0x00D00000` from the public
MMC2114/D Rev.1 manual. Whether this SC550080-class part *also* decodes
`0xD00000` as an alias/mirror of EFFFxx is a soft leftover and does not change
the live programming target.

## Open

- [ ] Recover exact EFFF18 command values and EFFF06 arm sequence from the image
      (paper only; no live write).
- [x] Live flash/NVM controller base on this module — **EFFFxx** (settled by this
      probe). Soft leftover only: whether `0x00D00000` is also decoded as an
      alias/mirror (does not change the programming target).
- [ ] Whether a program must also write the ECC window at `0x00100000+`.
- [ ] Meaning of the populated config words `EFFF00/02/04/08/0C/0E` and the
      `EFFF20`..`EFFF2A` run — logged, not assigned.

## Method note

No BIN was written. No program or erase command was sent. Values above are from
live elevated halfword reads on the **E46 MK60 818.3 (M3)** bench module on
2026-11-27.

---

# Write-path findings — static analysis (2026-11-27)

> **Correction (2026-11-27).** An earlier version of this section framed `0x14EC0`
> as a RAM-copied "NVM program/erase driver." That framing was wrong. Thanks to
> **Heinz Boehmer's reconciliation analysis** of the same 818.3 image, the identity
> is corrected below: `0x14EC0` is **mid-body of the reset handler** (the reset
> vector, word@`0x0`, points to `0x00014E60`), it **runs in place from flash once
> per boot**, the EFFF40 data is a **fixed in-image table value** (not caller data),
> and the EFFF18 low-field mask is **`0x0700`** (bits 8–10), not `0x07`. The
> register-write facts still hold; their *context* is the reset path, and no
> code-flash programming path was found in this image.

**Paper only.** Recovered by static disassembly of the **E46 MK60 818.3 (M3)**
flash image (`818.3.bin`, 256 KiB, md5 `7febd4b26eab79154411efc96c035b11`,
MCORE:BE:32, base `0x0`). Nothing was sent to the module; no program/erase was
performed. Addresses below are image offsets.

Confidence: **[C]** directly evidenced in the disassembly/decompile; **[I]**
interpretation; **[open]** not established.

## Identity: reset-path EFFFxx init at `0x00014EC0` **[C]**

`0x14EC0` is **not a function entry and not a RAM-copied driver**. The reset vector
(word@`0x0`) = `0x00014E60`. Disassembling from `0x14E60` gives a single contiguous
reset handler that zeroes the GPRs, sets the stack (`lrw r7,0x15214`), clears the
supervisor-save registers `SS0..SS4`, then at `0x14EBE` does `lrw r7,0x14ee0`
(= `0x00EFFF00`) and **falls straight through into `0x14EC0`**. So the EFFFxx code
below is part of the reset handler and runs **in place from flash, once per boot**.

Confirming evidence: image scan finds `0x00014EC0` **0 times** as a pointer word,
`0x00014E60` **once** (the reset vector), and there is **no memcpy of the
`0x14E60` region** anywhere. There is no RAM copy and no runtime caller — the
"no static caller" is simply because `0x14EC0` is interior to `reset_14E60`.

## Register pointer tables **[C]**

Register pointer table @ `0x00015000`:

| Pool word | Value | Register |
|---|---|---|
| `0x15000` | `0x00EFFF06` | gate (read/tested) |
| `0x15004` | `0x00EFFF18` | command (RMW target) |
| `0x15008` | `0x00EFFF1A` | status (polled) |
| `0x1500C` | `0x00EFFF40` | data port |

Second pointer/const table @ `0x00014EE0` and config block @ `0x00015010`:

| Pool word | Value | Role |
|---|---|---|
| `0x14EE0` | `0x00EFFF00` | config reg (read, `& 0xBFFF`, written back; bit10 tested) |
| `0x14EE4` | `0x0000BFFF` | mask applied to the EFFF00 word |
| `0x14EE8` | `0x00EFFF02` | config reg; reset code writes byte `0x10` |
| `0x14EEC` | `0x00EFFB00` | aux reg; reset code writes `0x02` then `0x10`/`0x00` |
| `0x14F10` | `0x00EFFA00` | aux reg; conditional clear of bit7 |
| `0x14F14` | `0x00EFFB00` | aux reg; conditional clear of bit7 |
| `0x15010` | `0x000000A4` | **armed/ready gate value** compared vs `*EFFF06` |
| `0x15014` | `0x00007000` | address/region field mask (bits 12–14) into EFFF18 |
| `0x15018` | `0x00000700` | **command-field mask (bits 8–10)** into EFFF18 (was mis-read as `0x07`) |
| `0x1501C` | `0x000088FF` | preserve mask on EFFF18 RMW |
| `0x15020` | `0x00000100` | bit8 strobe constant |
| `0x15024` | `0x00000100` | branch-select mask on the config word |
| `0x15294` | `0x0003DA7C` | ptr to config word `cfgA` (`[0x3DA7C] = 0x00002100`) |
| `0x15298` | `0x0003DA78` | ptr to config word `cfgB` (`[0x3DA78] = 0x00000101`) |
| `0x1529C` | `0x00015028` | base of the **fixed in-image data table** (24 halfwords) |
| `0x15454` | `0x00EFFF06` | gate re-read at routine exit |
| `0x15458` | `0x00015D5C` | sub called at exit (`COMPUTED_CALL` from `0x15410`) |

Both config words are **fixed in-image constants**: `cfgA = 0x00002100` (`@0x3DA7C`),
`cfgB = 0x00000101` (`@0x3DA78`). There is no caller-supplied input.

## EFFF06 (gate) — read-only; `0xA4` never written **[C]**

- The reset code **reads** `*EFFF06` and compares it to **`0xA4`** (`0x15010`) as the
  "armed/ready" condition (`0xA4 == *EFFF06`), and gates its success return on
  `*EFFF06 != 0xA4` (the signed byte `-0x5c`).
- **`EFFF06`/`EFFF07` are only ever READ — there is no store to `EFFF06` anywhere in
  the image, and `0xA4` is never written to it.** `0xA4` is a hardware-produced
  "armed/ready" state the gate reads back. Live idle reads `0xA8`.
- Arming is a **side effect of the aux-register writes in the reset path** (below),
  not a direct write to EFFF06.

### Aux-register arm writes (in the reset path) **[C]**

| Site | Register (via pool) | Value written |
|---|---|---|
| `0x14ED4` | `EFFB00` (`[0x14EEC]`) | `0x02` |
| `0x14ED6` | `EFFB00` (`[0x14EEC]`) | `0x10` if EFFF00 bit10 == 0, else `0x00` (`((w>>10 & 1)!=1)<<4`) |
| `0x14EDC` | `EFFF02` (`[0x14EE8]`) | `0x10` |
| `0x14EFE` | `EFFA00` (`[0x14F10]`) | RMW `&= 0xFF7F` (clear bit7) — only if `*EFFF06` bit2 == 0 |
| `0x14F06` | `EFFB00` (`[0x14F14]`) | RMW `&= 0xFF7F` (clear bit7) — only if `*EFFF06` bit2 == 0 |

`EFFA00`/`EFFB00` sit in the same on-chip register file just below EFFFxx and are
outside the live idle probe's `EFFF00..EFFF5E` range, so they were not read on the
bench. Their bit-level meaning (enable HV / clock / arm) is **[open]** — the write
values are evidenced, the silicon semantics are not.

## Live arm-check probe (bench, 2026-11-27) — aux-writes alone do NOT arm **[C]**

A read-only exec stub was run on the **E46 MK60 818.3 (M3)** bench module (14er)
to test the hypothesis above: do the reset-path aux-register pre-arm writes
(`EFFB00 <- 0x02`, `EFFB00 <- 0x10/0x00`, `EFFF02 <- 0x10`, plus the conditional
`EFFA00`/`EFFB00` bit7 clears) **alone** move the EFFFxx gate toward armed, from
inside our 10A1 / level-3 mfg session, **without** touching the `EFFF18` command
port or the `EFFF40` data port and **without** any erase/program command. The stub
elevated (`10A1`), snapshotted the registers, replicated only those pre-arm writes,
re-read the gate, then restored `EFFF02`/`EFFB00`/`EFFA00` from the snapshot.

Scratch result block at `0x00401400` (22 bytes, big-endian halfwords):

```
55 00 00 00 A8 A6 FB 00 00 00 11 00 00 00 A8 A6 FB 00 A8 A6 66 00
```

| Offset | Field | Value |
|---|---|---|
| +0x00 | ran sentinel | `0x55` (+ `00 00 00`) |
| +0x04 | EFFF06 BEFORE | `0xA8A6` |
| +0x06 | EFFF1A BEFORE | `0xFB00` |
| +0x08 | EFFB00 BEFORE | `0x0000` |
| +0x0A | EFFF02 BEFORE | `0x1100` |
| +0x0C | EFFA00 BEFORE | `0x0000` |
| +0x0E | **EFFF06 AFTER** | **`0xA8A6`** |
| +0x10 | EFFF1A AFTER | `0xFB00` |
| +0x12 | EFFF06 AFTER (re-read) | `0xA8A6` |
| +0x14 | returned sentinel | `0x66` (+ `00`) |

**Result — confirmed NEGATIVE.** The stub executed and returned cleanly
(`0x55` at +0x00, `0x66` at +0x14). `EFFF06` after the pre-arm writes read
**`0xA8A6`** — the idle/not-armed value — identical to the BEFORE snapshot and
**stable across the bounded re-read** (+0x0E and +0x12 both `0xA8A6`, not `0xA4xx`).
`EFFF1A` stayed `0xFB00` (status unchanged). `EFFB00`/`EFFA00` read back `0x0000`
and `EFFF02` read `0x1100`, matching the idle map.

**Conclusion [C]:** the reset-path aux-register writes (`EFFB00`/`EFFF02` + the
conditional bit7 clears) **do not by themselves arm the EFFFxx controller** from
inside the live mfg session — the gate does not move to `0xA4`. Arming therefore
requires more than the aux pre-writes: the **`EFFF18` command + strobe sequence**
(the command-field / region / bit6-initiate RMW decoded below), and/or a
power-on-only hardware condition that the reset path satisfies but a post-boot
session does not, is implicated. This is a live bench fact; the EFFF18 bit
semantics remain **[open]** (see below).

## EFFF18 (command) — bitfield RMW, no single opcode **[C]**

No literal "command word" is written. The reset code assembles EFFF18 by
read-modify-write with bit strobes, polling EFFF1A between steps, using the fixed
config words `cfgA = 0x2100` and `cfgB = 0x0101`:

1. `v = (*EFFF18 & 0x88FF) | (0x0700 & cfgA)` → write EFFF18  (preserve `0x88FF`;
   set command bits 8–10 from `cfgA`; `0x2100 & 0x0700 = 0x0100`).
2. `v &= 0xFFEC` → write EFFF18  (clear bits 0,1,4).
3. `v |= 0x40` → write EFFF18  (set **bit6**).
4. `v |= (0x7000 & cfgA)` → write EFFF18  (set **bits12–14** region; `0x2100 & 0x7000 = 0x2000`).
5. **poll** `while ((*EFFF1A >> 3 & 1) != 1)`  (wait status **bit3** = ready).
6. `v -= 0x100` → write EFFF18  (clear **bit8** strobe).
7. Stage data: `*EFFF40 = table[(cfgB & 3)*8 - 8 + (cfgA >> 12)]`. With the fixed
   config this resolves to index `2` → the halfword **`0x004D` at `0x1502C`**
   (table base `0x15028`). **A fixed in-image table value is staged — not caller
   data, and no target address is written anywhere.**
8. Per-word loop: `*EFFF18 = v & ~3` → poll bit3 → `v = (v & ~3) ^ 0x10 | (cfgB & 3)`
   (toggle **bit4** + bank bits) → poll **bit1** → `v ^= 0x10` → poll bit3 → done
   when status **bits 0 AND 1** are both set; else repeat.
9. Exit: `*EFFF18 = v - 0x100`, write `0x07` to a RAM flag (`*0x15400`), call
   `0x15D5C`, then re-read `*EFFF06`.

Controlling EFFF18 bits (positions evidenced): **bit6 (0x40)**, **bit8 (0x100)**
strobe, **bit4 (0x10)** per-word strobe, **bits0–1 (0x03)** bank, **bits8–10
(0x0700)** command field, **bits12–14 (0x7000)** region; preserve mask **0x88FF**.
The ERASE-vs-PROGRAM-vs-config meaning of each bit is **[open]** without a silicon
doc.

## EFFF1A (status) polling **[C]**

Polled bits: **bit3** = ready/handshake (gates each step), **bit1** = pulse-done,
**bits 0 & 1 together** = word complete. (Live idle `0xFB00`.)

## EFFF40 (data port) **[C]**

Loaded via `*0x1500C` with a **fixed halfword from the in-image table at `0x15028`**
(resolved value `0x004D` @ `0x1502C`). It is not a caller data word and carries no
target address. Consistent with the bench finding that EFFF40/42 is a latched
staging register (not a FIFO).

## Bank select EFFF10/12/14/16 **[open]/[I]**

The reset code does not write EFFF10–16 directly; bank/region is carried inside
EFFF18 (`0x03` / `0x7000` fields) and the EFFF40 table index. Whether EFFF10–16
(`0x3F02`×4 at idle) are set by separate code is **[open]**.

## Separate runtime EFFFxx accessor cluster **[I]**

Functions near `0x0003C1xx–0x0003C7xx` (`FUN_0003c118/c1ac/c21c/c278/c31c/c374/c3f4/
c674/c730`) read/write **EFFF00, EFFF21, EFFF2B, EFFF3D, EFFF3F** (byte RMW) and read
**EFFF06/07**, alongside QSPI `0xFFFA00` and `0xEF830C`. This runtime group **never
touches EFFF18/1A/40** (the command/status/data ports). Its register roles are
**[open]**.

## Code-flash programming path — not found in the 818.3 image **[open]**

This section does **not** assert a secondary bootloader. What is evidenced:

- The EFFF18/1A/40 command/status/data ports are driven **only** by the reset-path
  code at `0x14EC0`, which runs once per boot with fixed in-image config — a
  **boot-time controller configuration op**, not a runtime-callable programmer.
- No KWP/UDS download dispatcher (RequestDownload `0x34`, TransferData `0x36`,
  WriteMemoryByAddress `0x3D`, etc.) is evidenced in this image (constant-compare
  candidates all decompile to ABS/DSC control arithmetic).
- No constant-address self-write of the `0x0..0x3FFFF` flash region is evidenced.
- No out-of-image code-pointer set (bootloader/shadow region) is evidenced; the
  reset and exception vectors all point inside this image.

So: **no code-flash programming path was found in the 818.3 image.** The full role
of the EFFF18/1A/40 controller is **[open]** — a boot-time config operation is
evidenced; whether other code (not in this 256 KiB image) drives the controller
differently is unknown.

**Update — mfg exec handler located (2026-11-27).** The manufacturing A4/A5/A6 handler
*is* in-image (exec handler `FUN_0001be28` @ `0x0001BE28`, found by a read-only bench
`r15` capture — see `analysis/e46_m3_mk60_mfg_exec_handler.md`). It has **no native
program/erase sub-command** and no EFFFxx references in `0x1B000..0x1D000`; its only
write primitive is a single `st.b`/`st.h`/`st.w` (A4 `R` / A5 `T`) to a caller-supplied
address. Code-flash programming would require **uploaded M·CORE code run via the A6
`K`/`L`/`O` descriptor path**, not a built-in opcode. The "not found in the 818.3
image" conclusion here therefore stands: no in-image code-flash programmer exists.

## Open items

- [ ] Bit-level semantics of EFFF18 fields (`0x40`/`0x100`/`0x10`/`0x0700`/`0x7000`)
      and of the aux regs `EFFB00`/`EFFF02`/`EFFA00` — positions evidenced, meaning
      **[open]** (needs silicon doc).
- [ ] Purpose of the reset-path EFFFxx op (controller init vs ECC/shadow setup vs
      NVM housekeeping). **[open]**
- [ ] Whether EFFF10–16 BKSEL is set by separate code. **[open]**
- [ ] Roles of the `0x3C1xx–0x3C7xx` runtime EFFFxx accessor cluster. **[open]**
- [ ] Whether a code-flash programming path exists outside this 256 KiB image.
      **[open]**

### Reproduction (static, read-only)

Import `818.3.bin` (md5 `7febd4b26eab79154411efc96c035b11`) as MCORE:BE:32 base
`0x0`; disassemble from the reset vector (word@`0x0` = `0x00014E60`) and follow
through to `0x14EC0`; the pointer tables at `0x00015000` and `0x00014EE0` resolve
the EFFFxx targets, and `0x1529C → 0x15028` is the fixed data table. No live module
access required.
