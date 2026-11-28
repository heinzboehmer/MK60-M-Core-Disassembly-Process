# E46 MK60 flash programmability and the SGFM flash module

Hand-written note (2026-11-21; updated 2026-11-25). Companion to `e46_m3_mk60_kline_access_and_flash.md`,
`e46_m3_mk60_image_check_word.md`, `e46_m3_non_zcp_mk60_image.md`, `nonm_mk60_image.md` and
`zcp_mk60_image.md`. Addresses are from the 818.3 non-ZCP image unless a twin is named. This is a
description of what the three E46 application images contain and do not contain; it is not a
procedure for programming a module, and nothing here was written to a car.

Confidence: **[C]** read from the image/listing or a cited public datasheet, **[I]** inferred,
**[open]** not established.

## Summary
The E46 MK60 MCU is a Motorola M·CORE part whose on-chip flash is **SGFM-class** (Second Generation
Flash for M·CORE, the MMC2114 family), not the older CMFR (MMC2107). The three application dumps
contain **no flash program/erase driver, no bootloader, and no copy-to-RAM programming stub** — the
same "flat program, no loader" finding recorded in the image notes, now tied to the specific flash
technology. A resident routine therefore cannot program the program-flash region; that must go
through the SGFM controller. The application images still do not drive those registers, but the
family base is the M·CORE constant **`0x00D00000`** ([*MMC2114 Advance Information Manual*, MMC2114/D
Rev. 1, April 2002, Table 10-2](../docs/references/MMC2114D_Rev1_Apr2002.pdf)). Every SGFM register is supervisor-only; user-mode accesses have no
effect and result in a cycle-termination transfer error, which matches bench probes of `0xD00000`
from a user-mode stub. No live program or erase has been demonstrated. The resident KWP/K-line stack offers no download service and
no programming session, and the SecurityAccess (0x27) scheme — though symmetric — reaches only plain
memory stores, never flash program/erase. The "813.3/817.3 flashable, 818.3 not" split reported by
the community is therefore **not encoded in the application image**; it is external (a bootloader
reached by an authenticated programming mode, and/or a hardware/ID gate).

## 1. SGFM-class flash, and the security/config field at 0x200
Right after the M·CORE vector table (0x0–0x1FF), offset **0x200** holds the bytes `4C 4F 43 4B`
("LOCK"), then `FF`. That is exactly the SGFM flash configuration/security field location
(0x200–0x022B per Freescale AN2286): a 4-byte security word, an 8-byte backdoor key, and per-sector
program/erase and access-mode protection fields, all copied into SGFM registers at reset. The
security word reads as **secured** (low bits set), with the backdoor key **unset** (`FF`). All three
E46 images carry the same "LOCK" word. [C]

The 256 KB image size and this config-field layout place the part in the **MMC2114/SGFM family**
(256 KB, SGFM), not the 128 KB MMC2107/CMFR. [C]/[I]

## 2. SGFM program/erase model (from public silicon docs)
From Freescale AN2286, AN1756 and the SGFM/ColdFire-CFM register model:
- Registers: a configuration register (CBEIE/CCIE), a **clock-divider (CLKD)** register that must be
 written once before any command (or no command executes), a **status** register with **CBEIF**
 (command-buffer-empty, bit 7) and **CCIF** (command-complete, bit 6) plus ACCERR/PVIOL/BLANK, a
 **command** register, and protection/security registers.
- Commands ([*MMC2114*](../docs/references/MMC2114D_Rev1_Apr2002.pdf) Table 10-5): **`$05` erase-verify** (RDARY1 / all 1s), **`$20` word program**
 (PGM), **`$40` page erase** (PGERS), **`$41` mass erase** (MASERS).
- Sequence: write CLKD once; clear ACCERR/PVIOL; write the target word to the flash address; write
 the command; write 1 to CBEIF to launch; poll CCIF until set.
- Granularity: **program = one 32-bit word; page erase = 1 KB** (two interleaved 512-byte pages;
 low address bits ignored). [C]

This **corrects** the earlier inference (in `e46_m3_mk60_kline_access_and_flash.md`) that the erase
block might be 4 KB from the ZCP padding: SGFM page erase is **1 KB**. Under a 1 KB page, the
dataset-10 A/B cells (`0x3E8A4`–`0x3E8C1`) and the 818.3 check word (`0x3FCE4`) fall in **different**
1 KB pages (`0x3E800` and `0x3FC00`), each of which is mostly live data and would have to be buffered
and reprogrammed word-by-word. [I]

