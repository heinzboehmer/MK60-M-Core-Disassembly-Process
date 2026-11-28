# Supervisor escape via the SS4 fault trigger (mfg exec path)

**Result (CONFIRMED on 818.3, bench): the mfg exec stub can reach SUPERVISOR mode.** A stub that executes
`mfcr r1,ss4` as its first instruction faults into the supervisor exception handler, which resumes our
code **elevated** - so the following privileged instructions (incl. `mfcr r1,psr` and, we expect, flash
controller access) run with S=1. This is the sandbox escape; the long-standing privilege wall on flash
write is down.

## Background (why this matters)

The flash-write blocker was always **privilege, not addressing**: the flash controller registers are
supervisor-only, so a user-mode access faults like an unmapped address. The mfg exec path (K/L/K/O
descriptor upload + `O` trigger) gives arbitrary RAM code-exec, but it was believed to run in USER mode -
an earlier instrumented stub's `mfcr r1,psr` trapped and the stub died. That pinned flash write shut:
arbitrary code, sandboxed below the controller's privilege gate.

It turned out the user-mode verdict was only true for a stub whose **first** privileged op is `mfcr psr`.
Prefixing `mfcr r1,ss4` changes the outcome entirely.

## The mechanism (disassembled) - a deliberate syscall gate

`mfcr` in user mode raises a privilege violation, which vectors through the table at 0x0: vector 5
(offset 0x14) -> handler at **0x14CEC**. Every OTHER exception vector points at a near-identical stub that
saves EPC, calls a fault reporter (ptr at 0x14DD0 -> 0x15420), sets a PSR bit, and `br .` - an
**infinite-loop HANG**. The vec-5 handler is the sole exception: it decodes the faulting instruction and
conditionally ELEVATES. (Disassembled from the 820.3 sibling image; the 818.3 bench run elevating is the
proof the same gate is present there.)

```
14cec  mtcr r2,SS2 ; mtcr r3,SS3    ; save scratch regs
14cf0  mfcr r2,EPC                  ; r2 = faulting PC
14cf2  ld.h r3,(r2,0)               ; r3 = faulting instruction (zero-extended to 0x0000xxxx)
14cf4  lrw  r1,[0x14dd4]            ; r1 = magic opcode = 0x000010A1
14cf6  cmpne r1,r3
14cf8  bt   0x14d0e                 ; mismatch -> fall through to HANG
14cfa  mfcr r1,EPSR
14cfc  bseti r1,0x1f                ; *** set EPSR bit 31 (S) ***
14cfe  mtcr r1,EPSR                 ; the resumed context becomes SUPERVISOR
14d00  movi r1,0x1 ; mtcr r1,SS0    ; SS0 = 1  (and leaves r1 = 1)
14d04  addi r2,0x2 ; mtcr r2,EPC    ; EPC += 2  (step over the faulting instruction)
14d08  mfcr r2,SS2 ; mfcr r3,SS3    ; restore scratch
14d0c  rte                         ; resume, elevated, past the instruction
14d0e  <HANG: jsri reporter ; bseti PSR bit4 ; br .>
```

The magic opcode (literal at 0x14DD4) is **`0x000010A1` = `mfcr r1,ss4`**, confirmed. So the gate is:
"fault on exactly `mfcr r1,ss4` -> grant supervisor and step past it; fault on anything else -> hang." This
is the firmware's own **user->supervisor syscall** - the main loop runs user and executes `mfcr r1,ss4` to
elevate when it needs privileged work (the recurring "SS4 idiom" across the image is exactly this
elevate-then-work pattern; in code already supervisor, the same instruction is a harmless dead read). We
invoke the identical gate from an injected stub.

This explains the earlier probes exactly:
- lone `mfcr r1,psr` (0x1001): faults -> gate -> `0x1001 != 0x10A1` -> **HANG** (the stub "died").
- `mfcr r1,ss4` first: faults -> **matches** -> elevate + step over -> resume supervisor -> the following
  `mfcr psr` runs and returns a real PSR with S=1.

Side effects of the gate, to account for in any stub (state on return):
- **SUPERVISOR** until S is cleared (next rte to a user EPSR, or an explicit `mtcr psr`); it persists for
  the rest of our stub.
- **r1 = 1** (handler leftover from `movi r1,0x1`) - this is what the combined probe stored as "SS4=1"; it
  was never a real SS4 read. The `mfcr ss4` is *stepped over*, never executed, so don't expect SS4's value.
- **SS0 = 1**, set by the gate (see the nesting model below).
- r2, r3 are preserved (saved/restored via SS2/SS3); other GPRs untouched.

## The privilege-nesting model (SS0) and clean exit

SS0 is the firmware's **privilege-nesting counter**, a one-hot shift mask (resolved from the exit routine
`FUN_00015392` at 0x15392 and the setup at 0x15384):
- The gate sets `SS0 = 1` = depth 1 (outermost privileged section).
- Entering a *nested* privileged section shifts it up (`SS0 <<= 1`), gated by **PSR bit 8** - a software
  "in a privileged section" flag the firmware sets when it drops to user (0x15384 does `bseti r7,0x8 ;
  bclri r7,0x1f ; mtcr r7,psr`).
- Exiting shifts it down (`SS0 >>= 1`); when the shift reaches `1` (bit 0 set = outermost), the firmware
  **clears S and returns to user** (`mfcr r1,psr ; bclri r1,0x1f ; mtcr r1,psr`).

So `SS0 = 1` just means "privilege depth 1" - the normal state right after any gate call, and nothing to
worry about on its own. The **fault reporter (0x15420)** that the hang path calls is a **no-op `jmp r15`**;
a mis-fault just wedges silently (no log).

