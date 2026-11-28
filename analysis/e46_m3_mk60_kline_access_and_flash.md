# E46 MK60 K-line access and flash

Hand-written note (2026-11-20; updated 2026-11-25). Companion to `e46_m3_non_zcp_mk60_image.md`, `nonm_mk60_image.md`, `zcp_mfg_exec_path.md`, `e46_m3_mk60_image_check_word.md` and `e46_m3_mk60_can_map.md` (no `0x7xx` diagnostic identifiers on either CAN bus; workshop access remains K-line). Addresses are from the 818.3 non-ZCP image unless a twin is named.

This is a description of what the application image contains — and what it does not. It is not a finished flash-programming procedure. The manufacturer K-line path can write and execute **RAM**; it does not, by itself, program flash. The on-chip flash controller (SGFM at `0x00D00000`) and the remaining supervisor-elevate path are documented below; no live program or erase has been demonstrated yet.

Confidence: **[C]** read from the 818.3 listing or from the cited companion docs, **[I]** inferred, **[open]** not established. Bench results below are attributed to their source (Heinz, NAM3 forum post #147, or live 818.3 framing/session probes).

## No bootloader, no flash-program code

The E46 application is a single flat program mapped at address 0. There is no separate loader region and no second image. Reset clears both register files and runs into application init (`0x14E60` on 818.3). The listing has **no flash program or erase routines** and no copy-to-RAM programming stub. [C]

That is the contrast with the later E9x MK60E5, which keeps a resident loader and a `jump_to_bootloader` path. The E9x-oriented notes in `mfg_test_cycle.md` (`jump_to_bootloader`, session `0x85`) do not apply to this image. StartDiagnosticSession `0x85` is not a path this application implements. [C]

## Timed-KL15 factory mode

The application speaks a manufacturing protocol on the K-line (SCI / K-bus), not CAN. It re-arms on every **KL15 rising edge** by a software reboot, then opens a **130 ms** knock window. Knock is `@` `0x01` `0x02` at about 4800 8N1. After selection, framed mode accepts classes `0xA0`–`0xA6`. [C]

The protocol is documented address-for-address for 818.3 in `e46_m3_non_zcp_mk60_image.md` (“The factory protocol on this unit, address for address”) and in `nonm_mk60_image.md`. The 818.3 class-`0xA6` handler is `0x1B496`.

## Reads (`0xA4`) and writes (`0xA5`)

| Class | Sub | Level | Effect |
|---|---|---|---|
| `0xA4` `'U'` | 1 | up to **38** raw bytes from **any address**, including program flash |
| `0xA4` `'X'` | 1 | EEPROM read, up to 4 bytes (NVM job) — not flash |
| `0xA5` | 2 | plain **store**. RAM writes land as ordinary stores. `'X'` writes a single EEPROM byte through the NVM job. The command does **not** program flash. |
| `0xA5` `'U'` | 2 | at most **24** bytes per request |

`0xA4` `'U'` is how a complete flash dump of this image is obtained: the command will read the dataset-10 cells and the check word just as it will read any other mapped address. [C]

`0xA5` is a CPU store, not a flash programmer. A store targeting a flash address does not erase or program that cell. The same session can write RAM and EEPROM; it cannot, by itself, change program flash. [C] for the listing.

A5 SRAM writes need access level **≥ 2**. On E46, level 2 is free: live 818.3 probing raised the level with `0xA3` and param 32, and the module replied `f`/`2`. Heinz then used `0xA5` to write a RAM target at `0x00401400` on an 818.3-class unit (NAM3 forum post #147, 20 Nov 2026). That is a RAM store only.

Working `0xA4` reads on live 818.3 used **8 ASCII hex digits** for the address (e.g. `A4 55` + `00400000`…), not raw binary address bytes. `0xA5` writes should use the same ASCII-hex address encoding. The tool adds length and checksum; paste class + sub + params only.

## RAM `jsr` gates

`mfg_rx_service` at `0x1BE28` contains a `jsr` to a RAM address at `0x1BE7E`. The ZCP twin is the function already traced in `zcp_mfg_exec_path.md` §3; on 818.3 the body is the same through the `jsr`. [C]

The `jsr` is gated. It is not taken unless:

- the access level is already **≥ 3** (`ecu_mode_ge` at `0x1BC32` reads the low three bits of the mode byte at `0x4006E9`);
- mode-byte **bit 7 is clear**;
- the control byte at **descriptor − 3** allows the incoming mode (`0x80` from the usual trigger).

Level 0–2 returns **0** and never reaches the `jsr`. [C]

`0xA6` `'O'` (inside `0x1B496`) repeats the level and bit-7 tests, then calls `mfg_rx_service(0x80)`. A zero return is treated as failure. The usual `'O'` descriptor, at level 3 with bit 7 clear, is **`0x401142`** — not the raw `0x401030` base (that base is the level-4 starting point, adjusted to `0x401036`). On a successful return, `'O'` caps the reply length at `0x4005BF` to **0x27**. [C]

The jump target is **descriptor + halfword[0] + 6**. Failed gates return 0 and do not `jsr`. Nothing in this path programs flash. The 10 ms factory cycle also calls `mfg_rx_service` every tick, but with `0x82`, which is the decode path and not the `jsr`. [C]

### Proven RAM execute (Heinz, NAM3 #147)

Those gates have been exercised on an 818.3-class unit. Heinz (NAM3 forum post #147, 20 Nov 2026) raised access to level 3 with `0xA3`, wrote RAM at `0x00401400` with `0xA5`, then used `0xA6` sub-commands **K / L / K / O** (claim descriptor, upload stub, arm, trigger the jump). Marker `0x5A` appeared at `0x00401400`; the stub ran. SRAM only; nothing hits flash.

Source: [NA M3 Forums — Flashing the MK60 with ZCP software](https://nam3forum.com/forums/forum/special-interests/coding-tuning/172815-flashing-the-mk60-with-zcp-software), post #147.

`0x401400` is a valid scratch / upload address. It is **not** the 818.3 `'O'` descriptor base at **`0x401142`**. The ZCP usual descriptor is `0x401060` (see `zcp_mfg_exec_path.md`). Do not conflate these.

How much of the level-3 window (`0x401142`–`0x401700`) or the level-4 window (to `0x401800`) is actually safe, and whether a watchdog would reset a long routine, remain **[open]**. A longer walk of the control-byte tests is in `zcp_mfg_exec_path.md`; the 818.3 tests are the same.

### Same-session tooling

Over K+DCAN with MK60 Tool, every Send re-issues the knock, which reboots the module and wipes SRAM and the access level, so `A3` → `A5` → `A4`/`A6` cannot complete in one session. **Gauge.S** does the knock dongle-side and keeps one session alive across those frames — required for the write+exec sequence.


## Flash write path (SGFM)

This remains **not a finished flash-programming procedure**. No live program or erase has been
demonstrated. What follows is the controller map, privilege rule, and software path that a RAM stub
uploaded over A5/A6 would have to drive.

### Controller base and privilege

The flash-controller register base is the M·CORE family constant **`0x00D00000`**: CMFR on the
MMC2107, SGFM (Second Generation FLASH for M·CORE) on the MMC2114 / MMC2113 / MMC2112. The
SC5500xx firmware names SGFM.

Source: [*MMC2114 Advance Information Manual* (MMC2114/D Rev. 1, April 2002)](../docs/references/MMC2114D_Rev1_Apr2002.pdf)
(also covering MMC2113 and MMC2112), system memory map and Section 10, Table 10-2. That is the
Motorola/Freescale M·CORE SGFM family reference (sometimes listed as the MMC2114 Advance
Information Reference Manual). The manual is in this repo at `docs/references/MMC2114D_Rev1_Apr2002.pdf`.

Bench probes of `0xD00000` from a user-mode RAM stub faulted. The manual states that user-mode
accesses to supervisor-only addresses have no effect and result in a cycle-termination transfer
error. Elevated bench, 2026-11-26: after `0x10A1` set supervisor, halfword reads of `0xD0001C` and
the other `0xD00000` offsets tried, and a single probe of `0x00F60000`, still faulted. Those
candidate bases are not a live flash-controller map from elevated mfg A6 exec on this 818.3. See
`e46_m3_mk60_elevated_mmio_findings.md`.

### Register map (SGFM base `0xD00000`)

| Address | Register | Notes |
|---|---|---|
| `0xD0001C` | **SGFMUSTAT** | idle / reset ≈ `0xC0` (CBEIF command-buffer-empty + CCIF command-complete) |
| `0xD00020` | **SGFMCMD** | PGM `$20`, page-erase `$40`, mass-erase `$41`, erase-verify `$05` |

### Command-buffer launch sequence

This is a command-buffer machine, not the later MPC5554 DONE/EHV model. After the usual setup in the
manual (clock divider, bank select), the three-step write sequence is:

1. Write the data word to the flash array address (the data value is ignored for erase and verify).
2. Write the command to SGFMCMD.
3. Write 1 to CBEIF to launch; poll CCIF for completion.

No intermediate writes to the SGFM module are permitted between those three steps
([*MMC2114*](../docs/references/MMC2114D_Rev1_Apr2002.pdf) §10.8.3.2).

### Privilege door and delivery

The stub must run supervisor. Path under test: `mfcr r1, SS4` (encoding **`0x10A1`**) elevates; then
a halfword-read of USTAT. Delivery remains the manufacturer K-line path already proven for RAM: A5
uploads the stub into RAM, then A6 **K / L / K / O** (or `'O'`) executes it. On 818.3, the usual
level-3 `'O'` descriptor is `0x401142`. Same-session tooling (Gauge.S) still applies.

### What is and is not shown

- (a) The datasheet and family map identify SGFM at `0x00D00000`.
- (b) Bench user-mode faults match the supervisor-only rule.
- (c) A5/A6 RAM exec is proven (Heinz, NAM3 #147).
- (d) `0x10A1` elevate is proven on the 818.3 spare (2026-11-26). Elevated halfword reads of the
  MMC2114-family candidate bases faulted. No live program or erase has been demonstrated. See
  `e46_m3_mk60_elevated_mmio_findings.md`.

## Calibration is read from flash

Dataset-10 A and B (and T0, T3, T4) are `ld.h`’d from flash on each evaluation of `r_ref` / `r_est`. There is no init copy of those tables into RAM, and no factory command that pokes a new B into a RAM alias of the column. Coding / NCS selects the dataset *index* (`k_ds`), not the cells themselves: `BAUART` (id `0x0425`, `BAUART_MK60`) is packed coding byte 1, mask `0x1F`, stored at `0x40100F` on 818.3. In C06, data 10 is `coupe_s` / `csl`, 11 is `coupe`, and 12 is `cabrio`. The example car’s traces read `BAUART_MK60 = csl`, so they run dataset 10; that coding cannot install the ZCP `B = 2400`. See `e46_m3_dataset10_vs_zcp.md`. [C]

## Memory map

The following window list was reported in the [NA M3 Forums thread on the MK60](https://nam3forum.com/forums/forum/special-interests/coding-tuning/172815-flashing-the-mk60-with-zcp-software). It is not derived from a probe of `0xEF82xx` in this work, and the CRC block in particular was not exercised here.

| Region | Range | Notes |
|---|---|---|
| Firmware | `0x00000000`–`0x0005FFFF` | **384 KB** window. Unused flash reads as `0xFF`. |
| ECC | `0x00100000`–`0x0015FFFF` | same span as firmware, at `+0x00100000` |
| RAM | `0x00400000`–`0x004017FF` | 6 KB |
| Timer B | `0x007F8010`–`0x007F803D` | |
| System / clock | `0x00E6004D`–`0x00E6015B` | |
| Watchdog / clock-enable / CRC | `0x00EF8200`–`0x00EF8503` | the image-check MISR uses `0xEF8400` / `0xEF8404` (see `e46_m3_mk60_image_check_word.md`) |
| Digital I/O | `0x00EFFF20`–`0x00EFFF3F` | |
| Timer A | `0x00FF8010`–`0x00FF803D` | |
| QSPI / ASIC | `0x00FFFA08`–`0x00FFFB3F` | |
| CAN | `0x00FFFC86`–`0x00FFFDFD` | |

The 818.3 dump file is 256 KB, with used data through `0x3FCEB` and `0xFF` padding to `0x40000`. The 817.3 ZCP dump is 266,240 bytes (`0x41000`), with used data through `0x4031F` — 800 bytes past the 256 KB *file* mark, packed as check word, coding descriptors and mode-4 calibration, and with twenty-nine ZCP `lrw` slots pointing into `0x40000`–`0x40318`. Under the 384 KB firmware window that tail is inside the advertised decode range. Whether a given 818.3 part actually returns programmed data (as opposed to `0xFF` or a bus fault) above `0x40000` has not been confirmed by a live read in these notes. [C] for the file sizes and the pointers; **[I]** that the part decodes the full 384 KB.

No software write to the ECC window appears in the 818.3 image. ECC is therefore **[I]** to be generated automatically on a successful flash program, but whether an external programmer must also write `0x00100000+` is still **[open]**.

Flash **erase granularity** is a **1 KB page** on this SGFM-class part (Freescale SGFM page erase = 1 KB; two interleaved 512-byte pages). An earlier reading of the ZCP erased tail `0x40320`–`0x40FFF` as a 4 KB erase block is superseded by the SGFM identification in `e46_m3_mk60_flash_programmability.md`. Under 1 KB pages, dataset-10 A/B at `0x3E8A4`–`0x3E8C1` fall in page **`0x3E800`** and the 818.3 check word at `0x3FCE4` falls in page **`0x3FC00`** — **different** pages, each mostly live data that would have to be buffered and reprogrammed word-by-word. [C] for the SGFM page size; **[I]** for the per-page content. See `e46_m3_mk60_flash_programmability.md`.

## Open questions

- [ ] Flash erase granularity confirmed against the part (SGFM page erase is 1 KB; see `e46_m3_mk60_flash_programmability.md`).
- [ ] Whether a flash program must also write the ECC window at `0x00100000+`, or whether ECC is automatic.
- [ ] A live read past `0x40000` on an 818.3 unit, to confirm the 384 KB firmware window on that hardware.
- [ ] How much of the level-3 / level-4 RAM windows is safe, and whether a watchdog is kicked if interrupts stay masked during a long `jsr`.
- [x] SS4 elevate (`mfcr r1, SS4` / `0x10A1`) on the 818.3 spare, 2026-11-26. Elevated halfword reads of the MMC2114-family candidate bases faulted; see `e46_m3_mk60_elevated_mmio_findings.md`. No live program or erase has been demonstrated. This image still has no resident flash programmer. A5/A6 write and execute RAM only. Next lever named there: OnCE/BDM on a spare.

## Method

The “no bootloader / no flash-program code” finding and the timed-KL15 protocol are those already recorded in `e46_m3_non_zcp_mk60_image.md` and `nonm_mk60_image.md`, re-checked against the 818.3 listing. The `jsr` gates were read from 818.3 `mfg_rx_service` and the class-`0xA6` handler, and match the ZCP twin in `zcp_mfg_exec_path.md`. The memory map is credited to the nam3forum thread cited above; the MISR registers inside that map were identified separately in `e46_m3_mk60_image_check_word.md`.

The proven RAM write + execute sequence is Heinz’s public result in that same thread, post #147 (20 Nov 2026): `0xA3` to level 3, `0xA5` to `0x00401400`, `0xA6` **K / L / K / O**, marker `0x5A` at `0x00401400`. Access-level ≥ 2 for A5, ASCII-hex address framing, and the Gauge.S same-session constraint are from live E46 818.3 probing.

The SGFM base `0x00D00000`, supervisor-only rule, SGFMUSTAT/SGFMCMD map, and command-buffer sequence are from the [*MMC2114 Advance Information Manual* (MMC2114/D Rev. 1, April 2002)](../docs/references/MMC2114D_Rev1_Apr2002.pdf), Section 10 / Table 10-2 / §10.8.3.2. User-mode probes of `0xD00000` faulted. SS4 elevate (`0x10A1`) is proven on the 818.3 spare (2026-11-26); elevated reads of the family candidate bases faulted, and no program/erase has been demonstrated. See `e46_m3_mk60_elevated_mmio_findings.md`.
