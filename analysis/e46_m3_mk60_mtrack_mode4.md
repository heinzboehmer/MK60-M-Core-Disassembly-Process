# E46 M3 non-ZCP MK60 — M-track / mode 4 on the 818.3 image

2026-11-20. Read-only analysis of the 818.3 (non-ZCP E46 M3) Continental Teves MK60 image. This is a map of what live M-track mode changes in the firmware, not a procedure for writing a BIN or a car.

Companion documents: `zcp_m_track_mode.md` (ZCP / non-M twins of the same consumers), `e46_m3_non_zcp_mk60_image.md` (how this image sits between those two), `e46_m3_dataset10_vs_zcp.md` (dataset-10 `r_ref` and the numeric mode-1 / mode-4 oversteer bars), `e46_m3_mk60_tooling_status.md` (M·CORE tooling and coverage of the Ghidra recovery).

## What M-track is on this image

M-track is **driver mode 4** (also true for the transient value 7). The predicate `_is_mdm` at `0x209FE` tests exactly those two values. The predicates read channel 0 of the mode array through `driver_mode_get` at `0x21AE8`, which returns the word at **`0x400868[ch]`**. [C]

This build has the non-M *button* state machine: a coding bit selects a two-position DSC-button toggle or a three-position cycle that can enter mode 4. It does **not** have the ZCP’s third M-button channel or the channel-sync routine. Downstream of the mode value it is ZCP-like: a traction entry writes a cached flag, and the traction tail uses that flag as a table index. The mode lives only in RAM and is initialised to 1 (DSC on) every power cycle. Nothing received on CAN writes it. [C] for the 818.3 getters, flag writer, and consumer count; the button-machine and EEPROM statements follow `zcp_m_track_mode.md` and the existing 818.3 image note.

| Item | 818.3 address | Conf. |
|---|---|---|
| Mode word, `driver_mode_get(ch)` | `0x400868[ch]` | [C] |
| Sub-value, getter `0x21AF0` | `0x400870[ch]` | [C] |
| `_is_mdm` (modes 4 or 7) | `0x209FE` | [C] |
| Cached `_is_mdm` flag, written each cycle by `0x3B080` | **`0x401015`** (`st.b` at `0x40100E+7`; no literal `0x401015` in the image). ZCP twin: `0x401045`. | [C] |
| `0x401035` bit 3 | A packed status / config bit, **not** the live mode. In `0xE844` it skips the MDM `/128` bar scale when set. | [C] distinct from the mode; [?] official meaning |

## Summary — what changes, and what does not

**M-track widens the yaw oversteer / understeer bars, widens ASC / TCS slip targets (and selects the second record of several traction calibration pairs), raises a lateral-estimator limit, and tweaks a few related counters.** It also announces itself: CAN message `0x153` byte 7 bit 1, a KWP status bit, and a diagnostic status bit. Lamps are not a direct `_is_mdm` reader; on the sibling images they follow the channel-0 sub-value.

**It does not change A, B, T3, T4, `r_ref`, or `r_est`, and it does not select yaw set B.** No direct `_is_mdm` or `0x401015` read was found in the low-address hydraulic / ABS region, in an MSR (overrun) path, or in a CBC / ADB path. Engine-torque requests to the DME are not gated by a mode-4 test in a CAN packer; they are expected to move only because the TCS slip targets move. Brake intervention changes because the yaw bars and slip targets change, not because a separate “brakes off” flag is set. No mode-4-specific fault *setter* was found in the consumers; KWP and the diagnostic block only *report* that the mode is on or 4.

At the example sweeper’s MK60-view 113.8 km/h the live first-trip mode-4 oversteer bar is **13.17 °/s** (MDM `/128` scale, then the mode-1 taper at `0xF028`). Details and the chart are in `e46_m3_dataset10_vs_zcp.md`. [C]

## How the xref was built

Every 4-aligned word in the 256 KiB 818.3 dump was scanned for pointers to the predicates and to `driver_mode_get`, `0x40100E`, and `0x401035`. Instruction refs used the M·CORE pool rule `P = ((pc+2) & ~3) + (imm8 << 2)` for `jsri` / `jmpi` / `lrw`. Direct `bsr` / `br` / `bt` / `bf` displacements were also scanned; none landed on the predicates. Flag readers are `ld.b` / `st.b (reg, 7)` after an `lrw` of `0x40100E`. Bit-3 tests of `0x401035` were register-tracked through `ld.b` and `btsti …, 3`. Function bounds come from the Ghidra recovery of this image (1585 functions, verify HEALTHY), using the public CUB3D/ghidra-mcore module and the scripts in Heinz’s public repo. Listings used a 16-bit M·CORE disassembler with the same opcode map as GNU `opcodes/mcore-dis.c`.

`FUN_00021f18` at `0x21F18` calls `driver_mode_get(1)` and is the channel-1 optional driver function (modes 8–11), not a mode-4 consumer.

