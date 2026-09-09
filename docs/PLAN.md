# Build plan: from Ring Cursor probe to a general Bluetooth macro layer

Working title: **Overwrite**.

One sentence: *any connected Bluetooth device becomes a programmable remote for
the phone — its buttons, keys, wheels and sensors are discovered automatically,
listed live, and bound to phone actions, gestures, cursor control or MIDI.*

This document is the scope and sequencing decision, written before any feature
code. It is deliberately opinionated about what is **out** of scope, because the
hard part of this project is not the macro engine — it is knowing which inputs
Android will actually let an unprivileged app see and swallow.

---

## 1. What already exists

The probe in this repo is further along than a greenfield start. Do not restart.

| Capability | Where | State |
|---|---|---|
| Global key capture, pre-dispatch | `RingCursorService.onKeyEvent` | Works. `canRequestFilterKeyEvents` + `flagRequestFilterKeyEvents` already set. |
| Global motion capture (mouse/touchpad/joystick/trackball) | `RingCursorService.onMotionEvent` | Works on API 34+. Sources requested at runtime via `serviceInfo.motionEventSources`. |
| Input-device arrival/removal watch | `InputManager.InputDeviceListener` | Works. |
| Overlay cursor, trusted, no `SYSTEM_ALERT_WINDOW` | `CursorView`, `addCursor()` | Works. |
| Pointer integration, acceleration, clamping | `PointerEngine` | Works. |
| Tap / scroll / long-press / drag synthesis | `dispatchTap`, `dispatchScroll`, `DragSession` | Works. |
| Global actions (Back, Home) | `performGlobalAction` | Works, hardcoded. |
| Serialised GATT client with reconnect | `GattQueue`, `RingBle` | Works. |
| Vendor opcode prober | `RingBle.startProbe` | Works. |

Roughly 60% of the plumbing for v1 is done. What is missing is everything that
makes it *general*: a device/control registry, a binding model, persistence, a
sequence recogniser, and a UI that is not a debug log.

---

## 2. The capability matrix — what Android actually permits

This is the section that sets scope. Every scoping decision below follows from
it. Nothing here needs root; nothing here needs a privileged permission.

### 2.1 Input routes

**Route A — system HID stack.** The device bonds as a keyboard, mouse, gamepad
or trackball. Android decodes it and dispatches into the normal input pipeline.

- **Keys are observable and consumable.** `onKeyEvent` returns `Boolean`;
  returning `true` swallows the key system-wide. *This is the "bypass" the
  scope describes, and it already compiles in this repo.*
- **Motion is observable but NOT consumable.** The override is
  `onMotionEvent(event: MotionEvent)` returning `Unit` — there is no way to
  reject the event. The system pointer keeps moving underneath you.
- Motion capture requires **API 34+**. Keys work from API 26.

**Route B — app-owned GATT client.** The device is a BLE peripheral that is not
bonded as HID. The app subscribes to notifications directly.

- Full access to every non-blocklisted characteristic.
- Bypass is free: the phone's input stack never saw the data at all.
- The AOSP blocklist rejects `0x2A4A / 2A4B / 2A4C / 2A4D` without
  `BLUETOOTH_PRIVILEGED`. Boot reports `0x2A22 / 2A33` are not blocklisted, but
  the R6 firmware stubs them — see the README.

**Route C — classic Bluetooth RFCOMM/SPP.** `BluetoothSocket` on a service
UUID. Full byte access. This is how DIY controllers (ESP32, HC-05) and many
presenter clickers speak. Cheap to add, high payoff for hobby hardware.

**Route D — A2DP/AVRCP headphones.** *This is the case in the original scope
that behaves worst, and it is worth knowing before building.*

- Play/pause/next/previous arrive through `MediaSession`. Register a session,
  become the active one, and its `Callback` receives them. Effectively
  capturable.
- **Volume up/down on a headset is usually AVRCP absolute volume.** It moves
  the stream volume inside the audio framework and never becomes a `KeyEvent`.
  You cannot intercept it. The best available behaviour is *detect and undo*:
  watch `AudioManager` volume changes, fire the macro, restore the previous
  level. That can produce a brief audible step.
