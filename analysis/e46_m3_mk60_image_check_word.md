# E46 MK60 image check word

Hand-written note (2026-11-20). Companion to `e46_m3_non_zcp_mk60_image.md`, `zcp_mk60_image.md` and `nonm_mk60_image.md`. Addresses are from the 818.3 non-ZCP image unless a ZCP or non-M twin is named. A small verifier is `tools/check_word_verify.py`.

The three E46 application images each end their used flash with a 32-bit word that start-up and a later walk compare against a hardware residue. This note identifies that residue, the ranges it covers, what a mismatch does, and which common BMW checksums it is not.

## The range table

`0x15CAC` is a start-up **range-table validator** and a clear of the CRC/MISR unit. It is not the checksum itself. The compare lives in `0x15BDC`.

A 10-record table of `(start, end)` pairs is stored in the calibration tail. The walk insists on exactly ten records. On all three images the table has the same shape:

| Record | Start | End | Role |
|---|---|---|---|
| 0 | `0x0` | `0x2F8` | exception vectors; the stored word sits at `0x2F8` |
| 1–8 | `0x0` | `0x8` | unused placeholders (the word they would “compare” is whatever sits at `0x8`, and is not used as an image check) |
| 9 | `0x300` | *word address* | main used image, exclusive of the stored word |

Table bases and the two stored words that actually matter:

| Image | Table | Vector word at `0x2F8` | Main range | Main word |
|---|---|---|---|---|
| 818.3 non-ZCP | `0x3DA0C` | `ADECA4C0` | `[0x300, 0x3FCE4)` | `4527D066` at `0x3FCE4` |
| 817.3 ZCP | `0x3DF34` | `854B0007` | `[0x300, 0x40318)` | `E5A6292C` at `0x40318` |
| 820.3 non-M | `0x3DA4C` | `F577908C` | `[0x300, 0x3FAC4)` | `AC29FDE9` at `0x3FAC4` |

The start-up routine on the ZCP (and the non-M) is the same function with a different pool slot: each image points at **its own** word. A complete ZCP image is therefore self-consistent under ZCP start-up, just as 818.3 is under 818.3 start-up.

If the table is malformed, `0x15CAC` calls `fault_set(0x10004)` and returns. It does not hang and it does not enter a “refuse to run” stub.

## The hardware path

`0x15BDC` feeds a hardware unit in the Watchdog/CRC block:

| Address | Role |
|---|---|
| `0xEF8400` | control (the feeders write `1` to enable, `0` to disable) |
| `0xEF8404` | residue, read raw |

No polynomial, width or seed register is written by these functions, or by any other `0xEF84xx` literal in the 818.3 image. The polynomial is fixed in hardware (or was set by a write this image does not contain). Neighbouring addresses in `0xEF82xx`–`0xEF85xx` are used for clock / watchdog-style traffic and are not part of this feed.

Two helpers walk the range:

- `0x154C0` enables the unit, does `ldm r5–r15` (eleven 32-bit big-endian words), and advances the pointer by 44.
- `0x154E4` consumes the 0–10 word remainder with `ld.w`, then reads and clears `0xEF8404`.

`0x15BDC` splits a range into a multiple of eleven words plus a tail; the split is only a word-count modulo 11. It does not drop or repeat words, and the instruction fetches of the loops are not mixed into the residue. The unit is a bus-snoop signature register over the data words those loads issue.

## The algorithm

The residue is a **32-bit left-shift MISR** (a one-shift-per-word signature register), not a byte-wise CRC-32 and not a 16-bit sum.

- Polynomial: **x^32 + x^22 + x^2 + x + 1**, encoded as the tap word **`0x00400007`**.
- Seed: **0**. Final xor: **0**.
- Input: raw 32-bit **big-endian** words on `[start, end)`.
- Output: the value in `0xEF8404`, used **raw** (not inverted, not bit-reversed, not byte-swapped).

In software:

```
s = 0
for each big-endian 32-bit word in [start, end):
    if s bit 31 is set:
        s = ((s << 1) xor 0x00400007) & 0xFFFFFFFF
    else:
        s = (s << 1) & 0xFFFFFFFF
    s ^= word
# s must equal the 32-bit word stored at end
```