## 3. No flash-controller driver in any image; family base is `0x00D00000`
A scan of every `lrw` pool constant in the on-chip peripheral window `0x00C00000`–`0x00FFFFFF`,
across 817.3, 818.3 and 820.3, finds the live modules (clock `0xC10000`, PIT `0xC80000`, SCI
`0xCC/0xCD0000`, a CAN controller at `0xCF0000`, a second CAN at `0xFA0000`, a watchdog/clock/CRC
block at `0xEF82xx`, the SIM at `0xFF0000`) but **no block driven as a flash command/status/clock
controller**. The stock M·CORE flash-control slot `0x00D00000` appears only as data, never as a
code-driven MMIO base. [C]

That does not mean the address is unknown. The [*MMC2114 Advance Information Manual* (MMC2114/D
Rev. 1, April 2002)](../docs/references/MMC2114D_Rev1_Apr2002.pdf) maps FLASH registers (SGFM) at **`0x00D00000`** (CMFR occupied the same slot on
the MMC2107). Table 10-2 marks every SGFM register **supervisor access only**: user-mode accesses
have no effect and result in a cycle-termination transfer error. Bench probes of `0xD00000` from a
user-mode RAM stub faulted in exactly that way. The
earlier “base not pinned; also try `0x00F60000`” wording is superseded by the manual map in this
section. Elevated bench, 2026-11-26: after `0x10A1` set supervisor, halfword reads of `0xD0001C`
and the other `0xD00000` offsets tried, and a single probe of `0x00F60000`, still faulted (no
survival `0x66`). Those MMC2114-family candidate bases are not a live flash-controller map from
elevated mfg A6 exec on this 818.3. The manual cite above stays. See
`e46_m3_mk60_elevated_mmio_findings.md`.

Register offsets in that manual, for a program/erase stub that could drive this map: **SGFMUSTAT**
`0xD0001C` (idle ≈ `0xC0`), **SGFMCMD** `0xD00020`. See
`e46_m3_mk60_kline_access_and_flash.md` (“Flash write path (SGFM)”) and `zcp_mfg_exec_path.md` §4.
[C] for the manual map and privilege rule; [C] that user-mode probes fault; **[open]** that a
supervisor stub can complete a live program or erase.

## 4. KWP service table: no download, no programming session
Both the 817.3 and 818.3 images carry the **same 17-service KWP table** (817.3 at `0x3F358`, 818.3
at `0x3ED24`), identical in SIDs, lengths and gating levels — only handler addresses shift:
`0x14, 0x10, 0x20, 0x18, 0x17, 0x1A, 0x21, 0x27, 0x30, 0x31, 0x3B, 0x22, 0x2E, 0x3E, 0x09, 0x23,
0x3D`. There is **no RequestDownload (0x34), TransferData (0x36) or RequestTransferExit (0x37)**, and
StartDiagnosticSession rejects the programming sub-function `0x85` (observed as `7F 10 12`,
sub-function-not-supported). [C] No `0x7xx` diagnostic identifiers exist on either CAN bus, so
workshop access stays on K-line (see companion doc). [C]

## 5. SecurityAccess (0x27): symmetric, and still not a flash door
The 0x27 handler (818.3 `0x31A82`, 817.3 `0x31F4A`, byte-identical) implements **three
request/send levels**. On a correct key it records a granted level that gates other services:
- a base level (basic diagnostics),
- a mid level that enables ReadMemoryByAddress (`0x23`),
- a top level that enables WriteMemoryByAddress (`0x3D`).

The seed is drawn from a live counter and the key is a **symmetric, in-firmware transform of the
seed** (no asymmetric/public-key material appears in the 0x27 path). The scheme is therefore
reversible in principle from the image. The exact per-image constants and transform steps are left
out of this public note on purpose.

Crucially, **unlocking 0x27 does not advance flash programming.** Even at the top level,
WriteMemoryByAddress (`0x3D`) is a plain CPU store — it writes RAM and single EEPROM bytes but does
not run the SGFM program/erase sequence — and RoutineControl (`0x31`) exposes only sensor/valve
calibration routines (IDs `0x20`–`0x26`), none of which erase or program flash. [C]

## 6. The flashable/locked split is external
The community record (NA M3 Forums MK60 thread; ATE/Teves "Motorsport Flash" vendor) is that
**813.3 and 817.3/ZCP units are reprogrammable while 818.3 is not**, and that the flash is done over
CAN/K-line without opening the unit under an **asymmetric** authentication. None of that gate is in
the application image: 817.3 (flashable) and 818.3 (locked) have byte-identical diagnostic stacks,
identical peripheral maps, and neither contains flash code. The difference therefore lives **outside
the application dump** — a bootloader reached only by that authenticated programming mode, and/or a
hardware/ID gate. [C] for the image identity; [I] for where the gate lives.