**Getting out is free and direct - no gate, no SSx call.** The elevation is gated in only one direction:
the instruction that sets S (`mtcr ...,psr`) is itself supervisor-only, so it *traps* from user - which is
why the fault gate is the sole way *up*. Coming *down* is always allowed from supervisor, because dropping
privilege needs no permission: just run `mtcr psr` with S cleared and you are in user mode on the next
instruction. There is no "de-elevate" opcode and no SSx trick for the exit - other SSx accesses are either
harmless reads (when already supervisor) or a non-magic fault -> hang (from user).

**Clean exit for our stubs.** Our probes returned (`jmp r15`) still elevated (S=1, SS0=1) without
unwinding, and it was tolerated - the firmware re-syncs on its next gate/exit. But a longer or
write-capable stub should **de-elevate before returning**, matching the firmware's own exit, to avoid
desyncing its S/SS0 accounting:
```
...privileged work...
10 01   mfcr  r1, psr      ; 1001
31 F1   bclri r1, 0x1f     ; clear S (bit 31) -> back to USER
18 01   mtcr  r1, psr      ; 1801
00 CF   jmp   r15
```
Notes:
- Leaving `SS0 = 1` is fine - it is a clean depth-1 marker; the S bit is what matters.
- `rte` would also drop to user (it restores PSR from EPSR), and is how the gate handler itself returns,
  but it needs EPC/EPSR staging and buys nothing over the direct `mtcr psr` clear above.
- The full round trip for a write-capable stub is: `mfcr ss4` (elevate) -> privileged work ->
  `mfcr psr / bclri 0x1f / mtcr psr` (drop to user) -> `jmp r15`.

## Proof (the combined SS4 + PSR probe)

The clobbering stub (`mfcr ss4 ; mfcr psr` into the same r1) was ambiguous. This probe stores SS4 and PSR
to **separate** slots so both reads are visible.

Stub (32 bytes), **P1 = 0x0AB60022**:
```
10 A1 33 65 61 46 3C 86 1E 65 91 15 65 53 B3 05 10 01 91 25 33 F4 16 41 91 35 66 63 B3 15 00 CF
```
```
off  bytes   instruction        ; effect
00   10 A1   mfcr   r1, ss4      ; r1 = SS4  (trigger)
02   33 65   bgeni  r5, 0x16     ; r5 = 0x0040_0000 ...
04   61 46   movi   r6, 0x14     ;
06   3C 86   lsli   r6, 8        ;
08   1E 65   or     r5, r6       ; r5 = 0x0040_1400  (scratch)
0A   91 15   st.w   r1, (r5,4)   ; [+4] = SS4   (captured before psr clobbers r1)
0C   65 53   movi   r3, 0x55     ;
0E   B3 05   st.b   r3, (r5,0)   ; [+0] = 0x55  (entered)
10   10 01   mfcr   r1, psr      ; r1 = PSR
12   91 25   st.w   r1, (r5,8)   ; [+8] = PSR
14   33 F4   bgeni  r4, 0x1F     ; r4 = 0x8000_0000
16   16 41   and    r1, r4       ; r1 = PSR & 0x8000_0000  (isolate S)
18   91 35   st.w   r1, (r5,12)  ; [+C] = S bit
1A   66 63   movi   r3, 0x66     ;
1C   B3 15   st.b   r3, (r5,1)   ; [+1] = 0x66  (completed)
1E   00 CF   jmp    r15          ;
```

Bench readback (`scratch 0x401400 x9`): `55 66 00 00 00 00 00 01 80`
- `+0=0x55, +1=0x66` -> stub ran to completion (no trap-kill).
- `+4..+7 = 0x0000_0001` -> **r1 = 1** at the first store. This is NOT an SS4 read: per the handler
  disassembly above, the gate *steps over* the `mfcr ss4` and leaves `r1 = 1`, which the stub then stores.
- `+8 = 0x80` -> top byte of PSR -> **bit 31 (S) = 1**.

The decisive fact is `+8`: `mfcr psr` is supervisor-only, and it returned a word with **S=1** - so it
executed privileged, i.e. we were already elevated by the time it ran. => **supervisor, confirmed.** (Read
`x16` next time to log the full PSR word at +8..+B and the isolated S flag at +C; `+8=0x80` already settles
it.) This also vindicates the earlier `0x803C0150` - top byte `0x80`, so that was the real PSR, not leftover.

Decision table for this probe:
| readback | meaning |
|---|---|
| `+0 != 0x55` | trapped at `mfcr ss4` and killed -> user; SS4 is NOT a trigger here |
| `+0=55, +1=66`, **`+4 != +8`**, `+8` top byte `0x80` | both reads ran, PSR distinct with S=1 -> **SUPERVISOR** |
| `+0=55, +1=66`, **`+4 == +8`** | PSR returned nothing new; run the no-mfcr baseline to tell leftover from a user-readable SS4 |

Note: the descriptor control byte reads `FF` (never `0x80`-armed) on every run - a red herring. The 0x55/0x66
sentinels prove the stub executes regardless.

## Open items
- Confirm elevation persists long enough, and across the KL/L-append framing, for a full program sequence.
- The part reads UNSECURED (security word 0xFFFFFFFF), so no backdoor/mass-erase is needed once we can
  drive the controller.
- Elevated halfword reads of the MMC2114-family candidate bases (`0xD0001C` and the other `0xD00000`
  offsets tried; a single probe of `0x00F60000`) faulted after this elevate on the 818.3 spare
  (2026-11-26). Not a live flash-controller map from elevated mfg A6 exec. See
  `e46_m3_mk60_elevated_mmio_findings.md`.
