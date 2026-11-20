# e46_m3_non_zcp_mk60_image Analysis

2026-11-17

`images/e46_m3_non_zcp_mk60_full_dump.bin` (the dump handed in as `projects/e46_m3_non_zcp_mk60_full_dump.bin`) is the E46 M3 non-ZCP build of the same Continental Teves MK60 firmware as the ZCP image in `zcp_mk60_image.md` and the non-M image in `nonm_mk60_image.md`: the same chip, the same flat program with no loader, the same factory K-line protocol instruction for instruction, the same memory map, and 92% of its instruction mass inside functions that are identical to a ZCP function. It is a ZCP-generation build, not a non-M-generation one: 1527 of its 1585 functions are the ZCP's, including every control function the ZCP restructured, and the 58 that are not are either the non-M's versions (31, among them the whole driver-mode state machine and the power-state routine) or edited a third time (27, most of them in one control subtree and the fault manager). What the ZCP adds on top of this build is exactly the M track mode: the third button channel, the 37-row transition table, the channel-sync routine and one fault code are absent here, while the mode-indexed calibration pairs in the tail block are present and read by the same code. Mode 4 exists in this image as it does in the non-M, as the coded three-position cycle on the DSC button. The dump is complete: the data ends at 0x3FCEC with the chip erased to 0x40000, and no pool word points past the end. Against the E9x M3 it stands where its siblings stand: library leaves, actuator drivers and the factory protocol match, the control and diagnostic stacks do not, and CCP is absent. Project: `projects/e46_m3_non_zcp_mk60` (project `E46_M3_nonZCP_mk60`, program `e46_m3_non_zcp_mk60_full_dump.cpu.hex`), recovered with the pipeline at commit 6ea098d.

## What the image is

A complete 256 KB flash read mapped at address 0, holding one flat program. There is no separate loader region and no second image.