## 7. What routes remain (research only)
With no resident programming path, the only routes that could in principle write this flash are:
1. Upload a transient routine into SRAM via the factory K-line RAM-exec path (A5 then A6 **K / L / K / O**,
 proven for RAM by Heinz, NAM3 #147) and have **it** elevate with `mfcr r1, SS4` (`0x10A1`). Elevate
 is proven on the 818.3 spare. Elevated reads of the MMC2114-family candidate base `0x00D00000`
 faulted (2026-11-26, `e46_m3_mk60_elevated_mmio_findings.md`). A live program or erase is **not**
 demonstrated. [open]
2. A bench **BDM/OnCE** route: the M·CORE OnCE port's lockout-recovery mass-erases the whole array
 (clearing the security word), after which the part is unsecured — destructive, requires a full
 known-good image to restore, and the OnCE lines are not on the vehicle connector (open-unit
 only). [C] that the mechanism exists; [I] that it yields a usable reflash.

SS4 elevate is proven on the 818.3 spare; the elevated SGFMUSTAT read faulted (2026-11-26, see
`e46_m3_mk60_elevated_mmio_findings.md`). A program/erase stub is not shown. A flashable-unit bootloader image or a captured
authenticated flash session would still be independent evidence, since the application images do
not contain a resident programming path.

## 8. JTAG/OnCE connector pinout (MMC2114D reference)
On this M·CORE part the BDM-equivalent debug interface is the **JTAG/OnCE port**. The MMC2114D
Rev.1 (April 2002) manual, Section 22.14.18 / Figure 22-17 "Recommended Connector Interface to
JTAG/OnCE Port" (p. 609), specifies a 14-pin 0.1-inch (center-to-center) header:

| Pin | Signal |
|-----|--------|
| 1 | TDI |
| 2 | GND |
| 3 | TDO |
| 4 | GND |
| 5 | TCLK |
| 6 | GND |
| 7 | KEY (no connect) |
| 8 | GPIO/SI |
| 9 | TARGET_RESET (wired-OR with system reset; must be able to assert/monitor system RESET) |
| 10 | TMS |
| 11 | TARGET VDD |
| 12 | DE (debug enable) |
| 13 | GPIO/SO |
| 14 | TRST |

Each signal has a 10 kΩ pull referenced to TARGET VDD. GPIO/SI and GPIO/SO are **not** required
for OnCE operation; they exist only for the optional high-speed download interface. [C]

This is the generic MMC2114 recommended connector from the datasheet — what the silicon exposes —
not a verified mapping of pads or a harness on any MK60 board.

Live E46 MK60 818.3 (M3) bench work since this section was first drafted strengthens why this
pinout matters: elevated/diag-session probes of the MMC2114-family SGFM/clock windows fault or
no-reply, and the on-chip EFFFxx NVM controller is structurally a data-NVM path (not the
18-bit code-flash programmer). With no resident code-flash path reachable from mfg/diag context,
OnCE/BDM on a spare remains the named hardware lever. Board-level pad identification against this
14-pin reference is still **[open]** — nothing in the K-line work touched TCK/TMS/TDI/TDO/TRST.

## Open questions
- [x] SS4 elevate (`0x10A1`) on the 818.3 spare, 2026-11-26. The following elevated halfword
 reads of the MMC2114-family candidate bases faulted. See
 `e46_m3_mk60_elevated_mmio_findings.md`.
- [ ] A live program or erase. Not shown. Next lever named there: OnCE/BDM on a spare.
- [ ] Whether the SGFM protection registers (seeded from the 0x200 field) would allow a RAM routine
 to program the cal pages at all.
- [ ] Whether a program must also write the ECC window at `0x00100000+`, or ECC is automatic.
- [ ] The external programming path (bootloader / authenticated mode); not present in any image here.

## Method
The `lrw` peripheral scan, the KWP service-table parse, and the 0x27 / 0x31 handler reads were done
on the raw 817.3, 818.3 and 820.3 dumps and a headless Ghidra recovery of these public images,
cross-referenced with the [*MMC2114 Advance Information Manual* (MMC2114/D Rev. 1, April 2002)](../docs/references/MMC2114D_Rev1_Apr2002.pdf),
Freescale AN2286, and the SGFM register model. The `0x00D00000` base, supervisor-only rule, and
command-buffer sequence are from that manual (Table 10-2, §10.8.3.2), in this repo at `docs/references/MMC2114D_Rev1_Apr2002.pdf`; user-mode probe faults are
the matching bench pattern. The "no bootloader
/ no flash code" result is the one already recorded in the image notes, re-confirmed here and tied
to the SGFM technology. No BIN was written and nothing was written to a car.