## Every `_is_mdm` call (18 sites in 11 functions)

This is the same consumer count as the ZCP (11) and fewer than the non-M (27), because traction here reads the cached flag instead of calling the predicate.

### Yaw OS / US bars — `0xE844` (`sub_00E844`), five sites

| Site | Effect | Conf. |
|---|---|---|
| `0xEDB0` | If MDM, scale a temporary pair with curves `0x3D1BE` / `0x3D1C6`, then write `0x400434` / `0x400436`. | [C] |
| `0xEE06` | If MDM **and** `0x401035` bit 3 is clear, rewrite the oversteer entry `0x40042A` as `base · f(v) / 128` on curves `0x3D1CE` / `0x3D1D6` (`{50, 120, 160, 200}` km/h → `{640, 512, 256, 128}`), with a related store to `0x40042E`. The first-trip path still reaches `0xF028`, which reapplies the mode-1 speed taper (and a 3.00 °/s floor). | [C] |
| `0xEEC2` | MDM scale of the second temporary pair (`0x3D1DE` / `0x3D1E6`) into `0x400434` / `0x400436`. | [C] |
| `0xEF12` | Further MDM `/128` rewrite of `0x40042A` from `0x3D1EE` / `0x3D1F6`. | [C] |
| `0xF1DA` | If MDM and the local “scale applied” bit is set, clamp `0x40042A` / `0x40042E` to `0x3D1B2` / `0x3D1B4`. | [C] |

### Wheel-side state — `0x12E44`, site `0x12F1E`

Under several preconditions (one of them “`0x401035` bit 3 is clear”), `_is_mdm` writes **bit 4 of `0x40034B`**: set in mode 4, cleared otherwise. [C] for the write. [?] what that bit means to later wheel-side or brake logic. (The ZCP note describes a clear of a flag in the same byte; the 818.3 listing sets bit 4 when the gates pass.)

### CAN `0x153` status bit — `0x1DC54`, site `0x1DC7C`

Getter for the 1-bit field at **byte 7, bit 1** of message `0x153` (same placement as on the ZCP / non-M). After a KWP override path and a “coding bit 6 set?” test (`0x3B53A`, halfword at `0x401016+20`, mask 64 → return 0), the bit is 1 if the factory-window flag `0x4005AE` bit 1 is set, or if `_is_mdm` is true, the ECU is not in test mode (`0x1BB38`), and bit 6 of `0x400EB4` is clear. [C]

This is a status announcement only. It is not a torque request to the DME.

### Lateral estimator — `0x26CF8`, three sites

| Site | Effect | Conf. |
|---|---|---|
| `0x272B0` | Writes `0x400AA5` = `0xF9` in MDM, else `0x8E`. Same constants as the ZCP’s `0x400ACD` (RAM base shifted). | [C] |
| `0x27CA0` | If MDM and `0x400AA5 <= 71`, force `0x400AA5` back to `0x8E`. | [I] |
| `0x27CD0` | If MDM and the halfword `0x401038 < 0x1770`, raise `0x400AA5` to `0xF9`. | [I] that `0x401038` is the speed or time gate (the comparison is less-than). |

### Lateral-branch counter — `0x27D4C`, site `0x27F0A`

If MDM and a speed word is at least `0x1473` and `0x400AA5 == 0xF9` (or `0x400999` bit 5), a counter is incremented unless `0x4005AC` bit 0 is set. [I] hold-off / counter, same family as the ZCP’s `0x28274`.

### Threshold / counter — `0x28BE8`, site `0x28C04`

Uses threshold **`0x10BD` if MDM or `0x400EB6` bit 0, else `0x85F`**, against the word `0x400A72`. [C] (the same constant pair is named in `zcp_m_track_mode.md`).

### Debounce / counter — `0x28C88`, site `0x28CB0`

The body runs when `driver_mode_is_on` **or** `_is_mdm`. It touches `0x400956` bit 5, `0x40099A` bit 1, and counter `0x400AB1` (counts to 3, then clears). [I] hold-off, not a first-trip yaw bar.

### KWP SID `0x21` local id 5 — `0x31CF4`, site `0x31D4A`

Sets **bit 3 of `0x4005C5`** when the mode is on **or** 4. No separate mode-4 bit. [C]

### Diagnostic status block — `0x327B0`, site `0x3280C`

Base `0x4011CE`. Sets **bit 3 of `0x4011DA`** when the mode is on **or** 4. Also publishes `0x401035` bit 3 into `0x4011DB` bit 0. [C] status only. (ZCP published the same role at `0x40120A`.)

### Traction slip term — `0x36750`, sites `0x367C8` and `0x3680A`

Widens the allowed slip when a speed word is under 2000 and, in mode 4, adds a term scaled by the byte `0x40103D` (ZCP used `0x40106D`) if `0x400730` is also under 8000. [C] for the gates and the added term; [I] for the physical unit.

### Flag writer — `0x3B080`, site `0x3B096`