`tools/check_word_verify.py` reproduces all six stored words (vector + main on each of the three images). Public M·CORE / MMC2114 manuals do not document this `0xEF8400` unit. The closest published cousin is Freescale AN2192’s right-shift MISC with low taps at 0, 1, 2 and 22; this hardware is the left-shift counterpart of those taps, with a proper degree-32 polynomial.

## Ruled-out methods

The following were tested against all six stored words and **did not match**:

- MSS54 / MSS54HP-style CRC-16/ARC (`0x8005`, reflected, init 0), including 16 KB segment packing into 32 bits.
- Siemens MS43-style CRC-16/CCITT (`0x1021`) and CRC-32, with common inits, inversion and reflection.
- Bosch ME7 / MS45-style 16- and 32-bit word sums, two’s complement, `sum || ~sum`, XOR of words, and byte sums.
- zlib CRC-32, CRC-16/ARC over the raw byte stream, AN2192 right-shift MISC, S12-style MISR, and address-add / address-xor mixes.

A full search of 32-bit left- and right-shift MISR recurrences on the data-word stream, including xor-before / xor-after, leaves the polynomial above as the one that fits all six pairs.

No second image check word was found after the stored main word (818.3 has four `00` bytes at `0x3FCE8`, then `FF`). The feed helpers are not used by any path other than `0x15BDC` and the clear in `0x15CAC`. A wider hunt for a completely different summing loop over the cal tail is not closed, but nothing in this start-up / main-loop machinery points to one.

## Mismatch behaviour

On a residue mismatch, `0x15BDC` calls `fault_set(0x10004)`, then sets bit 0 of `0x400E00` (and later bits 6 and 7), writes `0xFF` to `0x400DF4` and `0xFE` to `0x400DF8`, and **advances to the next range**. It does not reset, spin, or skip the rest of the walk. A bad table in `0x15CAC` is the same fault and a normal return.

So a mismatch is a **fault, not a halt**. Whether the main range is always finished on a normal boot is still open: the only 32-bit pointer to `0x15BDC` in 818.3 is a `jsri` at `0x18026` in the normal-mode main loop (`0x17FC2`). The reduced test-mode loop at `0x1804A` does not call it. One invocation fully compares the vector range and only a prefix of the main range (the walk is a time-sliced state machine; on 818.3 on the order of 32 slices would cover the main range). Whether some later path re-enters `0x15BDC` and finishes the main word has not been pinned.

## Linearity (check-neutral pairs)

A MISR with seed 0 is linear over GF(2): the residue of a xor-difference of two images is the xor of the residues, and the seed cancels. Consequently two halfword changes in the main range can cancel in the stored word even though each change alone would move it.

On 818.3, for example, writing dataset-10 **A = 1833** at `0x3E8A4` together with **B = 2461** at `0x3E8C0` leaves the stored main word `4527D066` unchanged; either change by itself does not (both alone produce `759E665E`). That pair is a property of the residue, not a substitute for the ZCP column: `v_ch` becomes 120.15 km/h rather than the ZCP’s 119.04, and A still enters the yaw reference as wheelbase (see `e46_m3_dataset10_vs_zcp.md`). An in-memory computation of the main residue after a single halfword change — for instance dataset-10 B `1565 → 2400` at `0x3E8C0` — yields `A3981C2C` at `0x3FCE4`; the vector word is unaffected. Those figures are residues, not a procedure for programming the module.

## Open questions

- [ ] Whether `0x15BDC` is entered often enough on a normal boot to finish the main range.
- [ ] The precise init edge that calls `0x15CAC` (a pointer sits at `0x16E28`; the function itself is the validator above).
- [ ] Whether any other, still-unidentified checksum covers the calibration tail.
- [ ] The exact semantics of the `0xEF8400` control word, beyond enable / disable as the feeders use it.

## Method

The range table, the `0xEF8400` / `0xEF8404` traffic, the 11-word + tail feed, and the mismatch path were read from the 818.3 listing; the ZCP and non-M tables were checked against the same structure. The polynomial was identified by modelling that feed and searching the 32-bit MISR family against the six known (range, word) pairs, including difference equations that cancel the seed. `tools/check_word_verify.py` reproduces the six stored words from the raw dumps and does not write them.