| Property | Non-ZCP dump (this image) | ZCP dump | Non-M dump | E9x M3 7846816A |
|---|---|---|---|---|
| Size | 262,144 bytes: data to 0x3FCEB, 788 bytes of 0xFF from 0x3FCEC | 266,240 bytes (0x41000): data to 0x4031F, erased from 0x40320 | 262,144 bytes, 1,332 bytes of 0xFF at 0x3FACC | about 2 MB `.0pa`, application 748 KB at 0x48000 |
| Completeness | every pool word that lands past 0x40000 is a bit-mask constant the ZCP carries too (0x60001, 0xA0008 and the like); nothing lands in 0x40000..0x5FFFF; the last 0x320 bytes before the erased run are the same calibration block the ZCP keeps at 0x40000 | a first 256 KB read was truncated | complete | complete |
| Vector table | at 0x0; reset 0x14E60, exception stubs 0x14D4C..0x14E40 (default 0x14DF0 in 79 slots), ISRs to 0x17D93 | at 0x0; reset 0x14F90, stubs 0x14E70..0x14F64, ISRs to 0x17EE7 | at 0x0; reset 0x14DE0, stubs 0x14CC8..0x14DBC, ISRs to 0x17D13 | application vectors at 0x48000, loader below |
| Code / data boundary | code to 0x3C910, call-target islands to 0x3CCF6, data from 0x3CCF6 | code to 0x3CDD0, data from 0x3D1B6 | code to 0x3CB60, data from 0x3CF46 | code to about 0xD7000, calibration above |
| Resident loader | none: reset clears both register files and runs straight into application init (the one 449-instruction function 0x14E60, identical to the ZCP's 0x14F90), no flash-writing code, no copy to RAM | none | none | yes, 0x0..0x47FFF |
| CPU | M·CORE, big-endian; RAM at 0x40xxxx (highest address used 0x401800); bit-0 tag on code pointers | same | same | same |
| Peripheral blocks touched | 0xEFF000, 0xFF8000, 0xFFF000, 0xEF8000, 0xE60000; the ten discovered MMIO pages are the siblings' ten | same | same | 0xD80000, 0xDB0000, 0xE10000, 0xFA0000, 0xFC0000 |
| Identification | no `AZ1...` block, no seven-digit number string | same | same | `AZ1RAE00008` block at 0xF68FC |
| Calibration names | `AUTA24F1.DFT` from 0x3CD1F, then `AUTY`, `NOPARADJ.UST` at 0x3D91C, `AUTB24F1`, `AUPY2681.ZC5` at 0x3DB09, `AUTC`, `AUTS`, `AUTW24F1`, `AUTT24F1.DFT` at 0x3F9C0 | `AWTA26F1.DFT` from 0x3D1DF, `AWPY3381.ZC4`, `NOPARADJ.UST` eighth, `AWTT33F1.DFT` at 0x3FFF4 | `APTA27F1.DFT` from 0x3CF6F, `APPY2782.KC8`, `APTW27F1.DFT`, `APTT27F1.DFT` at 0x3F854 | Teves dataset names in the calibration section |
| Functions after recovery | 1585 (409 prologue-seeded), verify HEALTHY in 3 rounds | 1670 (410) | 1577 (416) | 3608 |
| Instructions | 106,745 | 107,265 | 107,135 | about 260,000 |

The recovery converged in three rounds (first-pass work 526, 20, 0). The verifier reports 0 on every gate (error bookmarks, mis-disassembled tables, code bytes in the data section, orphan runs, undefined prologues, call targets not code, pool slots in code), 206 of 213,694 code bytes owned by no function, 0 bad of 252 table pointers and 637 code-referenced table bases. The timer interrupt is split as in the ZCP: the phase selector 0x17BDC (jump table 0x17C0C) and the phase-0 body 0x17C2C, both identical to the ZCP's 0x17D30 and 0x17D80, with 0x15476 the only caller of the 10 ms body 0x17FC2.

## How it relates to the two E46 siblings

This build sits between the non-M and the ZCP, much nearer the ZCP: against the ZCP it has 96% identical functions holding 92% of the instruction mass; against the non-M 87% and 61%, which is the ZCP's own figure against the non-M (85% and 59%). Every function the ZCP restructured out of the non-M (the giants below the vector handlers, the 0xD2A4 observer branch, the lateral estimator, the wheel control, the traction and yaw cluster, the actuator drivers below `valve_flow`) is here in its ZCP form, instruction for instruction and in most cases at the same address.

| Measure | Against the ZCP | Against the non-M |
|---|---|---|
| Functions with an identical relocation-masked signature in the other image | 1527 of 1585 (96%) | 1378 of 1585 (87%) |
| Instruction mass inside identical functions | 97,880 of 106,745 (92%) | 65,512 of 106,745 (61%) |
| Large functions (20+ instructions) with an identical counterpart | 782 of 832 | 666 of 832 |
| Large functions with no identical counterpart | 50: 33 at 60% or better 6-gram containment in their partner, 7 at 30% to 60%, 10 under 30% | 166 |
| Functions of the other image with no identical counterpart here | 142 of 1670 (54 large) | 206 of 1577 (168 large) |
| 16-byte shingles of the raw file found in the other image | 100,545 of 128,750 (78%) | 68,847 (53%) |
| Raw bytes that differ over the 256 KB | 178,248 | 247,053 |
| Identical by signature but at a different address | code below 0x12000 is in place (delta 0); the reset region sits 0x130 lower, the protocol and init 0x18C lower, 0x20000..0x2BFFF 0x528 lower, the 0x2C000 subtree 0x4F8..0x500 lower, the KWP handlers 0x4C8 lower, 0x34000 up 0x4B8..0x4BC lower; the data section starts 0x4C0 earlier | everything moved, as for the ZCP |

Three-way: 1347 functions are identical to both siblings, 180 to the ZCP only (35,381 instructions: the restructured control software), 31 to the non-M only (3,013 instructions), and 27 to neither (5,852 instructions). The 58 functions that are not the ZCP's, grouped by the subtree that reaches them:

| Subtree (this image) | Entered from | Functions (instructions, 6-gram containment in the ZCP partner / in the non-M partner) | Which version |
|---|---|---|---|
| 0x13630 branch, the wheel-speed side state | dispatcher 0x20ED4 → 0x13630 | 0x13EBA (789, 11% / identical), 0x13CA0 (269, 90% / identical), 0x148C0 (138, 32% / identical), 0x13724 (105), 0x14B7C (105), 0x137F6 (81), 0x13898 (65), 0x146E4 (63), 0x13A40 (59), 0x14C4E (57), 0x14690 (42), 0x14CC0 (28), 0x149D4 (22), and three under 20 | the non-M's, every one; the ZCP rewrote this branch (its 0x13F0E shares 11% with either) |
| power-state routine | the ASIC status reader 0x16E8C | 0x18AE4 (428, 4% / identical) | the non-M's 0x18A64, not the ZCP's 456-instruction rework; see below |
| driver-mode state machine | dispatcher, control-block init 0x1DF38 | 0x21870 init (37), 0x218BA state machine (43), 0x21B48 channel reader (26), 0x21B8C validity check (47), 0x21C34 stuck-button monitor (53), 0x21AAC (30), 0x21C02, 0x21C24, and the channel-1 button getter 0x1711C (22): all identical to the non-M; 0x217C0 table lookup (84, 92% / 92%) and 0x21CC0 row enable (34, 24% / 79%) unique | the non-M's two-channel machine with this build's own 28-row table; see the next section |
| 0x1DF88 arbiter table | the factory main cycle and others | 0x1E120 (31; the ZCP's 0x1E2AC is 23) | the non-M's |
| actuator driver | dispatcher → 0x1F44C | 0x1F834 (207, 5% / 77%) | neither: nearer the non-M's 0x1F6F8 (205) than the ZCP's 0x1F9C0 (276) |
| 0x21074 | dispatcher | 0x21074 (115, 98% / identical) | the non-M's |
| dispatcher | the 10 ms body 0x17FC2 | 0x20ED4 (129): every 6-gram occurs in the non-M's 0x20D58 (130), 99% in the ZCP's 0x210F8 | the non-M's minus one instruction; it has no call where the ZCP calls its sync routine |
| 0x2BC34 subtree (the ZCP's 0x2C1A2 subtree, RAM 0x400B00..0x400DFF) | dispatcher → 0x2BC34 | 0x2BC7A (350, 93% / 42%), 0x2C108 (67, 88% / 3%), 0x2C3D4 (563, 47% / 1%), 0x2C908 (237, 7% / 7%), 0x2CDA0 (356, 99% / 15%), 0x2E5A0 (461, 97% / 11%), 0x2EA98 (550, 40% / 89%), 0x2FA5C (183, 85% / 0%), 0x2FBCA (31), 0x3061C (264, 72% / 5%), 0x3092A (132, 50% / 9%), 0x30A4E (28); 0x2D162 (181, 99% / 3%) from the application init | ZCP-type functions edited once more; 0x2C908 is new against both, 0x2C3D4 half new; 0x2EA98 is the non-M's single function where the ZCP has four (0x2F03C, 0x2F1FC, 0x2EF90, 0x2F394, each fully contained in it) |
| fault manager | the 10 ms body → 0x328EE → 0x32FC8 | 0x33140 (571, 99% / 97%), 0x33640 (573, 92% / 92%), 0x33CAC (275, 88% / 88%), 0x32D12 (190, 95% / 95%), 0x32FD6 (181, 59% / 59%), `fault_latched` 0x32B48 (18 against 20) | small edits that the siblings do not share; 0x33CAC, 0x32D12 and 0x32FD6 have partners identical between ZCP and non-M, the two largest differ slightly among all three images |
| KWP handlers | the service table 0x3ED24 | SID 0x27 handler 0x31A82 (105, 92% / identical), SID 0x31 handler 0x3233C (125, 92% / identical), SID 0x18 handler 0x31FE0 (22, 71% / 71%), 0x31F48 (76, 89% / identical) | the non-M's security-access and routine-control handlers; the read-DTC handler is this build's own |
| 0x3A1BE | dispatcher → 0x3929C | 0x3A1BE (147, 95% / 14%) | ZCP-type, edited |

**The power-state routine** (this image 0x18AE4, the one that raises the restart request) is the non-M's 0x18A64 by signature, 428 instructions, where the ZCP has a 456-instruction rework of the same procedure. Its pool names the status block 0x401152/0x401154 and its base 0x40113C, the ASIC bytes 0x40062A, 0x400629 and 0x400626, the control word 0x40070C, the pulse words 0x4006C2, the window flags 0x4005AE and counter 0x4005AC, the mode byte block 0x4006E8 and the two 0xEF8200/0xEF8500 ports, the same objects as the siblings' at this build's RAM layout. The restart-request mechanism of the non-M (bit 0 of the window flags raised on the ASIC edge, the 10 ms body re-running the initialisation through 0x16770, the idle loop after it) is therefore present; its edge polarity is not re-derived here.

**The calibration tail.** Nine dataset names in the same order and inline structure as the siblings', the data section beginning 0x4C0 before the ZCP's. The family is a third one: `AUT?24F1` with the control dataset `AUPY2681.ZC5` at 0x3DB09 (ZCP `AWPY3381.ZC4`, non-M `APPY2782.KC8`); `NOPARADJ.UST` is the third name here (0x3D91C) where the ZCP has it eighth and the non-M has `APTW27F1.DFT`, and the eighth name is `AUTW24F1.DFT`. The twelve bytes after the control dataset name are `90 00 0E 81 0E 06 02 0E 1E 69 03 00` (ZCP `90 00 0E 80 0D 02 03 0B 14 74 03 00`, non-M `90 00 0E 72 1B 09 02 0F 30 70 03 00`), still undecoded. The three bytes after the baud mask table, 0x3DB09..0x3DB0B, spell the dataset prefix `AUP` (ZCP `AWP`, non-M `APP`). After the last name the 800-byte block 0x3F9CC..0x3FCEB follows, the one the ZCP keeps at 0x40000..0x4031F beyond the 256 KB mark: it is the ZCP's block byte for byte except eight bytes, the RAM addresses in the three coding-image descriptors (0x400FF2 length 0x7B version 1, 0x40100E/0x73/8, 0x401016/0x77/9, against the ZCP's 0x401022, 0x40103E, 0x401046 with the same lengths and versions and the non-M's 0x400FE0, 0x400FFC, 0x401004 with versions 1, 7, 8) and the image check word `4527 D066` at 0x3FCE4 (ZCP `E5A6 292C`, non-M `AC29 FDE9`). So the duplicated 34-byte curve records (0x7530 pairs at 0x3FAB6 with 0x1FE against 0x44C and 0x2EE against 0x578, 0x3E80 pairs at 0x3FBBE), the (3, 6) and (0, −1000) constant pairs at 0x3FC44 and 0x3FC48 and the four constants 0x1F, 0x1F, 0x14, 0x1E at 0x3FCB0 are all in this build. The data section is 12,278 bytes against the ZCP's 12,474 (which includes the 800-byte block) and the non-M's 11,850; the chip is erased from 0x3FCEC, 788 bytes.

**Concrete differences a reader would check for, and the answer.**

- Protocol-version bytes behind `0xA0` `3`: the same `0F 00 00 00`, at ROM 0x3DB26 (ZCP 0x3E04E, non-M 0x3DB64), read through the pool word of the class handler 0x1B37E, identical to the ZCP's 0x1B50A. The same two bytes of `FF FF` padding follow it before the UART records.
- UART records: nine 16-byte records at 0x3DB2C (ZCP 0x3E054), byte for byte the same except the driver pointers (0x1B7A4 and 0x1B7A0 against the ZCP's 0x1B930 and 0x1B92C): the same baud ids per digit, mode byte 0x08, gaps 0x14/0x16, 0xFA, 0x08.
- Baud divisors: the same words `0x5B 0x54 0x0F 0x08 0xB6 0x2E 0x1C 0x17 0x0E` at 0x3DADE (ZCP 0x3E006), so the same SCI clock and the same 4800-or-9600 reading; the mask table at 0x3DAF0 (ZCP 0x3E018) is identical.
- Command table: 30 slots at 0x3DC08 (ZCP 0x3E130), 28 populated, slots 5 and 28 empty in all three images, every populated slot the twin of the ZCP entry (slot 0 `mfg_addr_directory` 0x1BE8A, slot 1 the valve pulse set 0x1A230, slot 2 the pump level set 0x19958, slots 7 and 8 the request arbiter 0x1DF88 and the status getter 0x1E202, slot 9 `mfg_reply_send` 0x1B994, slot 26 the frame check 0x1B7D8, slot 27 the hex parser 0x1B940). The framed handler 0x1AF62 is identical, so class 0xA6 carries `M O K L Q` beside `Z` here too and the non-M doc's command-class table applies unchanged.
- KWP: the same 17-record service table at 0x3ED24 (ZCP 0x3F358, non-M 0x3EBB8) with the same SIDs, lengths, masks and levels; the dispatcher 0x31758 and `kwp_10` 0x31950 are identical to the ZCP's 0x31C20 and 0x31E18, so `10 87` is still the only accepted session sub-function and there is no download, reset or programming hand-off. `10 85` occurs twice at odd offsets inside instructions (0x2E6FF, 0x2E70D), `AA 00 8C` not at all. Two handlers are the non-M's rather than the ZCP's (SID 0x27 at 0x31A82, SID 0x31 at 0x3233C) and the SID 0x18 handler 0x31FE0 is this build's own; the rest are the ZCP's. KWP RAM: request SID 0x4011E3, sub-function 0x4011E4, request length 0x4011E7, NRC 0x4011E8, session byte 0x4011F4, security level 0x4011F5 (ZCP 0x401213, 0x401214, 0x401217, 0x401218, 0x401224, 0x401225).
- CAN: the twelve message records at 0x3DCC2 (ZCP 0x3E1EA) are identical bytes, so the same three transmitted frames 0x153, 0x1F0, 0x1F3 and nine received (0x316, 0x329, 0x1F5, 0x43F, 0x43D, 0x235, 0x615, 0x613, 0x610); the signal table at 0x3DE98 (ZCP 0x3E3C0) has 78 of its first 80 entries identical apart from the getter pointers; the transmit scheduler 0x1C1D8 and the controller setup 0x156AC are identical. The 0x153 byte 7 bit 1 getter is 0x1DC54, identical to the ZCP's 0x1DDE0.
- Identification strings: none; the only text is the dataset names above.
- Timer interrupt: split into selector and phase-0 body as in the ZCP (above), not the non-M's single ISR.

### The factory protocol on this unit, address for address

Every function of the manufacturing protocol in the non-M doc has an identical twin here (the same twins as in the ZCP), so the state table, the knock, the selection byte, the 130 ms rule, the state 5 wait, the idle timeout of 180, the access levels, the class and sub-command set and the bench recipe in `nonm_mk60_image.md` apply unchanged; only the addresses move. The RAM variables below are the pool words of those twins.

| Element | Non-ZCP (this image) | ZCP | Non-M |
|---|---|---|---|
| State machine jump table | 0x1ADDC, 13 entries 0x1ADE0..0x1B68C | 0x1AF68 | 0x1AD50 |
| State byte | 0x400700 | 0x400704 | 0x400710 |
| Main cycle | 0x1AD30 | 0x1AEBC | 0x1ACA4 |
| Session start | 0x1AD10, called only from the application init 0x16C4E | 0x1AE9C from 0x16D7E | 0x1AC84 |
| Session reset | 0x1A0FA | 0x1A286 | 0x1A06E |
| Window counter / flags | 0x4005AC / 0x4005AE (bit 0 restart request, bit 1 window flag) | same | 0x4005BC / 0x4005BE |
| Restart-request helper, RAM clear, re-init | 0x16D88, 0x1675A, 0x16770 | 0x16EB8, 0x1688A, 0x168A0 | 0x16D08, 0x166DA, 0x166F0 |
| Power-state routine, ASIC status reader | 0x18AE4 (the non-M's version), 0x16E8C (status byte block 0x400629..0x40062B) | 0x18C38, 0x16FBC (0x40062B..0x40062D) | 0x18A64, 0x16E0C |
| Knock state 1, selection state 2 | 0x1AE1A, 0x1AE54 | 0x1AFA6, 0x1AFE0 | 0x1AD8E, 0x1ADC8 |
| Framed-mode handler (state 6) | 0x1AF62 | 0x1B0EE | 0x1AED6 |
| Frame check | 0x1B7D8 | 0x1B964 | 0x1B74C |
| Class 0xA0 handler | 0x1B37E (version word 0x3DB26) | 0x1B50A (0x3E04E) | 0x1B2F2 (0x3DB64) |
| Class 0xA3 access-level machine | 0x1B190 | 0x1B31C | 0x1B104 |
| Class 0xA4/0xA5 memory handler | 0x1B496 | 0x1B622 | 0x1B40A |
| Command dispatch, command table | `mfg_cmd_dispatch` 0x1BE9C, table 0x3DC08 (30 slots, 28 populated) | 0x1C028, 0x3E130 | 0x1BE10, 0x3DC44 |
| UART records, baud divisors, mask table | 0x3DB2C, 0x3DADE, 0x3DAF0 | 0x3E054, 0x3E006, 0x3E018 | 0x3DB68, 0x3DB1A, 0x3DB2E |
| Reply send, hex value parser, hex pair parser | `mfg_reply_send` 0x1B994, 0x1B940, 0x1BA0C | 0x1BB20, 0x1BACC, 0x1BB98 | 0x1B908, 0x1B8B4, 0x1B980 |
| Banner send, hex format, hex byte, memcpy | 0x1BB6C, 0x1B8D4, 0x1B910, 0x1B964 | 0x1BCF8, 0x1BA60, 0x1BA9C, 0x1BAF0 | 0x1BAE0, 0x1B848, 0x1B884, 0x1B8D8 |
| ASIC SPI exchange | 0x1B9D2 via 0x3C374 | 0x1BB5E via 0x3C830 | 0x1B946 via 0x3C5BC |
| Valve pulse set, pulse value decode, pump level set | 0x1A230 (pattern words 0x4006B4), 0x1B6BE, 0x19958 (control word 0x40070C) | 0x1A3BC (0x4006B6), 0x1B84A, 0x19AE4 (0x400710) | 0x1A1A4, 0x1B632, 0x198CC |
| NVM request, synchronous read, job queue, job status, NVM wait entry | 0x16042 (block id 0x40118C), 0x163A6, 0x15F5E, 0x161E4 (table base pool 0x40115C), 0x1BA6A | 0x16172 (0x4011BC), 0x164D6, 0x1608E, 0x16314, 0x1BBF6 | 0x15FC2, 0x16326, 0x15EDE, 0x16164, 0x1B9DE |
| Control reset, checkpoint-fail flag, mode request | 0x1BBAC (state words 0x400DF4..0x400E00), 0x1BB2E, 0x18F38 via 0x3C1AC | 0x1BD38 (0x400E24..0x400E30), 0x1BCBA, 0x190C0 via 0x3C668 | 0x1BB20, 0x1BAA2, 0x18EB0 |
| Request descriptor, receive service, long-frame install, frame copy | 0x1BC3E (`mfg_rx_descriptor`, buffer 0x401030), `mfg_rx_service` 0x1BE28, 0x1BD8E, 0x1BD2C | 0x1BDCA (0x401060), 0x1BFB4, 0x1BF1A, 0x1BEB8 | 0x1BBB2, 0x1BD9C, 0x1BD02, 0x1BCA0 |
| Idle timer reload / count | 0x1BB1C, 0x1BA76 | 0x1BCA8, 0x1BC02 | 0x1BA90, 0x1B9EA |
| Request arbiter, three-byte status getter (slots 7, 8) | 0x1DF88, 0x1E202 (0x400858) | 0x1E114, 0x1E38E (0x40085C) | 0x1DE38, 0x1E0B2 |
| Reduced test-mode task | 10 ms body 0x17FC2 switches to 0x1804A when `ecu_mode_ge`(3) (0x1BC32) | 0x18116 → 0x1819E | 0x17F42 → 0x17FCA |

**Variables for the bench.** State 0x400700; flags 0x4006EA (bit 0 gates the cycle, bit 4 window armed); mode byte 0x4006E9 (level in the low three bits); session flags 0x4005BE; request buffer 0x4005E9, class byte 0x4005EA, sub-command 0x4005EB, hex fields 0x4005EC and 0x4005F4; reply buffer 0x4005BF; session block 0x4006F0 (pointer 0x4006F4); window counter 0x4005AC and flags 0x4005AE; raw wheel speeds 0x4005B2, 0x4005B4, 0x4005B8, 0x4005B6; fault latch array 0x400E08. The 0x4005xx block is at the ZCP's addresses; the 0x4006xx block sits 2 lower for the pulse words and 4 lower from 0x4006E8 up, the 0x4008xx block 4 lower, and everything from 0x400Fxx to 0x4011xx 0x30 lower, which is the two-channel mode block's saving plus the fault area's. The variables not read directly through a pool word (flags, mode byte, class and sub-command bytes, fault array) are derived from the block base plus the ZCP offset.

## What the ZCP build adds that this one lacks

The question was whether the M track mode of `zcp_m_track_mode.md` has any part here. The driver-mode logic of this image is the non-M's, not the ZCP's, while the calibration the ZCP selects by mode is present. Item by item, with the ZCP's address, this image's counterpart and the evidence:

| ZCP element | Here | Evidence |
|---|---|---|
| Third button channel: the getter 0x17278 reading bit 0 of the second input byte (0x40063D), pressed when clear | absent | the input copy routine 0x1704A (identical) fills 0x40063A..0x40063C from the same three port registers; the getters over that block are exactly the non-M's five, 0x17074 (byte 0 bit 0), 0x1709A (byte 1 bit 5), 0x170C6 (byte 0 bit 6, the DSC button), 0x170F2 (byte 1 bit 1), 0x1711C (byte 1 bit 6, pressed when clear, channel 1); nothing reads bit 0 of 0x40063B, and the ZCP getter's nearest match here is 0x1709A at 60%, the twin of a different getter |
| Three-channel mode block (init 0x21AA2 looping three times, arrays 0x400864..0x4008CF) | two channels | the init 0x21870 is the non-M's 0x21704 by signature: it loops twice and sets channel 0 to mode 1 and channel 1 to mode 9; state 0x400860, mode 0x400868 (`driver_mode_get` 0x21AE8 reads it), sub-value 0x400870 (0x21AF0), press counter 0x400878, saved counter 0x40087C, channel enable 0x400880 (setter 0x21AF8), fallback and stored bytes 0x400882/0x400883, published pair 0x400886/0x400887, stuck flag 0x40088A, row enables 0x40088C; the ZCP's change-tracking bytes 0x4008CB..0x4008CF have no counterpart (no pool word in the image names them) |
| Channel reader 0x21D8C with a channel-2 branch | 0x21B48, the non-M's 0x219DC | channel 0 through the DSC-button thunk 0x21B3C after the fault-byte check (0x400DFA bits 3 and 4), channel 1 through 0x1711C, anything else −1 |
| 37-row transition table at 0x3E9F8 | 28 rows at 0x3E4C4 | channel 0 has the non-M's fourteen rows exactly: rows 0..3 the two-position toggle, rows 4..9 the three-position cycle through mode 4 (press in mode 1 → mode 4 on release; press in mode 4 or 0 → mode 1; 358-tick hold → mode 0), rows 10..13 the 1431/500/25-tick long-hold rows; channel 1 has the ZCP's fourteen rows (the non-M has eight); no channel-2 rows. The ZCP's channel-0 rows 6..9 (what the DSC button does in mode 4) are not here |
| Row enable 0x21F80 over coding bit 6 of 0x40105A | 0x21CC0 over the coding getter 0x3B53A (the twin of the ZCP's 0x3B9F6, reading the coding block at 0x401016) | rows 0..3 are enabled when the getter returns non-zero, rows 4..9 when it returns zero, rows 10..27 always: the non-M's gating, so with the coding bit clear the DSC button cycles on → mode 4 → on and a long press turns DSC off |
| The sync routine 0x21FE0 (271 instructions) | absent | no function of this image shares more than 1% of its 6-grams; the dispatcher 0x20ED4 is the non-M's 0x20D58 less one instruction (every 6-gram contained) and calls the state machine 0x218BA with nothing in the sync position |
| Stuck-button fault 0xC2000 for channel 2 | absent | the monitor 0x21C34 (the non-M's) carries the channel-0 word 0x91000 in its pool and forms the channel-1 word from it, as the siblings do; 0xC2000 occurs in no pool word of this image, and the ZCP's monitor 0x21EC8 has no twin here |
| Mode-indexed calibration pairs in the tail block, read through the flag byte 0x401045 | present and read | the block is the ZCP's byte for byte (above); the reader 0x382B6 is identical to the ZCP's 0x3876E and reads `0x3FC00 + 0x22 × flag`, `0x3FC44[flag]` and `0x3FC48[flag]`, and the traction entry 0x3B080 is identical to the ZCP's 0x3B53C, writing `_is_mdm()` into the block at 0x40100E each cycle, so the flag byte is 0x401015 by the same offset (confirmed: `0x3B080` does `st.b` of `_is_mdm()` at `0x40100E+7`); the readers of the other three pairs (0x3FAB6.., 0x3FBBE..) are the ZCP's twins as well |
| Mode-4 consumers (0xE844 curve pairs, the lateral estimator 0x27220, the wheel-side 0x12E44, the traction cluster) | present | 0xE844 and 0x12E44 identical in place, 0x26CF8 identical to 0x27220, 0x36750 to 0x36C08, the traction functions 0x3518C..0x38768 identical |
| CAN 0x153 byte 7 bit 1 "mode 4 active" | present | the getter 0x1DC54 is identical to the ZCP's 0x1DDE0 |

So this image has a mode 4: the DTC-style third position of the single DSC button, selected by the coding bit as on the non-M, and when it is active the traction control takes the second record of each calibration pair, the same records the ZCP calls M track mode. What the ZCP build adds is the way of reaching it (a dedicated button on a new channel, the table rows for it, the routine that keeps the two channels agreeing, one fault code) and the 28 extra instructions of its power-state routine; what the ZCP takes away is nothing. The 0x40105A coding word of the ZCP sits at 0x40102A here by the layout above, and bit 6 of it is the gate; the start-up path that fills the coding image from EEPROM is not traced here either.

## Why it is the same lineage

The subsystems that change least across a product generation are present and recognisable, and they are the same ones the sibling docs found. Matching was done on relocation-masked instruction streams: exact FNV signatures through `tools/ghidra/mcore_port.sh`, then 6-gram containment through `tools/ghidra/mcore_fuzzy_port.sh` for functions of 20 or more instructions.

| This image | M3 counterpart | Match |
|---|---|---|
| 0x23A32 `wheel_post_frame` | 0x8F028 | 84% fuzzy |
| 0x1B964 `mfg_memcpy` | 0x7738C | 79% fuzzy |
| 0x1BB6C `mfg_banner_send` | 0x77690 | 75% fuzzy |
| 0x18108 `curve_interp_s16` | 0x710FC | 73% fuzzy |
| 0x1B8D4 `mfg_hex_format` | 0x772FC | 72% fuzzy |
| 0x20686 `outlet_valve_level` | 0x819A8 | 71% fuzzy |
| 0x14D70 `vec_misaligned_access` | 0x6CA18 | 71% fuzzy |
| 0x20624 `inlet_valve_level` | 0x818E6 | 70% fuzzy |
| 0x3518C `tcs_driveslip_curve` | 0xC795E | 70% fuzzy |
| 0x3151C `kwp_valve_pump_test_drive` | 0xB0154 | 69% fuzzy |
| 0x181F8 `interp_linear_xy` | 0x7119E | 67% fuzzy |
| 0x220D8 `tyre_tol_normalise` | 0xD0480 | 65% fuzzy |
| 0x205F8 `valve_flow` | 0x818B6 | 65% fuzzy |
| 0x1BE9C `mfg_cmd_dispatch` | 0x779D6 | 64% fuzzy |
| 0x250CC `steer_offset_fault_monitor` | 0xBF868 | 62% fuzzy |
| 0x363FC `tcs_yaw_slip_term` | 0xC9450 | 60% fuzzy |
| `mfg_hex_byte`, `pump_circuit_active`, `cluster_sensor_get`, `mfg_rx_buf_end`, `mfg_addr_directory`, `mfg_nvm_wait_enter`'s twin 0x1BA6A, `clamp_s16`, `clamp_sym16`, `abs_sat16`, `iir_blend_q8`, `pedal_at_most`, `fcan_rx_0D1_q1`, `fcan_rx_0CD_q0` and the rest of the 280 | same names | exact signature |

The scores are the ZCP's to the percentage point, as they must be: every function in this table is identical to its ZCP twin.

## How far it is from the E5

| Measure | Result (ZCP, non-M in brackets) |
|---|---|
| Functions with an identical signature in the M3 | 280 of 1585 (18%), nearly all short leaves [356 of 1670; 270 of 1576] |
| Identical functions of 20 or more instructions | 10 [11; 9] |
| Instruction mass inside identical functions | 1,460 of 106,745 (1%) [1%; 1%] |
| Large functions (20+ instructions) with under 10% 6-gram overlap with any M3 function | 602 of 832 [603 of 836; 613 of 834] |
| Large functions with 60% or better overlap | 45 [46; 40] |

The ten identical large functions are leaves (0x1A5AE, 0x1B910 `mfg_hex_byte`, 0x2072C `pump_circuit_active`, 0x28B76, 0x28C3A, 0x29518, 0x300CC, 0x3016C, 0x31620, 0x34BC0 `cluster_sensor_get`, 20 to 53 instructions). The control algorithms, the CAN layer and the diagnostic stack are the rewrite the non-M doc describes; nothing here moves the build closer to the E5.

## CCP is not compiled in

None of the M3's CCP routines has a counterpart: the best 6-gram containment of any `ccp_*` function in this image is 10% (`ccp_cmd_START_STOP`, against the unrelated 0x2A1EC) and 9% (`ccp_cmd_START_STOP_ALL`), every other one including `ccp_command_dispatch`, `ccp_dto_0x798_build`, `ccp_dnload_write`, `ccp_upload_read`, `ccp_session_reset` and `ccp_cro_rx_0797_handler` is at 0% to 2%, while the manufacturing routines of the same code base match at 61% to 100% (`mfg_hex_byte`, `mfg_rx_buf_end`, `mfg_addr_directory`, `mfg_nvm_wait_enter` at 100%). The image has no 36-entry command pointer table like the M3's at 0x7A720 (its pointer tables of 20 or more entries are the 64 vectors at 0x100, 20 at 0x16FB0, 24 at 0x18860, 255 at 0x2EFD8, 26 empty slots at 0x3D74A and the 30-slot command table at 0x3DC08), no `AZ1` station identifier, and no counterpart to `cfg_flag_toggle_ccp_mode` (best 2%) or `kwp_routine_31_ccp_mode` (0%). With no resident loader either, reprogramming this unit would have to come from outside.

## Names carried over

50 names are in the project, all applied by `ApplyPortedNames.java` with a plate comment naming the method, all from the ZCP project, which was ported first as the nearest sibling. The non-M and M3 ports then added nothing (the non-M's 46 names: 42 already placed, 4 without a twin here; the M3's: 20 already placed, 10 source-ambiguous), and the fuzzy pass at the 0.60 floor over the three sources applied nothing either. With `reset` and seven auto-named thunks the project has 58 non-generic functions.

| Source and method | Names | Which |
|---|---|---|
| ZCP, exact signature | 46 | `vec_misaligned_access` 0x14D70, `curve_interp_s16` 0x18108, `abs_sat16`, `clamp_s16`, `interp_linear_xy`, `iir_blend_q8`, `wheel_edge_count_get` 0x1A7FC, `uart_rx_byte_pending` 0x1B7B0, `mfg_hex_format`, `mfg_hex_byte`, `mfg_memcpy`, `mfg_reply_send` 0x1B994, `mfg_banner_send`, `mfg_rx_descriptor` 0x1BC3E, `mfg_rx_buf_end`, `mfg_rx_xform_byte`, `mfg_rx_decode_frame`, `mfg_rx_service` 0x1BE28, `mfg_addr_directory`, `mfg_cmd_dispatch` 0x1BE9C, `pedal_at_most` 0x1DE0A, `valve_flow`, `inlet_valve_level`, `outlet_valve_level`, `pump_circuit_active`, `driver_mode_is_on` 0x209A8, `_is_off`, `_is_mdm` 0x209FE, `clamp_s16_0D0444`, `tyre_tol_normalise`, `wheel_post_frame` 0x23A32, `wheel_speed_filter` 0x23F90, `ax_from_front_wheels_0C953C` 0x24AF4, `steer_offset_fault_monitor`, `clamp_sym16`, `clamp_sym16_091A26`, `kwp_valve_pump_test_drive` 0x3151C, `kwp_09_proprietary` 0x31B54, `cluster_fault_any`, `cluster_sensor_get`, `fcan_rx_0D1_q1` 0x34DE0, `fcan_rx_0CD_q0` 0x34E44, `tcs_driveslip_curve` 0x3518C, `tcs_yaw_slip_term` 0x363FC, `coding_image_checksum` 0x3B2B0, `coding_image_seal` 0x3B2EE (the ZCP's three hand-confirmed names crossed by exact signature) |
| ZCP, call-site propagation | 4 | `uart_flags_get` 0x15556, `ecu_mode_ge` 0x1BC32, `driver_mode_get` 0x21AE8 (confirmed: it reads the mode array 0x400868), `fault_latched` 0x32B48 (18 instructions against the ZCP's 20; the body differs, the role is the same call site) |
| Non-M, M3, fuzzy | 0 | — |

The fuzzy tail stops under the floor at the same candidates as in the ZCP, plus a few that the ZCP port had already named by hand. None was applied; the role column says what the call sites or tables make of them, for hand application later:

| Function | Instructions | Best M3 candidate | Score | What it is here |
|---|---|---|---|---|
| 0x23964 | 36 | `wheel_minmax_select` 0x8EFB0 | 52% | wheel-speed min/max selection, just below `wheel_post_frame` |
| 0x170F2 | 21 | `dsc_button_input_get` 0x6FEE4 | 50% | the byte 1 bit 1 switch getter; the ZCP doc's call-site reading says it is not the DSC button, which is 0x170C6 |
| 0x383A8 | 52 | `tcs_torque_intervention_flag` 0xCB708 | 49% | traction-control torque-intervention flag |
| 0x24F46 | 142 | `steer_offset_learn` 0x9020C | 45% | steering-offset learning, beside `steer_offset_fault_monitor` |
| 0x2576C | 71 | `steer_yaw_residual_update` 0x90BC0 | 43% | the same lateral branch |
| 0x21B8C | 47 | `mode_transition_valid` 0x8D570 | 40% | the transition validity check the table lookup 0x217C0 calls: the role matches |
| 0x329A4 | 113 | `fcan_fault_report` 0xBFA2A | 40% | fault reporting over CAN |
| 0x3B3F8 | 80 | `coding_image_load` 0xCFB14 | 37% | the coding-image loader beside `coding_image_seal` |
| 0x1804A | 24 | `task3_10ms_testmode_cycle` 0x70FB6 | 37% | the reduced test-mode task the 10 ms body switches to: the role matches |
| 0x1BA76 | 51 | `mfg_idle_timer_tick` 0x775B0 | 35% | the idle-timer count of the protocol table: the role matches |
| 0x17FC2 | 68 | `task_10ms_body` 0x71000 | 34% | the 10 ms body itself: the role matches |
| 0x1640E | 33 | `nvm_status_write` 0x6EA36 | 36% | beside the NVM job routines |

## See also

Later notes on this image:

- `e46_m3_mk60_image_check_word.md` — the vector and main-range check word (left-shift MISR at `0xEF8400`).
- `e46_m3_dataset10_vs_zcp.md` — dataset-10 A/B/T0/T3/T4, the live `k_ds` mapping (`BAUART_MK60 = csl` → 10 on the example C06 car), the mode-1 / mode-4 oversteer bars, and the 818.3 versus ZCP calibration inventory.
- `e46_m3_mk60_kline_access_and_flash.md` — factory K-line access, the absence of a bootloader, and the memory map.
- `e46_steering_ratio_tables.md` — the six steering-ratio sets, including set 2 versus set 4.
- `e46_m3_mk60_mtrack_mode4.md` — every 818.3 read of `_is_mdm` / the cached flag at `0x401015`, and what M-track changes (yaw bars, ASC slip, status bits) versus what it does not (ABS, A/B, `r_ref`).
- `e46_m3_mk60_tooling_status.md` — M·CORE tooling (Ghidra / binutils / IDA / Binary Ninja) and measured coverage of the Ghidra recovery of this image.

## Open questions

- [x] Completeness: the data ends at 0x3FCEC with 788 erased bytes to 0x40000 and no pool word points past the end; the tail block the ZCP lost to truncation is inside the 256 KB here.
- [x] Whether this image has a mode 4: yes, the coded three-position DSC-button cycle of the non-M, with the ZCP's mode-indexed calibration pairs behind it; the M button channel, its table rows and the sync routine are the ZCP's additions.
- [ ] Which bus it speaks: the CAN records are the ZCP's; `fcan_rx_0D1_q1` 0x34DE0 and `fcan_rx_0CD_q0` 0x34E44 are the entry points, and `zcp_can_frames.md` should apply with the addresses shifted.
- [ ] What the third-generation edits in the 0x2BC34 subtree do (0x2C908 new against both siblings, 0x2C3D4 half new, 0x2EA98 kept whole where the ZCP split it) and in the fault manager (0x32FD6 at 59% against both): the partners are placed in the same order and share RAM, so a side-by-side decompilation is the direct route.
- [ ] Why this build keeps the non-M's wheel-speed side state (the 0x13630 branch, 0x13EBA) and power-state routine while taking every other ZCP rework: whether these are older or newer than the ZCP's cannot be told from the images alone; the dataset revision `24F1` against the ZCP's `26F1`/`33F1` suggests older.
- [ ] Part number and variant: no identification block; the dataset family `AUT?24F1` / `AUPY2681.ZC5`, the stamp bytes at 0x3DB15 and the prefix bytes `AUP` at 0x3DB09 are the only variant markers found.
- [ ] The polarity of the ASIC status bit and the edge the restart request fires on: inherited from the non-M reading.
- [ ] The near-miss names above, four of them corroborated by role (0x21B8C, 0x1804A, 0x1BA76, 0x17FC2).

## Method and provenance

Completeness check before anything else: every `lrw` in the code region of the raw file resolved to its pool slot (`slot = ((pc + 2) & ~3) + (imm8 << 2)`) and the slot values in [0x40000, 0x400000) listed; the same scan on the ZCP file reproduces its thirteen words above 0x40000 beside the shared mask constants, and on this file yields only the mask constants. Recovery: `tools/ghidra/mcore_import.sh` with raw `.bin` input at `BASE=0`, then `mcore_recover.sh` (three rounds, first-pass work 526, 20, 0) and `mcore_verify.sh` (HEALTHY), at commit 6ea098d; no pipeline change was needed. Name porting: `mcore_port.sh` from `projects/817.3_e46_m3_zcp_mk60` first, then from `projects/820.3_E46_non_m_mk60` and `projects/E9x_M3_7846816A`, then `mcore_fuzzy_port.sh` with the three as sources in that order (`DumpMasked.java`, `tools/fuzzy_port.py`, `ApplyPortedNames.java`); the work files are in `projects/e46_m3_non_zcp_mk60/port_work/`. The identity and distance figures come from the `ExportSigs` and `DumpMasked` outputs of that port (identical signatures, 6-gram containment, call edges to the 10 ms body); the protocol addresses from the exact-signature twins of the ZCP functions and the pool words their bodies load, read from the raw image; the transition table, the KWP table, the command table, the UART and CAN records and the tail block from byte comparison of the three raw files; the row-enable, button-getter, channel-reader and mode-init listings from `tools/ghidra/dump.sh` on a snapshot copy. No project other than this image's own was modified. Companion documents: `zcp_mk60_image.md` (whose subtree table names the ZCP forms this build shares), `zcp_m_track_mode.md` (the mode logic this build has the non-M half of), `nonm_mk60_image.md` (the factory protocol and command-class table, unchanged here), `zcp_can_frames.md`, `kwp_dispatch.md`, `mfg_test_cycle.md`. Steering-ratio tables: see `e46_steering_ratio_tables.md`. See also: `e46_m3_mk60_image_check_word.md`, `e46_m3_dataset10_vs_zcp.md`, `e46_m3_mk60_kline_access_and_flash.md`, `e46_m3_mk60_mtrack_mode4.md`, `e46_m3_mk60_tooling_status.md`.
