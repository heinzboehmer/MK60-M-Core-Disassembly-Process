# Elevated MMIO on the E46 M3 MK60 818.3 spare

2026-11-26. Live bench on a spare E46 MK60 818.3 (M3 ATE part 2 282 249). Read-only.
Nothing here was written to flash, and this is not a programming procedure.

Companion to `mmio_map.md` (peripheral banks, and the user-mode `0xA4` byte scan) and
`supervisor-escape-ss4.md` (the `mfcr r1,ss4` gate). This note is what elevated manufacturer
A6 exec actually read on this unit.

## Bench path

- Manufacturer diagnostic session, access level 3.
- `0xA5` plants the stub at `0x00401400`. `0xA6` **K / L / K / O** executes it. The session is
  kept alive and reused across probes.
- Tool: E46 MK60 Tool elevated MMIO sweep. Each read-only load is prefixed with
  `mfcr r1,ss4` (`0x10A1`).
- Survival marker: `0x66` at scratch+8 means the stub finished after the elevate. A FAULT
  after that elevate is a wrong or protected map, not a privilege failure. A HANG needs KL30
  removed before the module answers again.

Reads below are halfwords unless a row says otherwise.

## 1. `0x10A1` elevate, from mfg A6 user exec

Privilege-violation vector v5 (VBR+`0x14`) targets `0x14D70` on this 818.3. That handler
accepts the faulting instruction `0x10A1` (`mfcr r1,ss4`), sets EPSR bit 31 (S), skips the
`0x10A1`, and `rte`. The 820.3 disassembly of the same gate is in `supervisor-escape-ss4.md`
(handler `0x14CEC` on that sibling).

A bare `mfcr r1,psr` (`0x1001`, no leading `0x10A1`) traps in user mode and leaves no markers.

The stub `10A1;1001` (P1 = `0x0A6F0020`) returned scratch `55 00 00 00 80 3C 01 50 66`:

| scratch | bytes | reading |
|---|---|---|
| +0 | `55` | stub entered |
| +4 .. +7 | `80 3C 01 50` | PSR = `0x803C0150`, bit 31 (S) set — supervisor |
| +8 | `66` | survival marker; the stub completed after the elevate |

## 2. Family-doc flash-controller candidates, after elevate

`e46_m3_mk60_flash_programmability.md` and `e46_m3_mk60_kline_access_and_flash.md` cite the
MMC2114 family map: SGFM at `0x00D00000`, and the earlier `0x00F60000` candidate. Those cites
stay. From elevated mfg A6 exec on this 818.3 they are not a live flash-controller map.

| probe | P1 | result |
|---|---|---|
| halfword at `0xD0001C` (SGFMUSTAT candidate) | `0x0CD60026` | scratch `55`…, no `0x66`. The `ld.h` faulted in supervisor. |
| other SGFM offsets tried: `0xD00000`, `0xD00008`, `0xD00010`, `0xD0001C` | | same pattern: no `0x66` |
| halfword at `0x00F60000` | `0x0CE00026`; stub ends `00 F6 00 00` | scratch `55`…, and a session-reuse readback `55 8D 30 30`…. No `0x66`. |

The `0x00F60000` row is one address. It is not a sweep of the `F6xxxx` range.

## 3. Elevated MMIO sweeps

Read-only halfwords. Step is per window.

### `0xFF8000`..`0xFF80FF`, step 2

29 addresses mapped. Live through `0xFF803C`. FAULT from `0xFF803E` up, including
`0xFF80F8`..`0xFF80FE`. FAULT at `0xFF8002`. Several even addresses in the live window read
`0x0000`. This window was already partly readable in user mode (`mmio_map.md`); elevate
confirms the same island.

Notable halfwords:

| addr | value | note |
|---|---|---|
| `0xFF8004` | `0x1A18` | |
| `0xFF8006` | `0x0016` | |
| `0xFF8008` | `0x121E` | |
| `0xFF800A` | `0x141C` | |
| `0xFF800C` | `0x100E` | |
| `0xFF8010` | `0x0034` | |
| `0xFF8012` | `0x6D24` | |
| `0xFF8020` | `0x0063` | |
| `0xFF8022` | `0xB446` | |
| `0xFF8024` | `0x006D` | |
| `0xFF8026` | `0xFC31` | |
| `0xFF802E` | `0xF06F` | |
| `0xFF8032` | `0x00D8` | UART/timer status anchor (`mmio_map.md` timer status halfword) |
| `0xFF8038` | `0x0003` | |

### `0xEF8200`..`0xEF85FF`, step 2 — do not probe further

**HANG.** `0xEF8304`–`0xEF830A` hung and the 12 V rail dropped. Resume is KL30 off, then on.
Heinz identifies `0xEF8200`..`0xEF8503` as watchdog / clock-enable / CRC (`mmio_map.md`: do not
probe; a hang there survives KL15 and clears only when KL30 is removed). The values below are
confirmatory. They are not a reason to sweep this window again.

Sparse mapped island. The hang pocket was skipped. Merged mapped halfwords:

| addr | value |
|---|---|
| `0xEF8200` | `0x0000` |
| `0xEF8202` | `0x0004` |
| `0xEF8300` | `0x0000` |
| `0xEF8302` | `0x0000` |
| `0xEF830C` | `0x0002` |
| `0xEF830E` | `0x0000` |
| `0xEF8400` | `0x0000` |
| `0xEF8402` | `0x0000` |
| `0xEF8404` | `0xA694` |
| `0xEF8406` | `0xAD4A` |
| `0xEF8500` | `0x0000` |
| `0xEF8502` | `0x0004` |

### Known banks, re-checked elevated

These match the live banks in `mmio_map.md`. They are not a flash-controller page.

| window | result |
|---|---|
| `0xEFFF20`..`0xEFFF3F` Digital I/O | dense, live |
| `0xFFFA08`..`0xFFFB3F` QSPI/ASIC | control sparse on `0xFFFA08`..`0xFFFA1E` (`0xFFFA0C` = `0x0190` seen); data dense on `0xFFFB00`..`0xFFFB3E`. High entropy there is QSPI/ASIC RX/data. It is not a proven die-id, OTP, or flash base. |
| `0xFFFC86`..`0xFFFDFD` CAN | method check only. Not a flash lead. |

### Unlisted gaps

Elevated halfword, step `0x100`.

| window | mapped |
|---|---|
| `0xFF8100`..`0xFFF9FF` | 0 |
| `0xFF0000`..`0xFF7FFF` | 0 |

Outside the known Heinz banks, this A6 elevated hunt found no flash-controller page in those
gaps.

## 4. Manufacturer services ceiling

Framed manufacturer diagnostics on this E46 MK60 do A0–A6 and stop there.

- `0xA4` reads, including a flash dump.
- `0xA5` stores to RAM and EEPROM. It does not program flash.
- `0xA6` builds a RAM stub and jumps to it. That is the only upload-and-run door on this path.

No KWP `0x34` / `0x36` / `0x37` programming session, and no flash service past A5/A6, showed up
on this path.

The remaining practical lever for a full live map is OnCE/BDM on a spare. Naming it is the
extent of this note.

## What this bench does not show

- No live flash program or erase.
- `0x00F60000` was a single halfword probe, not a range sweep.
- The QSPI/ASIC data window is not identified as die-id, OTP, or a flash base.
- `0xEF8304`–`0xEF830A` was not read. Do not probe `0xEF8200`..`0xEF8503` on a live module.
