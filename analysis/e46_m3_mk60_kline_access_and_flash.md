# E46 MK60 K-line access and flash

Hand-written note (2026-11-20). Companion to `e46_m3_non_zcp_mk60_image.md`, `nonm_mk60_image.md`, `zcp_mfg_exec_path.md` and `e46_m3_mk60_image_check_word.md`. Addresses are from the 818.3 non-ZCP image unless a twin is named.

This is a description of what the application image contains — and what it does not. It is not a procedure for programming a module.

Confidence: **[C]** read from the 818.3 listing or from the cited companion docs, **[I]** inferred, **[open]** not established.

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

`0xA5` is a CPU store, not a flash programmer. A store targeting a flash address does not erase or program that cell. The same session can write RAM and EEPROM; it cannot, by itself, change program flash. [C] for the listing; `0xA5` on hardware is **untested** in these notes.

## RAM `jsr` gates

`mfg_rx_service` at `0x1BE28` contains a `jsr` to a RAM address at `0x1BE7E`. The ZCP twin is the function already traced in `zcp_mfg_exec_path.md` §3; on 818.3 the body is the same through the `jsr`. [C]

The `jsr` is gated. It is not taken unless:

- the access level is already **≥ 3** (`ecu_mode_ge` at `0x1BC32` reads the low three bits of the mode byte at `0x4006E9`);
- mode-byte **bit 7 is clear**;
- the control byte at **descriptor − 3** allows the incoming mode (`0x80` from the usual trigger).

Level 0–2 returns **0** and never reaches the `jsr`. [C]

`0xA6` `'O'` (inside `0x1B496`) repeats the level and bit-7 tests, then calls `mfg_rx_service(0x80)`. A zero return is treated as failure. The usual `'O'` descriptor, at level 3 with bit 7 clear, is **`0x401142`** — not the raw `0x401030` base (that base is the level-4 starting point, adjusted to `0x401036`). On a successful return, `'O'` caps the reply length at `0x4005BF` to **0x27**. [C]

Failed gates return 0 and do not `jsr`. Nothing in this path programs flash. The 10 ms factory cycle also calls `mfg_rx_service` every tick, but with `0x82`, which is the decode path and not the `jsr`. [C]

The gates and the RAM windows they can see have not been exercised on hardware in this work. How much of the level-3 window (`0x401142`–`0x401700`) or the level-4 window (to `0x401800`) is actually safe, and whether a watchdog would reset a long routine, remain **[open]**. A longer walk of the control-byte tests is in `zcp_mfg_exec_path.md`; the 818.3 tests are the same.

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

Flash **sector size** is still **[open]**. The ZCP dump’s erased tail `0x40320`–`0x40FFF` after a used region that ends at `0x4031F` is consistent with a **4 KB** erase block starting at `0x40000` **[I]**. Under a 4 KB map, dataset-10 A/B at `0x3E8A4`–`0x3E8C1` and the 818.3 check word at `0x3FCE4` would fall in **different** sectors. That is a reading of the dumps, not a measured erase.

## Open questions

- [ ] Flash sector (erase block) size, confirmed against the part rather than inferred from the ZCP padding.
- [ ] Whether a flash program must also write the ECC window at `0x00100000+`, or whether ECC is automatic.
- [ ] A live read past `0x40000` on an 818.3 unit, to confirm the 384 KB firmware window on that hardware.
- [ ] How much of the level-3 / level-4 RAM windows is safe, and whether a watchdog is kicked if interrupts stay masked during a long `jsr`.
- [ ] The factory programming path for this module, if any exists outside the application image. This image does not contain one.

## Method

The “no bootloader / no flash-program code” finding and the timed-KL15 protocol are those already recorded in `e46_m3_non_zcp_mk60_image.md` and `nonm_mk60_image.md`, re-checked against the 818.3 listing. The `jsr` gates were read from 818.3 `mfg_rx_service` and the class-`0xA6` handler, and match the ZCP twin in `zcp_mfg_exec_path.md`. The memory map is credited to the nam3forum thread cited above; the MISR registers inside that map were identified separately in `e46_m3_mk60_image_check_word.md`.