- So the headphone example from the scope is the **weakest** device class, not
  the easiest. Design the UI to say so honestly rather than pretending.

**Route E — MIDI.** `MidiManager.openBluetoothDevice()` reads BLE-MIDI
peripherals natively. Separately, a `MidiDeviceService` lets this app *publish*
itself as a virtual MIDI source that other apps on the phone consume. That is
what makes "any device is a MIDI operator" literally true, and it is the
cheapest high-value feature in the whole plan.

### 2.2 Capture confidence — surface this in the UI

Do not present a uniform "bypass" switch. Show a per-control badge:

| Badge | Meaning | Applies to |
|---|---|---|
| **Full** | Observed and swallowed. The phone never acts on it. | HID keys, GATT, RFCOMM, MIDI |
| **Observe only** | Seen, but the phone acts on it too. | HID motion (mouse, joystick axes) |
| **Undo** | Not interceptable; the effect is detected and reversed. | AVRCP headset volume |
| **Blocked** | Consumed by the OS before accessibility. | Power, and Home on some OEM builds |

### 2.3 Output actions

| Action | Mechanism | Cost |
|---|---|---|
| Back, Home, Recents, Notifications, Quick Settings, Power dialog | `performGlobalAction` | none |
| Screenshot | `GLOBAL_ACTION_TAKE_SCREENSHOT` | API 30+ |
| Lock screen | `GLOBAL_ACTION_LOCK_SCREEN` | API 28+ |
| Tap, swipe, drag, pinch, multi-touch | `dispatchGesture` | `canPerformGestures` (set) |
| Volume set/step | `AudioManager.adjustStreamVolume` | none |
| Flashlight | `CameraManager.setTorchMode` | none |
| Media transport | `AudioManager.dispatchMediaKeyEvent` | none |
| Launch app, intent, shortcut | `startActivity` | none |
| Brightness | `Settings.System.SCREEN_BRIGHTNESS` | `WRITE_SETTINGS` user grant |
| Type text into the focused field | `ACTION_SET_TEXT` | needs `canRetrieveWindowContent=true` |
| Screen off | `DevicePolicyManager.lockNow()` | device-admin receiver |
| **Power off** | — | **not possible.** Drop it from scope. |

Two entries in the original scope do not survive: **power off** is unavailable
to any non-system app, and the **power button** cannot be rebound because
`PhoneWindowManager` consumes it before accessibility sees it. Lock-screen is
the closest substitute and it is available.

`canRetrieveWindowContent` is currently `false`. Flipping it unlocks per-app
profiles and text injection, but widens the privacy surface and changes the
consent text the user sees. Treat it as a deliberate v2 decision, not a default.

---

## 3. Core architecture

Keep the existing decision: **everything in the AccessibilityService, one
process.** The service toggle stays the master switch. Add a thin persistence
layer and a normal activity UI on top.

```
  transports                 core                         sinks
  ──────────                 ────                         ─────
  HidKeySource ─┐                                    ┌─ GlobalActionSink
  HidMotionSrc ─┤     ┌──────────────┐               ├─ GestureSink (dispatchGesture)
  GattSource   ─┼──▶  │  Observation │──▶ registry   ├─ PointerSink (overlay cursor)
  RfcommSource ─┤     │    Layer     │               ├─ SystemSink (volume/torch/media)
  MediaSource  ─┤     └──────┬───────┘               ├─ IntentSink (launch app)
  MidiSource   ─┘            │                       └─ MidiSink (virtual MIDI out)
                             ▼
                     ┌──────────────┐    ┌─────────────┐    ┌──────────────┐
                     │  Recogniser  │──▶ │   Binding   │──▶ │ Macro Runner │
                     │  (trie+time) │    │   Resolver  │    │  (steps)     │
                     └──────────────┘    └─────────────┘    └──────────────┘
```

