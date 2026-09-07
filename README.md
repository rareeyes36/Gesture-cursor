# Ring Cursor 0.2-probe

An Android overlay cursor driven by a Manridy R6 smart ring over BLE.

Target device from the 2026-09-07 nRF Connect capture:

```
Name         R6
MAC          D8:36:01:08:0C:8A
Manufacturer "Manridy nc."
Model        "BLE 1.0"
HW / FW / SW 2.00 / 3.00 / 4.00
PnP ID       02-3A-09-05-0A-02-00   (USB-IF, VID 0x093A PixArt, PID 0x050A)
```

---

## RESULT (2026-09-07): the boot-mouse hypothesis is dead

The probe ran on hardware, twice, and the answer is **no**.

```
write 0x2A4E status=0
ProtocolMode reads back as 0x01     <- write ignored
cccd 0x2A33 status=13               <- boot mouse subscribe REJECTED
cccd 0x2A22 status=13               <- boot keyboard subscribe REJECTED
cccd 0xFEA1 status=0                <- vendor OK
cccd 0xFEC8 status=0                <- vendor OK
cccd f000efe3 status=0              <- vendor OK
cccd 0x2A19 status=0                <- battery OK
```

Run once bonded and once unbonded (scan path, no HID host present). **Identical
result both times**, so the system HID host was never the cause. On the same
connection, milliseconds apart, every non-HID CCCD write succeeds and both HID
boot-report CCCD writes fail with status 13 (`GATT_INVALID_ATTRIBUTE_LENGTH`).

The R6 declares HOGP and exposes 0x2A4E / 0x2A33 / 0x2A22 in its GATT table, but
Protocol Mode is pinned to Report Mode and the boot CCCDs are non-functional
stubs. This is a cosmetic HID implementation. Nothing in Android is stopping us.

Two things were confirmed along the way:

- The AOSP blocklist is real, in our own process:
  `read ReportMap threw SecurityException: ... android.permission.BLUETOOTH_PRIVILEGED`
- FEA2 is the step goal: `01-40-1F-00` -> field 0x01, u16le = 8000.

The vendor channels stream fine (FEA1, FEC8, efe3, battery), so **the remaining
route is the FEE7 vendor protocol, not anything HID-shaped.** Note also that the
ring takes back the fast connection interval after ~10s
(`interval=99 latency=4`), which will fight any pointer built on this link.

## What this build is actually for

This is a **probe**, not a finished product. It exists to answer one question on
real hardware:

> Can a normal, unprivileged Android app read mouse deltas out of a BLE HID
> device by switching it into Boot Protocol Mode?

### The reasoning

AOSP blocks apps from touching HID-over-GATT. `GattService.java` enforces
`BLUETOOTH_PRIVILEGED` on four characteristic UUIDs:

```
0x2A4A  HID Information
0x2A4B  Report Map
0x2A4C  HID Control Point
0x2A4D  Report
```

That is anti-keylogger protection and it is not going away. A sideloaded app
cannot hold `BLUETOOTH_PRIVILEGED`.

But the scan log shows that these read **cleanly**, with no exception:

```
0x2A4E  Protocol Mode          -> 0x01
0x2A22  Boot Keyboard Input    -> 0x00
0x2A32  Boot Keyboard Output   -> 0x00
0x2A33  Boot Mouse Input       -> 00-00-00-00
```

and `setCharacteristicNotification` succeeded on `0x2A22` and `0x2A33` while
throwing on all five `0x2A4D` instances.

Boot reports were never added to the blocklist. So:

```
write 0x00 -> 0x2A4E     (Boot Protocol Mode; write-without-response)
subscribe  -> 0x2A33     (Boot Mouse Input Report)
```

If the firmware honours it, you get `[buttons, dx, dy, wheel]` in your own
process with no root and no privileged permission.

---

## Build

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

No Gradle wrapper jar is checked in. Either open the folder in Android Studio
(it will offer to generate one) or run `gradle wrapper` once with a local
Gradle 9.7+. CI regenerates it automatically.

Toolchain: AGP 9.4.0, Kotlin 2.4.20, Gradle 9.7.1, compileSdk/targetSdk 37,
minSdk 26, JDK 17 bytecode built on JDK 21.

## Run

1. Launch the app, press **Grant BT**.
2. Press **Enable service**, turn on "Ring Cursor" in accessibility settings.
3. Watch the log.

**Do not pair the ring in Bluetooth settings while testing.** If it bonds as an
HID device, the system HID host becomes a second GATT client and will set
Protocol Mode back to `0x01`, fighting this app for control. Connect as a plain
GATT client only.

---

## Reading the result

The `ProbeState` in the status bar is the answer:

| State | Meaning | Next step |
|---|---|---|
| `BOOT_MOUSE_LIVE` | It worked. Non-zero deltas arriving on 0x2A33. | Tune `PointerEngine`, build features. |
| `BOOT_MOUSE_SILENT` | Mode set, subscribed, nothing ever arrived in 25 s. | Firmware declares boot mode but stubs it. Fall back to pairing as a system HID device and decorating the native cursor, or go to a rooted `/dev/input` read. |
| `BOOT_MODE_REFUSED` | Protocol Mode would not move off `0x01`. | Confirmed on R6 firmware 3.00. The firmware pins Report mode. |
| `BOOT_SUBSCRIBE_REFUSED` | The CCCD write on 0x2A33 was rejected (status 13). | Confirmed on R6 firmware 3.00. Boot reports are stubs. |
| `FAILED` | Never connected. | Bluetooth off, permissions missing, or ring asleep. Charge it and retry. |

The log also records the blocklist failing on `0x2A4B`, on purpose, so you have
first-party evidence rather than my assertion.

---

## Architecture notes

**Everything lives in the AccessibilityService.** The alternative — a foreground
service for BLE plus an accessibility service for clicking, joined by a binder —
was rejected. Same process, no benefit, an extra IPC layer, and an extra
notification. The accessibility toggle is already the natural on/off switch.

**The overlay is `TYPE_ACCESSIBILITY_OVERLAY` created from the service's own
context.** Two consequences: no `SYSTEM_ALERT_WINDOW` grant is needed (there is
deliberately none in the manifest), and the window is *trusted*, so it is exempt
from the Android 12+ behaviour where overlays cause the system to silently drop
touches underneath them. Building the cursor from a plain service context would
break clicking in a way that is very hard to debug.

**All GATT operations go through `GattQueue`.** Android allows exactly one
outstanding GATT operation at a time and silently drops the rest. This is the
most common cause of BLE code that works on one phone and not another.

**`canPerformGestures="true"`** in `accessibility_service_config.xml` is
mandatory for `dispatchGesture`. Without it every dispatch silently returns
false.

**Connection interval.** The ring advertises 125 ms with slave latency 4 — up to
625 ms between report opportunities, which is unusable for a pointer.
`requestConnectionPriority(CONNECTION_PRIORITY_HIGH)` asks for ~11–15 ms. The
peripheral is free to refuse; if the cursor feels stepped, that is why.

---

## Protocol notes so far

### 0xFEE7 — vendor health service

Same service/characteristic layout as the HPlus family documented by
Gadgetbridge, different command dialect (their current-stats frame is `0x33`,
this ring's stream is `0x07`).

| Char | Props | Notes |
|---|---|---|
| FEC7 | W | command in |
| FEC8 | I R | response out |
| FEC9 | R | returns `D8-36-01-08-0C-8A` — the ring's own MAC. Confirmed. |
| FEA1 | N R | `07-10-00-00-09-00-00-00-00-00` every 5 s, static while unworn |
| FEA2 | I R W | `01-40-1F-00` — field 0x01, LE u16 = 8000. **Hypothesis: daily step goal.** Change it in the vendor app and re-read to confirm. |

### f000efe0-0451-4000-0000-00000000b000 — TI-base vendor service

`0451` is Texas Instruments' vendor ID, so the radio is probably a TI part and
PixArt is the sensor, not the SoC. Note the UUID is *not* the canonical TI base;
the vendor shuffled the trailing groups. Use it exactly as the device reports it.

Notify characteristic `f000efe3` emits:

```
0F 06 14 08 01 08 0B 1C 10 00 00 00 01 00 01 03 66 00 00 00
      |___ timestamp ___| |_ val _| |idx|
```

- bytes 6–7: minute, second. Byte 6 tracked wall-clock minutes exactly
  (`0B → 0C → 0D` = 11, 12, 13) during the capture. **The ring's RTC is
  unsynced** — its seconds ran ~26 s behind the phone and bytes 2–5 are static.
- bytes 8–11: LE u32, value 16 (once 11)
- bytes 12–13: LE u16 record index, +1 per minute
- byte 16: `0x66` = 102, constant

Once per minute it also emits a pair:

```
F0 12 04 55 1D 55 00 00 55 ...
0F 85 04 55 08 55 00 00 55 ...
```

`0x55` is the classic fill pattern — sensor slots reporting no data, consistent
with an unworn ring. **Wear it for five minutes and re-capture** to see whether
these populate.

---

## Open questions

1. ~~Does boot mouse mode work?~~ **Answered: no.** See RESULT above.
2. Is the pointing sensor optical/capacitive (thumb) or an IMU (hand)? The GATT
   tree cannot tell us. Pair as HID, watch the cursor, wave your hand, then try
   your thumb.
3. What do the five `0x2A4D` Report characteristics carry? The Report Map is
   blocked on Android but readable from Linux/BlueZ.
4. Is FEA2 really the step goal?