Writes `_is_mdm()` as 0 or 1 into **`0x401015`** each cycle. [C]

## Readers of the cached flag `0x401015`

No code loads the literal `0x401015`. Eight traction functions load `0x40100E+7` and use the 0/1 value as a **table index** (often stride `0x22` / 34). That is how this image, like the ZCP, selects the second record of the mode-indexed pairs in the tail block `0x3F9CC`–`0x3FCEB`.

| Function | Access | Role | Conf. |
|---|---|---|---|
| `tcs_driveslip_curve` `0x3518C` | `0x35194`, `0x351AC` | drive-slip curve, base `0x3FAF8`, stride 34 | [C] |
| `FUN_000379b0` | `0x379C2` | `0x3FC4C[flag]` | [C] index; [I] curve role |
| `FUN_00037e20` | `0x37ECC` | `0x3FC54[flag]` | [I] |
| `FUN_00037fda` | `0x37FE2`, `0x37FF4` | base `0x3FC84`, stride 22 | [I] |
| `FUN_000380e4` | `0x380F0` | `0x3FCB0` family | [I] |
| `FUN_00038126` | `0x381C8` | `0x3FCB4[flag]` | [I] |
| `FUN_00038264` | `0x3827A` | `0x3FBBC`, stride 34 | [I] |
| `FUN_000382b6` (ZCP-identical to `0x3876E`) | `0x382DA` | `0x3FC00 + 0x22×flag`, plus the `0x3FC44` / `0x3FC48` constants already noted in `e46_m3_non_zcp_mk60_image.md` | [C] |

Writer: `0x3B09E` in `0x3B080`, as above.

## `0x401035` bit 3

This byte is heavily used (65 pool literals; register-tracked bit-3 tests in **46 functions**, including early low-address and hydraulic / actuator code). It is **not** written by the mode-4 consumers above.

The only mode-4-adjacent uses pinned this pass:

- `0xE844` skips the MDM `/128` oversteer-bar raise when bit 3 is set. [C]
- `0x12E44` uses it as one of the gates before the `0x40034B` write. [C]
- `0x327B0` publishes it into the diagnostic block. [C]

The other sites were not re-derived. They are not counted as M-track effects.

## Topic checklist

| Topic | Does mode 4 change it? | Conf. |
|---|---|---|
| Yaw OS / US bars | Yes — widened (13.17 °/s OS bar at 113.8 km/h after `0xF028`) | [C] |
| ASC / TCS slip targets | Yes — flag-indexed curve pairs plus the slip term at `0x36750` | [C] |
| MSR (overrun / engine-drag) | No direct `_is_mdm` or flag read found | [?] |
| Engine torque / CAN to the DME | No mode-4 test in a packer; torque requests should move only via TCS targets. CAN `0x153` bit is status, not torque. | [I] |
| Brake intervention | Indirectly, via yaw bars and TCS slip; no separate “brakes off” flag | [I] |
| ABS | No direct `_is_mdm` / `0x401015` read in `0x0`–`0xD000` | [C] no direct read; [?] later consumers of `0x40034B` bit 4 |
| CBC / ADB | No direct read found | [?] |
| Lamps / cluster messages | CAN `0x153` byte 7 bit 1 is set. Lamp *pins* are not a direct `_is_mdm` reader; sibling images use the channel-0 sub-value. That dispatcher was not re-walked here. | [C] for the CAN bit; [I] for lamps |
| Fault handling | Status bits only (KWP `0x4005C5` bit 3, diag `0x4011DA` bit 3). No mode-4 fault setter in the 11 consumers. The ZCP-only M-button fault is absent. | [C] for the consumers; [?] for the rest of the fault manager |
| `r_ref` / A, B, T4 / set B | No | [C] |

## Open questions

- Official meaning of `0x401035` bit 3, and a trusted list of its writers.
- What `0x40034B` bit 4, `0x400AA5`, and the `0x10BD` / `0x85F` counters mean to the brake / wheel-side plants.
- Whether any MSR, CBC, ADB, or ABS routine reads those derived bytes (as opposed to `_is_mdm` itself).
- The 818.3 lamp-dispatcher addresses, and whether ids 4 and `0xC` have a reader on this image (none was found on the siblings).
- A live confirmation that CAN `0x153` byte 7 bit 1 tracks the M-button / three-position DSC cycle on a car.

## Method and provenance

The 818.3 listings are from the raw image and a Ghidra recovery of this image (1585 functions, verify HEALTHY) with the public CUB3D/ghidra-mcore module and the scripts in Heinz’s public repo (commit `6ea098d`). ZCP / non-M roles were taken from `zcp_m_track_mode.md` and re-checked against this listing; several RAM addresses are shifted relative to the ZCP (`0x401015` not `0x401045`, `0x400AA5` not `0x400ACD`, `0x4011DA` not `0x40120A`, `0x400EB4` not `0x400EE4`). No BIN was written, and nothing was written to a car.