### 3.1 The canonical signal

Every transport normalises to one envelope. This is the single most important
type in the codebase — get it right first.

```kotlin
data class InputSignal(
    val transport: Transport,      // HID_KEY, HID_MOTION, GATT, RFCOMM, MEDIA, MIDI
    val deviceKey: String,         // BT MAC where available, else "vendor:product:name"
    val controlKey: String,        // "key:KEYCODE_VOLUME_UP" | "axis:REL_X" | "gatt:FEA1@4..5"
    val kind: Kind,                // BUTTON, AXIS, DELTA, TRIGGER
    val value: Float,              // 0/1 for buttons; normalised or raw for axes
    val raw: ByteArray? = null,
    val atNanos: Long,
    val synthetic: Boolean = false // set on anything this app itself caused
)
```

`deviceKey + controlKey` is the primary key of the discovered-controls table.
First sighting inserts a row. That is exactly the "table updates when you press
the button" behaviour in the scope, and it falls out of the design for free.

**The `synthetic` flag is not optional.** A macro that adjusts volume will
otherwise observe its own volume change and re-trigger itself. Tag every
self-caused event and drop it, plus a short suppression window after each sink
call.

### 3.2 Devices and controls

```kotlin
data class ManagedDevice(
    val deviceKey: String,
    val displayName: String,
    val transport: Transport,
    val bypass: Boolean,            // master swallow switch for this device
    val lastSeenMs: Long,
    val connected: Boolean
)

data class DiscoveredControl(
    val deviceKey: String,
    val controlKey: String,
    val label: String,              // user-editable: "left shoulder"
    val kind: Kind,
    val confidence: Capture,        // FULL | OBSERVE_ONLY | UNDO | BLOCKED
    val hits: Int,
    val lastValue: Float,
    val range: ClosedFloatingPointRange<Float>?,   // learned for axes
    val enabled: Boolean
)
```

The "indicator light" in the scope is `lastSeenMs`/`hits` rendered as a dot that
pulses on activity and dims on idle. Cheap, and it is the thing that makes the
learn flow feel alive.

---

## 4. The recogniser — sequences, timing, and the delay problem

The scope identifies the real problem correctly: to recognise a double-press you
must withhold the single-press action until the multi-press window closes. Here
is the refinement that matters.

### 4.1 Only pay the delay where ambiguity exists

Store bindings in a **prefix trie** keyed by chord sequences. On reaching a node
that is a valid terminal, ask: *does any binding extend this prefix?*

- **No** → fire immediately. Zero added latency.
- **Yes** → arm a timer for the gap and hold.

A button bound to exactly one thing is therefore instant. Only genuinely
overloaded controls feel a pause. This is how Karabiner-Elements, tmux prefix
keys and vim's `timeoutlen` behave, and it removes the sluggishness that a
uniform delay would give every binding in the app.

### 4.2 Timing tiers

| Constant | Default | Meaning |
|---|---|---|
| `chordWindow` | 50 ms | Presses inside this window are one simultaneous chord (A+B). |
| `tapGap` | 260 ms | Max gap between taps within one burst (double-click). |
| `stepGap` | 700 ms | Max gap between bursts in a sequence (the "double-double"). |
| `holdThreshold` | 500 ms | A press outliving this becomes a hold terminal. |

Invariant: **`stepGap >= 2.5 x tapGap`.** If the two windows are close, a burst
boundary is ambiguous and recognition becomes a coin flip. Enforce this in the
settings UI rather than letting the user configure them independently into an
unusable state.

Hold must be decided by a timer armed on the DOWN edge, not by inspecting the UP
edge — otherwise a hold does not fire until release, which feels broken.

All four should be per-device overridable. A ring with a stiff button needs
different numbers than a mechanical keyboard.

### 4.3 Bypass and the dead-end problem

The recogniser must decide whether to swallow a key on the DOWN edge, *before*
it knows whether the sequence will complete. Two possible policies:

1. **Swallow-and-replay.** Consume anything that could start a binding; if the
   sequence dead-ends, re-emit the native action. Replay fidelity is poor:
   volume can be replayed exactly, but arbitrary keys cannot be re-injected into
   another app without `INJECT_EVENTS`, which is a signature permission.
2. **Per-control capture.** Only swallow controls the user has explicitly
   enabled. Unbound keys are never captured and keep working normally.

**Recommend policy 2 as the default**, with replay offered only for the small
set of actions that can be reproduced faithfully (volume, media transport). It
is predictable, it degrades safely, and it avoids a class of bug where the
phone's keyboard silently stops working because a macro engine ate a keystroke.

### 4.4 Analog controls

An axis needs more than a binding — it needs a transfer function.

```
raw ─▶ [range calibration] ─▶ [dead zone] ─▶ [curve] ─▶ [mode] ─▶ scalar target
```

- **Range calibration**: learn observed min/max, auto-widening, with a "recalibrate" reset.
- **Dead zone**: centre band suppressed, essential for drifting IMUs and sticks.
- **Curve**: linear / expo / logarithmic.
- **Mode**: absolute (value maps to target level) or relative (value integrates as a rate).

Scalar targets for v1: volume level, scroll velocity, cursor velocity, MIDI CC.
`PointerEngine` already implements the relative-integration case — generalise it
rather than writing a second one.

---

## 5. Scope: three releases

### v1 — "Overwrite". The minimum that is genuinely useful.

The goal is a build you use daily on one keyboard and one mouse or ring.

1. **Registry + live control table.** Devices list with connection state.
   Controls auto-inserted on first sighting, with the activity indicator,
   editable label, capture-confidence badge, and last value.
2. **Bypass**, per device and per control, honouring the confidence matrix.
3. **Binding editor.** One control → one action. Ship eight actions: Back, Home,
   Recents, Screenshot, Lock, Volume ±, Flashlight, Launch app.
4. **Recogniser** with the trie plus conditional delay. Supports tap-count,
   hold, and two-step sequences.
5. **One analog binding path**: axis → scalar target, with dead zone and range
   calibration.
6. **Persistence and profile export/import** as JSON.

Explicitly out of v1: MIDI, per-app profiles, RFCOMM, macro composer, text
injection, byte-field discovery.

### v2 — "Operator".

7. **MIDI out** via `MidiDeviceService`, plus **BLE-MIDI in**. This is the
   feature that delivers the scope's "any device could be a MIDI operator" and
   it needs no accessibility trickery at all — consider pulling it forward if
   the music use case matters more than the phone-control one.
8. **Macro composer**: ordered steps with delays, repeats, and simple
   conditionals; the "programmable sequence of clicks with pauses" from the scope.
9. **Pointer-capture spike** for true mouse bypass (`requestPointerCapture()` on
   a focusable accessibility overlay, delivering raw deltas via
   `onCapturedPointerEvent`). Timebox it. The fallback — accept the system
   cursor and layer gestures on top — is acceptable.
10. **Per-app profiles**, gated on flipping `canRetrieveWindowContent`.

### v3 — "Bench".

11. **Automatic byte-field discovery** for opaque GATT streams. Run variance
    analysis on a rolling window of notification payloads: static bytes are
    constants, monotonic bytes are counters or timestamps, two-valued bytes are
    buttons, bounded-jitter bytes are sensors. Present candidates and let the
    user confirm by wiggling the control. **This turns the manual protocol
    reversing already documented in the README into a product feature, and it is
    the highest-leverage idea specific to this codebase.**
12. **RFCOMM/SPP transport** for DIY hardware.
13. **Touch macro record and replay.**

---

## 6. Sequencing — build order that de-risks early

Each step ends at something runnable on hardware.

