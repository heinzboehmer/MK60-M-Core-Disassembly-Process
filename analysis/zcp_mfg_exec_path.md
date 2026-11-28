# ZCP MK60 — mfg protocol write + code-execution path

Hand-written trace (2026-11-17; updated 2026-11-25), companion to `zcp_mk60_image.md`. Not pipeline output. Covers the
manufacturing (mfg_test_cycle) protocol's **memory-write** and **code-execution** primitives on the E46 M3
ZCP image, and what the `0xA6 'Z'` / state-7 hand-off actually does. All addresses are ZCP ROM/flash offsets
(big-endian M·CORE) unless marked RAM. Entry to the mfg session (the K-line knock → framed mode, access
level 1) is covered in `mfg_test_cycle.md` and the bench work; this doc starts from "already in framed mode".

Provenance: addresses marked **[dis]** were read directly from the ZCP Ghidra project
(`projects/817.3_e46_m3_zcp_mk60`) via `tools/ghidra/dump.sh` in this session. Addresses marked **[doc]** are
from `zcp_mk60_image.md` / `nonm_mk60_image.md` (the non-M command-class table "applies here unchanged", per
`zcp_mk60_image.md`). The **A5 RAM-write + A6 execute path is now bench-proven** on an 818.3-class unit
(Heinz, NAM3 forum post #147, 20 Nov 2026; see §4). Live E46 818.3 framing and access-level notes are also
in §4. This path writes and executes **RAM only**; it does not program flash.

---

## 1. There are arbitrary-memory WRITE commands (class 0xA5)

We dump with `0xA4` (read). Its twin **`0xA5` is write**, same memory handler **0x1B622** [doc]. From the
command-class table (non-M, applies to ZCP) [doc]:

| cmd | effect |
|---|---|
| `0xA5 'R'` | write **1, 2 or 4 bytes to any address** |
| `0xA5 'U'` | write **up to 24 bytes** (from request buffer 0x400605) **to any address** — the copy is `mfg_memcpy` |
| `0xA5 'T'` | write hex pairs from the request to a given address |
| `0xA5 'X'` | EEPROM write of one byte (NVM job 0xF0 via 0x15FC2 mode 2) |

`mfg_memcpy` = **0x1BAF0** [doc] (non-M 0x1B8D8, M3 0x7738C). The command-class table lists reads (`0xA4`) and
writes (`0xA5`) at **access level 1** [doc]. Live E46 818.3 probing required access level **≥ 2** for A5
SRAM writes (level 2 is free on that unit: `0xA3` with param 32 raised the level and replied `f`/`2`). A5
RAM writes are proven — see §4.

Note on flash: `0xA5` writes are plain stores. M·CORE flash needs the SGFM program/erase sequence
(see §4), and the app image has **no flash-writing code** (`zcp_mk60_image.md`). So `0xA5` into a flash
address (0x0..0x3FFFF) will not program flash — but `0xA5` into **RAM (0x400000+) works**, which is the basis
for everything below.

---

## 2. `0xA6 'Z'` → state 7 — re-enters the command handler, NOT a loader door

State machine [dis]: 12-state jump table at **0x1AF68**, indexed by the **RAM state byte 0x400704** (main
cycle `FUN_0x1AEBC`; `lrw r1,0x1af68; ixw r1,state; ld.w r1; jmp r1` at 0x1AF26). `0xA6 'Z'` sets state 7
(non-M: "hand off to state 7 with the stored command byte; req len 2 selects 0x7B" [doc]).

State 7's handler is **0x1B0D8** (jump-table entry 7; it is a label *inside* the state-6 body `FUN_0x1B0C4`)
[dis]:

```
0001b0d8  lrw  r7,0x1b18c      ; -> RAM 0x4005BE (session flags)
0001b0da  ld.b r7,(r7,0x0)
0001b0dc  btsti r7,0x6
0001b0de  bt   0x1b0ee         ; bit6 set -> fall into the FRAMED COMMAND HANDLER 0x1B0EE
0001b0e0  movi r7,0x9 ; st.b r7,(r12,0x0)   ; else -> state 9 (idle)
0001b0ec  jmpi 0x1b19c
```

So **`Z` just re-enters the framed command dispatcher (0x1B0EE)** — the class/long-frame processor. It is
not a jump to a bootloader or any out-of-image address. As a *direct* reflash door it is a dead end; its only
use is keeping the long-frame machinery (below) running. (Framed handler 0x1B0EE dispatches classes
0xA0..0xA6 through a flash jump table at 0x1B1CC; class handlers are fixed flash: 0xA0 0x1B50A, 0xA3
access-level machine 0x1B31C, 0xA4/0xA5 memory handler 0x1B622 [doc].)

---

## 3. The code-execution primitive — `mfg_rx_service` jsr's into a RAM buffer (the prize)

The long-frame receive service **`mfg_rx_service` 0x1BFB4** [dis] (ZCP doc: "the long-frame receive service
the class `0xA6 'O'` command runs") gets a descriptor from **`mfg_rx_descriptor` 0x1BDCA** [dis], which
returns a **RAM** address — **0x401060** or **0x40116C** (pool words 0x1C084/0x1C088; selected by a flag,
then optionally +8/align/-2):

```
0001bdd4  lrw r2,0x1c084     ; *(0x1C084) = 0x00401060   (RAM)
0001bdd8  lrw r2,0x1c088     ; *(0x1C088) = 0x0040116C   (RAM)
```

Then the tail of `mfg_rx_service` computes a call target from a halfword *inside* that RAM buffer and calls it
[dis]:

```
0001c000  ld.h r6,(r14,0x0)   ; r6 = halfword at descriptor[0]
0001c002  mov  r2,r14
0001c004  addu r14,r6         ; target = descriptor + halfword
0001c006  addi r14,0x6        ;        + 6
0001c008  addi r2,0x2
0001c00a  jsr  r14            ; *** CALL descriptor + halfword + 6 ***
```

Descriptor is RAM (~0x401060) and the offset is added positive, so **the call always lands in RAM** — the
firmware executes code out of a buffer whose address we know and can fill (via `0xA5` writes, or the
long-frame `K`/`L`/`Q` commands that build that buffer). It is reached by **`0xA6 'O'`** (runs
`mfg_rx_service` with r13 = 0x80) [doc]; `mfg_rx_service` is also called from the 10 ms cycle with 0x82
(xref 0x1AF00 [dis]).

Gating before the `jsr` (not fully pinned) [dis]: a flag byte **0x4006ED** (bit 7) and a control byte at
**descriptor-3**, checked against r13 (0x80/0x81/0x82). The 0x82 path branches to 0x1BE1E (not the jsr); the
jsr is in the bit7-clear / 0x80-0x81 path. The `jsr` itself needs access level **≥ 3** (818.3 image; see
`e46_m3_mk60_kline_access_and_flash.md`). That matches the bench sequence in §4, which raises to level 3
with `0xA3` before execute.

### The 0xA6 long-frame commands (descriptor builders) [doc]
- `M` — set the active request descriptor's header byte (0x1BBB2 non-M / 0x1BDCA-family ZCP).
- `K` — install a long-frame descriptor via the frame check.
- `L` — copy the escaped frame into the **descriptor buffer** when its header is `@`.
- `Q` — load eight parameter bytes into the request area, then as `L`.
- `O` — run the active descriptor through `mfg_rx_service` with 0x80 (caps reply at 0x27).

---

## 4. Proven write + execute path

A "load a frame into a RAM buffer, then `jsr` into it" mechanism is the factory's intended
**download-and-execute** path. That resolves the puzzle of "no resident flash-writing code in the image": the
factory does not keep flash code resident — it **uploads a transient routine into RAM over the K-line and
executes it via this path**. A hidden bootloader is not required to get code running; this is the *vector*
that lets outside code in. (The parent docs' "reprogramming must come from outside" is about the app image
having no resident loader — which is still true.)

The RAM write and the `jsr` have been demonstrated on hardware. The SGFM controller, privilege rule,
and command-buffer sequence are identified from the [*MMC2114*](../docs/references/MMC2114D_Rev1_Apr2002.pdf) manual (below). A live program or erase
has **not** been demonstrated. This path writes and executes **SRAM only** until a supervisor stub
drives SGFM.

### Bench-proven sequence (Heinz, NAM3 forum post #147, 20 Nov 2026)

On an 818.3-class unit, Heinz publicly demonstrated arbitrary RAM code execution:

1. `0xA3` — raise access level to 3.
2. `0xA5` — write a RAM target at `0x00401400`.
3. `0xA6` with sub-commands **K / L / K / O** — claim descriptor, upload stub, arm, trigger the jump.
4. Result: marker `0x5A` appeared at `0x00401400`; the stub ran. SRAM only; nothing hits flash.

Source: [NA M3 Forums — Flashing the MK60 with ZCP software](https://nam3forum.com/forums/forum/special-interests/coding-tuning/172815-flashing-the-mk60-with-zcp-software), post #147.

`0x401400` is a valid scratch / upload address. It is **not** the `'O'` descriptor base. Do not conflate
them:

- ZCP usual `'O'` descriptor: **`0x401060`** (or `0x40116C`; see §3). Jump = descriptor + halfword[0] + 6.
- 818.3 usual level-3 `'O'` descriptor: **`0x401142`**. Same jump rule. See
  `e46_m3_mk60_kline_access_and_flash.md`.

### Access levels (image + live E46 818.3)

- `0xA5` SRAM writes need access level **≥ 2**. On E46, level 2 is free: `0xA3` with param 32 raised the
  level and replied `f`/`2`.
- `0xA6` `jsr` into RAM needs access level **≥ 3** (already documented from the 818.3 image; matches Heinz
  raising to level 3 with `0xA3` before exec).

### Framing and session (live 818.3)

Working `0xA4` reads used **8 ASCII hex digits** for the address (e.g. `A4 55` + `00400000`…), not raw
binary address bytes. `0xA5` writes should use the same ASCII-hex address encoding. The tool adds length
and checksum; paste class + sub + params only.

Same-session constraint: over K+DCAN with MK60 Tool, every Send re-issues the knock, which reboots the
module and wipes SRAM and the access level, so `A3` → `A5` → `A4`/`A6` cannot complete in one session.
**Gauge.S** does the knock dongle-side and keeps one session alive across those frames — required for the
write+exec sequence.

### Milestones

1. **Prove the RAM write.** `0xA5`-write a scratch RAM word and read it back with `0xA4`. **Demonstrated by
   Heinz (#147)** on an 818.3-class unit (`0xA5` to `0x00401400`).
2. **Prove code execution.** Stage a routine that writes a marker to a scratch RAM word, trigger it
   (`0xA6` **K / L / K / O**), and read the word. **Demonstrated by Heinz (#147)**: marker `0x5A` at
   `0x00401400`. Nothing written to flash.
3. **Read-only CAN firmware dump (self-validating).** Stage a memory-read loop that walks flash, packs it into
   8-byte CAN payloads, and pushes them through the controller's transmit mailbox (registers `0xFFFC80`;
   model on the mailbox setup `0x157DC` and transmit `0x157B0`/`0x157BC` in `zcp_can_frames.md`); the Gauge.S
   CAN side captures and reassembles. This replaces the slow K-line `0xA4` read (~114 B/s, ~38 min for 256 KB)
   with a CAN transfer of the whole image in under a minute, and is checked **byte-for-byte against the
   existing K-line dump** — so it exercises the full write+exec chain read-only, with a perfect ground-truth
   comparison and nothing written to flash. The staged routine drives the CAN controller directly (init a
   mailbox, push, wait for complete), since the mfg session runs independently of the application's CAN task.
   **Still open.**
4. **SS4 elevate + SGFM program/erase stub.** `mfcr r1, SS4` (`0x10A1`) elevate is proven on the
   818.3 spare (2026-11-26). The elevated halfword read of SGFMUSTAT at `0xD0001C` faulted; see
   `e46_m3_mk60_elevated_mmio_findings.md`. No live program or erase has been demonstrated. A5/A6
   themselves do not program flash.

---


### Flash write path (SGFM)

This is still **not a finished flash-programming procedure**. No live program or erase has been
demonstrated.

The flash-controller register base is the M·CORE family constant **`0x00D00000`**: CMFR on the
MMC2107, SGFM on the MMC2114 / MMC2113 / MMC2112. The SC5500xx firmware names SGFM. Source:
[*MMC2114 Advance Information Manual* (MMC2114/D Rev. 1, April 2002)](../docs/references/MMC2114D_Rev1_Apr2002.pdf)
(also covering MMC2113 and MMC2112), system memory map and Section 10, Table 10-2.
The manual is in this repo at `docs/references/MMC2114D_Rev1_Apr2002.pdf`.

Bench probes of `0xD00000` from a user-mode RAM stub faulted. The manual states that user-mode
accesses to supervisor-only addresses have no effect and result in a cycle-termination transfer
error. Elevated bench, 2026-11-26: after `0x10A1` set supervisor, halfword reads of the
MMC2114-family candidate bases still faulted. See `e46_m3_mk60_elevated_mmio_findings.md`.

| Address | Register | Notes |
|---|---|---|
| `0xD0001C` | **SGFMUSTAT** | idle / reset ≈ `0xC0` (CBEIF command-buffer-empty + CCIF command-complete) |
| `0xD00020` | **SGFMCMD** | PGM `$20`, page-erase `$40`, mass-erase `$41`, erase-verify `$05` |

Launch sequence (command-buffer based, not MPC5554 DONE/EHV): write the data word to the flash array
address → write the command to SGFMCMD → write 1 to CBEIF → poll CCIF. No intermediate SGFM writes
are permitted between those three steps ([*MMC2114*](../docs/references/MMC2114D_Rev1_Apr2002.pdf) §10.8.3.2).

The stub must run supervisor. Path under test: `mfcr r1, SS4` (encoding **`0x10A1`**) elevates; then
a halfword-read of USTAT. Delivery is the proven manufacturer K-line path: A5 upload into RAM, then
A6 **K / L / K / O** (or `'O'`). On 818.3, the usual level-3 `'O'` descriptor is `0x401142`.

Evidence so far: (a) the datasheet and family map identify SGFM; (b) bench user-mode faults match
the supervisor-only rule in that manual; (c) A5/A6 RAM exec is proven (Heinz #147); (d) `0x10A1`
elevate is proven on the 818.3 spare (2026-11-26), and elevated reads of the family candidate
bases faulted. No live program or erase. See `e46_m3_mk60_elevated_mmio_findings.md`.

## 5. Open items / caveats / next steps

- **Gating:** the `jsr` is reached at access level 3 via `0xA6` **K / L / K / O** (Heinz #147). A longer
  walk of the exact control-byte / `0x4006ED` tests is still useful; see also
  `e46_m3_mk60_kline_access_and_flash.md`.
- **Watchdog:** whether a watchdog would reset a long `jsr` (interrupts masked) remains **open**.
- **Brick risk:** everything here writes a live module. We hold the full dump to restore, and BDM/chip-off is
  the backstop. The proven path touches SRAM only; flash is unchanged by A5/A6 themselves.
- **Flash routine:** the family cite is SGFM at `0x00D00000` (see §4). `0x10A1` elevate is proven;
  elevated reads of that candidate base faulted (2026-11-26,
  `e46_m3_mk60_elevated_mmio_findings.md`). No program or erase has been demonstrated.

## Address quick-reference (ZCP)

| what | addr | src |
|---|---|---|
| state jump table (12 states) | 0x1AF68 | [dis] |
| RAM state byte | RAM 0x400704 | [dis] |
| main cycle | 0x1AEBC | [dis] |
| state 7 handler (in state-6 body) | 0x1B0D8 (fn 0x1B0C4) | [dis] |
| session flags byte | RAM 0x4005BE | [dis] |
| framed command handler / class dispatch | 0x1B0EE (table 0x1B1CC) | [dis] |
| `mfg_rx_service` (long-frame receive) | 0x1BFB4 | [dis]/[doc] |
| `mfg_rx_descriptor` → RAM buffer | 0x1BDCA → RAM 0x401060 / 0x40116C | [dis] |
| rx_service `jsr` (code-exec) | 0x1C00A | [dis] |
| gating flag byte | RAM 0x4006ED | [dis] |
| `mfg_cmd_dispatch` / command table | 0x1C028 / 0x3E130 | [dis]/[doc] |
| `mfg_memcpy` | 0x1BAF0 | [doc] |
| memory handler (0xA4 read / 0xA5 write) | 0x1B622 | [doc] |
| 818.3 usual level-3 `'O'` descriptor (not ZCP) | RAM 0x401142 | see 818.3 k-line note |
| Heinz #147 scratch / upload target | RAM 0x401400 | NAM3 #147 |

Companion: `zcp_mk60_image.md`, `nonm_mk60_image.md`, `mfg_test_cycle.md` (E9x M3), `program_request_record.md`,
`e46_m3_mk60_kline_access_and_flash.md`.
