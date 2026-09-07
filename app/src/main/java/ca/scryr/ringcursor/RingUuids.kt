package ca.scryr.ringcursor

import java.util.UUID

/**
 * Every UUID here is copied verbatim from the nRF Connect capture of
 * R6 (D8:36:01:08:0C:8A) on 2026-09-07. Do not "correct" the vendor ones.
 *
 * In particular the TI-base service is written on this device as
 *   f000efe0-0451-4000-0000-00000000b000
 * which is NOT the canonical TI base (f000xxxx-0451-4000-b000-000000000000).
 * The vendor shuffled the trailing groups. Use what the device actually reports.
 */
object RingUuids {

    private fun sig(short: String): UUID =
        UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

    // --- Standard ---
    val GENERIC_ACCESS = sig("1800")
    val DEVICE_NAME = sig("2a00")

    val DEVICE_INFO = sig("180a")
    val MANUFACTURER = sig("2a29")
    val MODEL_NUMBER = sig("2a24")
    val FIRMWARE_REV = sig("2a26")
    val PNP_ID = sig("2a50")

    val BATTERY_SERVICE = sig("180f")
    val BATTERY_LEVEL = sig("2a19")

    val CCCD = sig("2902")

    // --- HID over GATT (0x1812) ---
    val HID_SERVICE = sig("1812")

    /**
     * Protocol Mode. [R WNR] on this device, and NOT on the AOSP blocklist.
     * 0x00 = Boot Protocol Mode, 0x01 = Report Protocol Mode.
     * The device reports 0x01 at connect; we write 0x00 to flip it.
     */
    val PROTOCOL_MODE = sig("2a4e")

    /**
     * Boot Mouse Input Report. [N R], NOT blocklisted.
     * This is the whole reason this app can exist without root:
     * AOSP's GattService blocks 2A4A / 2A4B / 2A4C / 2A4D only.
     * Boot reports were never added to that list.
     */
    val BOOT_MOUSE_INPUT = sig("2a33")

    /** Boot Keyboard Input Report. [N R], also not blocklisted. */
    val BOOT_KEYBOARD_INPUT = sig("2a22")

    /** Blocked on Android. Listed only so we can log the failure explicitly. */
    val REPORT = sig("2a4d")
    val REPORT_MAP = sig("2a4b")
    val HID_INFORMATION = sig("2a4a")

    // --- Vendor health/telemetry service (Chinese band family) ---
    val VENDOR_FEE7 = sig("fee7")
    val FEC7_WRITE = sig("fec7")          // [W]   command in
    val FEC8_INDICATE = sig("fec8")       // [I R] response out
    val FEC9_DEVICE_ID = sig("fec9")      // [R]   returns the ring's own MAC
    val FEA1_NOTIFY = sig("fea1")         // [N R] periodic telemetry frame
    val FEA2_CONTROL = sig("fea2")        // [I R W] 01-40-1F-00 -> likely step goal 8000

    // --- TI-base vendor service ---
    val TI_SERVICE: UUID = UUID.fromString("f000efe0-0451-4000-0000-00000000b000")
    val TI_WRITE: UUID = UUID.fromString("f000efe1-0451-4000-0000-00000000b000")
    val TI_NOTIFY: UUID = UUID.fromString("f000efe3-0451-4000-0000-00000000b000")

    // --- Misc vendor service seen in the scan, contents all zero ---
    val FF00_SERVICE = sig("ff00")
    val FF01_CHAR = sig("ff01")

    /** Known MAC of the unit that was captured. Used as a scan hint only. */
    const val KNOWN_MAC = "D8:36:01:08:0C:8A"
    const val ADVERTISED_NAME = "R6"
}
