// Create the uninitialized RAM + MMIO memory blocks the .0pa image doesn't carry,
// so RAM/peripheral references resolve (no more "Address not in memory", and the
// Symbol Tree can navigate to RAM labels). Ranges from ScanUnmapped.java +
// ARCHITECTURE.md: RAM 0x400000, ports/serial/QSPI/CAN/GPIO 0xD5-0xDE, INTC 0xE1,
// timers/CAN2/reset 0xF5-0xFF, MPU/flash-cache 0xFFF. Idempotent (skips existing).
//@category MK60
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.mem.*;

public class CreateMemBlocks extends GhidraScript {
    @Override
    public void run() throws Exception {
        //           name            start         length      volatile(MMIO)
        make("RAM",            0x400000L,  0x24000L,  false);  // 0x400000-0x423FFF (max ref 0x421FC0)
        make("MMIO_ports_D",   0xD50000L,  0x91000L,  true);   // 0xD50000-0xDE0FFF
        make("MMIO_INTC_E",    0xE00000L,  0x11000L,  true);   // 0xE00000-0xE10FFF
        make("MMIO_timers_F",  0xF50000L,  0xA1000L,  true);   // 0xF50000-0xFF0FFF
        make("MMIO_MPU_FFF",   0xFFF00000L,0x85000L,  true);   // 0xFFF00000-0xFFF84FFF
    }

    private void make(String name, long start, long len, boolean vol) {
        Address a = toAddr(start);
        Memory mem = currentProgram.getMemory();
        if (mem.getBlock(a) != null) { println("  skip " + name + " (already mapped at " + a + ")"); return; }
        try {
            MemoryBlock b = mem.createUninitializedBlock(name, a, len, false);
            b.setRead(true); b.setWrite(true); b.setExecute(false); b.setVolatile(vol);
            println(String.format("  created %-14s %s .. %s  (%d KiB)%s",
                    name, a, a.add(len - 1), len / 1024, vol ? " volatile" : ""));
        } catch (Exception e) {
            println("  FAILED " + name + ": " + e.getMessage());
        }
    }
}