| # | Step | Ends when |
|---|---|---|
| 0 | Rename module `ringcursor` → `overwrite`; split `RingCursorService` into service + transports. | Existing probe behaviour unchanged after refactor. |
| 1 | `InputSignal` envelope + `ObservationLayer`. Route `onKeyEvent` and `onMotionEvent` through it. | Debug log shows normalised signals from both routes. |
| 2 | In-memory registry + a real device/control list UI replacing the log screen. | Pressing a new key on a BT keyboard makes a row appear and its dot pulse. |
| 3 | Persistence (Room). | Rows and labels survive a restart. |
| 4 | Sinks: global actions, volume, torch, launch. | A hardcoded binding fires an action. |
| 5 | Binding model + editor UI, single-tap terminals only. | Any control can be bound to any of the eight actions. |
| 6 | Bypass with per-control capture. | A bound key stops reaching other apps; unbound keys still work. |
| 7 | Recogniser: trie, tap-count, hold, conditional delay. | Single/double/hold on one button do three different things, and the single is instant when unambiguous. |
| 8 | Two-step sequences. | The scope's "double, pause, double" fires a distinct action. |
| 9 | Analog path: calibration, dead zone, curve, scalar targets. | A mouse wheel or ring axis drives volume smoothly. |
| 10 | Profile export/import. | v1 ships. |

Steps 0–3 are the ones worth doing carefully; everything after is additive. The
refactor in step 0 is not optional — `RingCursorService` is 605 lines with BLE,
overlay, gestures and input handling interleaved, and every later step touches it.

---

## 7. Risks and things that will bite

- **Play Store distribution.** Accessibility-service apps need an
  `IS_ACCESSIBILITY_TOOL` declaration and a policy justification. This one
  genuinely qualifies, and `isAccessibilityTool="true"` is already set, but
  review is a real gate. Sideloading is the pragmatic path; do not let store
  policy shape the architecture.
- **API 34 floor for motion.** `minSdk` is 26 and keys work there, but every
  mouse/joystick feature needs 34+. Either raise the floor or keep degrading
  gracefully, as the service already does — but make the UI say *why* a mouse
  shows no axes on an older phone.
- **OEM battery management kills accessibility services.** Ship a
  `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` prompt and an in-app health check that
  detects "service enabled in settings but not running".
- **Self-triggering.** Covered by the `synthetic` flag; if it is skipped, the
  first volume macro will loop.
- **Connection interval on BLE.** Documented in the README: the R6 reclaims a
  99 ms interval with latency 4 after ~10 s. Any BLE-driven pointer will feel
  stepped. `CONNECTION_PRIORITY_HIGH` is a request the peripheral may refuse.
- **Two cursors.** Until the pointer-capture spike lands, a bound mouse shows
  both the system pointer and the overlay. Decide deliberately whether to hide
  the overlay when a real mouse is present.
- **Locking yourself out.** A bypass config that swallows every key on the only
  connected keyboard is recoverable only by toggling the accessibility service.
  Ship a fixed panic combo that the recogniser never swallows, and never let
  bypass survive a service restart without an explicit re-arm.

---

## 8. Open decisions

These change the work materially and are the user's call. Recommended defaults
in bold; the plan above assumes them.

1. **Primary use case: phone control, or MIDI/music control?** If MIDI, promote
   v2 item 7 into v1 — it is easier and needs no accessibility permissions.
   *Default assumed: **phone control**.*
2. **`minSdk` 26 with degraded motion, or 34 for a uniform feature set?**
   *Default assumed: **stay at 26**, degrade.*
3. **Flip `canRetrieveWindowContent` to true?** Unlocks per-app profiles and
   text injection; widens privacy surface and changes the consent prompt.
   *Default assumed: **no in v1**, revisit in v2.*
4. **Bypass policy: per-control capture, or swallow-and-replay?**
   *Default assumed: **per-control capture**.*
5. **UI toolkit: keep XML views, or move to Compose?** The current UI is three
   XML screens' worth of debug output; the v1 UI is list-heavy and stateful.
   *Default assumed: **Compose**, since almost none of the existing UI survives.*
6. **Keep the R6-specific vendor probe as a feature, or archive it?**
   *Default assumed: **keep**, behind a "developer" screen — it is the seed of
   the v3 field-discovery bench.*
