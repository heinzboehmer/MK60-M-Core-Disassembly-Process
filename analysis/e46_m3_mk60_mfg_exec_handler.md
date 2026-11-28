# E46 MK60 818.3 (M3) — manufacturing A4/A5/A6 exec handler (located)

Static analysis of `818.3.bin` (md5 `7febd4b26eab79154411efc96c035b11`, MCORE:BE:32,
base `0x0`), confirmed by a read-only bench capture. Evidence only; nothing was sent
to the module for the static work. This supersedes the earlier "A5/A6 handler not in
this image" note: the handler **is** in-image; prior static scans missed it for
reasons proven below.

## Bench anchor — return-address capture **[C]**

A read-only M·CORE stub was uploaded via the factory A6 `K`/`L`/`O` long-frame path
and executed. Instead of a memory read it stored its own link register `r15` (the
address the exec handler `jsr`'d from) to absolute scratch `0x00401400`, framed by
`0x55`/`0x66` sentinels, then `jmp r15`.

Stub (28 bytes, verified on Ghidra MCORE:BE:32):

```
33 65 61 46 3C 86 1E 65 65 53 B3 05 60 03 B3 15 B3 25 B3 35 9F 15 66 63 B3 85 00 CF
  bgeni r5,0x16 ; movi r6,0x14 ; lsli r6,8 ; or r5,r6   -> r5 = 0x00401400
  movi r3,0x55 ; st.b r3,(r5,0)                          -> +0 "ran"
  movi r3,0 ; st.b r3,(r5,1..3)                          -> zero +1..+3
  st.w r15,(r5,4)                                        -> +4..+7 = r15
  movi r3,0x66 ; st.b r3,(r5,8)                          -> +8 "returned"
  jmp r15
```

Read-back `0xA4 'U' 0x00401400 len 9` returned:

```
55 00 00 00 | 00 01 BE 80 | 66
```

So `r15 = 0x0001BE80` — the exec handler's `jsr` into the uploaded stub is **in-image**,
immediately before `0x1BE80`.

## The exec handler — `FUN_0001be28` @ `0x0001BE28` **[C]**

`r15 = 0x1BE80` is the return site of `jsr r14` at `0x0001BE7E`:

```
0x1BE74  ld.h r6,(r14,0)   ; r6 = descriptor[0]  (the 0x0000 offset written by L-append)
0x1BE76  mov  r2,r14       ; r2 = descriptor
0x1BE78  addu r14,r6       ; r14 = desc + desc[0]
0x1BE7A  addi r14,6        ; r14 = desc + desc[0] + 6
0x1BE7C  addi r2,2         ; r2  = desc + 2   (argument passed to the stub)
0x1BE7E  jsr  r14          ; enter stub; link r15 = 0x1BE80
0x1BE80  br   0x1BE84      ; return lands here
```

Decompiled gating:

```c
undefined4 FUN_0001be28(uint cmd) {              // cmd = exec control code
  if (FUN_0001bc32(3) != 0) {                     // session LEVEL gate: level >= 3
    desc = FUN_0001bc3e();                         // descriptor base (RAM 0x4011xx)
    if (*(char*)(0x004006E9) < 0) {               // state flag bit7 set?
      if (cmd == 0x82) return FUN_0001bc92();      //   -> append/K path
    } else if ((*(byte*)(desc-3) & 0x81) == 0x81) {// control byte @desc-3 == exec-ready(0x80)|0x01
      if (cmd == 0x81) goto EXEC;
    } else if ((cmd & *(byte*)(desc-3)) == cmd) {
      EXEC:
        *(char*)(desc-1) = (char)cmd;
        return (*(code*)((desc + *desc + 6) & ~1))(desc+2);   // jsr desc + desc[0] + 6
    }
  }
  return 0;
}
```

- Entry: `jsr`, so the stub receives `r15` = the handler return address (`0x1BE80`) and
  `r2` = `desc+2`.
- Gates: session **level ≥ 3** (`FUN_0001bc32(3)`), and the **control byte at `desc-3`**
  must read `0x80` (exec-ready), set by the K-finalize path.
- The `'O'` sub-command reaches this via trampoline `word@0x1B6DC = 0x0001BE28`.

## Why static scans missed it **[C]**

1. **No literal `0xA4`/`0xA5`/`0xA6` compare exists anywhere in the image** (full-image
   scan: zero hits). The class byte is converted to a small table index upstream and
   dispatched through a function-pointer table — there is nothing for a "compare to
   0xA5/0xA6" scan to find.
2. The actual code entry is a **register-indirect `jsr r14`** to a RAM descriptor
   address computed at runtime (`desc + desc[0] + 6`). There is therefore no static
   code cross-reference into the stub, and no in-image flow reference into RAM, so an
   "executed RAM / flow-into-RAM" scan also finds nothing.

## Dispatch structure **[C]**

- **Class dispatcher** `FUN_0001be9c` @ `0x0001BE9C`:
  `if (idx < 0x1E && (f = *(u32*)(0x0003DC08 + idx*4)) != 0) ret = (*(f & ~1))(...);`
  30-entry function-pointer table at **`0x0003DC08`**. A second 19-entry table at
  `0x0003DBBC` (mostly RAM data pointers) is read via `FUN_0001be8a`.
- **Sub-command parser** — one large flat routine, approx `0x0001B1C0 .. 0x0001B6xx`,
  entered through the table above (Ghidra does not bound it as a function because it is
  reached by computed dispatch). It reads the sub-command ASCII char at `frame+2` and
  branches by character:
  - A5 block `0x1B200..0x1B372`: `T`(0x54) `W`(0x57) `G`(0x47) `U`(0x55) `V`(0x56)
    `I`/`J`/`H`(0x49/0x4A/0x48) `X`(0x58). `U` copies N bytes into the mfg RAM buffer
    at `0x004005BF+3`.
  - A4 block `0x1B37E..` gated `level >= 2`: `R`(0x52) `U`(0x55) `T`(0x54) `X`(0x58).
  - A6 block `0x1B4C0..0x1B5CE`: `M`(0x4D) `O`(0x4F) `K`(0x4B) `L`(0x4C) `Q`(0x51),
    via trampolines `word@0x1B6D8=0x1BC3E` (descriptor getter), `word@0x1B6DC=0x1BE28`
    (`O` exec), `0x1BD8E` (K-finalize), `0x1BD2C` (L-append), `0x1B994` (reply emit).
    `K` validates a session byte with `movi r6,0x23 ; xor` (the XOR-0x23 gate) plus a
    checksum byte.
- **Level gate** `FUN_0001bc32(n)` @ `0x0001BC32` = `n <= (*(0x004006E9) & 7)`; exec
  needs `n=3`, the A4 read block needs `n=2`.

## Descriptor / RAM layout **[C]**

Descriptor getter `FUN_0001bc3e` @ `0x0001BC3E`:

```c
lvl  = FUN_0001bc32(4);                               // level >= 4 ?
base = lvl ? 0x00401030 : 0x0040113C;                 // pool words @0x1BEF8 / @0x1BEFC
if ((*(byte*)0x004006E9 & 0x80) == 0)                 // state-flag bit7 clear ->
    base = ((base + 8) & ~3) - 2;                      //   adjust
return base;
```

- **Session level 3** (the exec minimum) ⇒ base `0x0040113C` ⇒ adjusted **`0x00401142`**,
  control byte at **`0x0040113F`**.
- Session level ≥ 4 ⇒ base `0x00401030` ⇒ adjusted `0x00401036`, control byte `0x00401033`.
- Companion `FUN_0001bc64` returns the buffer top: `0x00401800` (level ≥ 4) or
  `0x00401700` (level 3). mfg RAM buffer base `0x004005BF`; state/level byte
  `0x004006E9` (level in low 3 bits, bit7 a mode flag).

The live exec session is level 3, so the descriptor is **`0x00401142`** with its
control byte at `0x0040113F`. This matches the independently-derived 818.3 descriptor
`0x401142`.

### Reconciliation with the bench tool's `EXEC_CTRL` constant **[C]**

`bench/core/src/ops.rs:1121` defines `EXEC_CTRL = 0x0040_11A7` (and the test-only
`EXEC_DESC = 0x0040_11AA`, ops.rs:2738). These are **not** the descriptor the handler
uses. They are referenced only by:

- a diagnostic log read `mfg_read_reply(br, EXEC_CTRL, 1)` (ops.rs:1352-1353), and
- test assertions (ops.rs:2749-2750).

The O-exec frame is sent **unconditionally** right after that log line (ops.rs:1356-1357);
the log does not gate anything. During the live run this read printed
`control byte @0x004011A7: FF (NOT 0x80)` **yet exec still succeeded**, because:

- The real control byte is at `desc-3 = 0x0040113F` (descriptor `0x401142`), and the
  handler's K-finalize set **that** byte to `0x80` — the handler gates on the real
  descriptor it computes itself, never on the tool's constant.
- The stub's marker landed at `0x00401400` because that address is **absolute, baked
  into the stub bytes** (`EXEC_MARK`/`REG_SCRATCH = 0x401400`), independent of the
  descriptor.
- Planting is **handler-driven**: the tool sends only A6 `K`/`L`/`O` opcodes + data;
  the handler places the descriptor and stub at its own computed location. The tool
  never writes to `0x4011A7`/`0x4011AA`.

So `EXEC_CTRL`/`EXEC_DESC` are **wrong for 818.3 (M3) but harmless** — they only make
one diagnostic log line misleading ("NOT 0x80" even on a successful exec). Proposed
tool fix (doc note; not applied here): make the control-byte log read `desc-3` of the
real descriptor (`0x0040113F` for the level-3 case the tool targets), or drop the
constants, since they are purely informational. **[open]** for Bryson to approve the
code change; the handler behaviour itself is fully resolved.

## Flash / EFFFxx: no native program/erase sub-command **[C]**

- Full scan of the handler region `0x1B000..0x1D000`: **zero** references to
  `EFFFxx`/`EFFBxx`/`EFFAxx`/`D000xx`. The handler never drives the flash controller.
- The only "write to a computed address" primitive is A4 `R` / A5 `T` (`0x1B38A` /
  `0x1B3F2`): a plain `st.b`/`st.h`/`st.w` (width from the frame) of caller data to a
  caller-supplied target address `r13`. It can target any address, including EFFFxx
  MMIO or flash space, but it is a **single CPU store**, not the controller's
  unlock+program/erase sequence — by itself it cannot program NOR flash.
- Real programming is only reachable by **A6 `K`/`L`/`O`: upload arbitrary M·CORE code
  and `jsr` it**. Any flash programmer would have to be such uploaded code. There is no
  built-in program/erase mfg op.

## Why a bare raw A4 read only echoed **[C]**

The A4 read path is both level-gated and framing-specific:

- Level gate at `0x1B384`: `ld.b r1,(r8,1); andi r1,7; cmphs r1,2; bf 0x1B5E6` — requires
  session **level ≥ 2**, else it falls to the error/echo path.
- Framing: the working read is `0xA4 'U' <addr:8 ASCII hex> <len:8 ASCII hex>`; the
  handler parses those fields (count from the parsed params, address pre-parsed into
  `r13`).

The exec op's built-in read works because the `K`/`L`/`O` exec already holds the
session at the required level and emits the properly-framed `0xA4 'U'`; a standalone
raw A4 frame that did neither only gets an echo/NAK.

## Key addresses

| what | address |
| --- | --- |
| exec `O` handler | `FUN_0001be28` @ `0x0001BE28` |
| exec `jsr` site / return | `0x0001BE7E` / `0x0001BE80` (= captured `r15`) |
| target / arg | `r14 = desc + desc[0] + 6`, `r2 = desc + 2` |
| class dispatcher | `FUN_0001be9c` @ `0x0001BE9C` |
| 30-entry handler table | `0x0003DC08` |
| 19-entry table | `0x0003DBBC` |
| sub-command parser | ~`0x0001B1C0 .. 0x0001B6xx` |
| descriptor getter | `FUN_0001bc3e` @ `0x0001BC3E` → `0x401142` (lvl3) / `0x401036` (lvl≥4) |
| buffer-top getter | `FUN_0001bc64` @ `0x0001BC64` → `0x401700` / `0x401800` |
| level gate | `FUN_0001bc32` @ `0x0001BC32` |
| K-finalize / L-append / reply | `0x0001BD8E` / `0x0001BD2C` / `0x0001B994` |
| `O` trampoline / desc trampoline | `word@0x1B6DC = 0x1BE28` / `word@0x1B6D8 = 0x1BC3E` |
| mfg RAM buffer base / state byte | `0x004005BF` / `0x004006E9` |
| exec scratch (stub marker) | `0x00401400` |

No `0xA4`/`0xA5`/`0xA6` literal compares exist in the image; no EFFFxx references in
`0x1B000..0x1D000`.

### Reproduction (static, read-only)

Import `818.3.bin` as MCORE:BE:32 base `0x0`. Disassemble `0x0001BE28` (contains the
`jsr r14` at `0x1BE7E`); decompile `FUN_0001be9c` (`0x1BE9C`), `FUN_0001bc3e`
(`0x1BC3E`), `FUN_0001bc32` (`0x1BC32`); dump the 30-entry table at `0x0003DC08`; and
read the ASCII sub-command compares across `0x1B200..0x1B5CE`. The tool-constant
reconciliation is in `bench/core/src/ops.rs` lines 1121, 1352-1357, 2738, 2749-2750.
