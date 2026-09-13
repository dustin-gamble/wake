# WAKE

**Rowing instruments, 21 games and a coastal flight, driven by a WaterRower S4 monitor over USB.**

📥 **[Download the APK and read the guide →](https://dustin-gamble.github.io/wake/)**

Your rowing machine already measures everything. WAKE reads the WaterRower S4 monitor over USB
from the Android tablet on the machine, and turns it into analog instruments that behave like
water, a shelf of games driven by your real power and cadence, and a flight down the California
coast on a 3D globe.

Built against a WaterRower in an **Ergatta** fit-out, but it talks to the S4, not to Ergatta:
no root, no system changes, and Ergatta itself is untouched and still the fallback.

---

## What's in it

- **Three analog gauges** — speed, power, rate — with needles that carry momentum.
- **A water paddle** spinning at the measured speed, coasting down on its own when you stop.
- **21 games**: pace boats, ghost races against your own best, a zombie that closes when you ease
  off, a side-scrolling runner, boss fights, a canyon chase, an isometric city that builds one
  block per stroke. Each shows the same vitals strip, so the numbers are never hidden by the game.
- **Coast Flight** — a CesiumJS globe with satellite imagery and terrain. Power above 60 W is
  lift, boat speed is airspeed, the route flies itself, and your progress down the ~900 km coast
  persists across sessions.
- **An optional laptop dashboard** (`node server/server.js`) for live capture, an event stream and
  protocol decoding. The app finds it on the Wi-Fi by itself; everything works without it.

## The interesting part: the decay *is* the instrument

A real boat decelerates between strokes. Rowers call it the **run** of the boat, and reading it is
how you judge your own stroke — a strong drive shows a high peak and a slow decay.

The S4 does not decay its readings: it holds the last value until the next one. Showing that value
flat is the single mistake that cost this project the most rework. Instead the paddle is modelled
directly, with drag torque quadratic in speed:

```
I dω/dt = T − kω²        →  free coast:  ω(t) = ω₀ / (1 + (k/I)ω₀t)
```

Knowing *when* to coast turned out to be the hard part. Three candidate triggers were tested
against thousands of captured samples before one survived:

| Trigger | Result |
|---|---|
| `watts == 0` | **Rejected** — zero in 27% of samples taken *while actively rowing*, runs up to 9.7 s |
| Speed value unchanged | **Rejected** — it is an average and holds its last figure for ~10 s |
| Flywheel pulses stopped | **Rejected** — the paddle turns for many seconds after you let go |
| **Stroke counter gone quiet** | **Used** — exact, one tick per stroke, allowance scaled to your rating |

## Other things the capture settled

- **Stroke rate is address `1A9` with no multiplier.** An inherited ×2 produced 74 spm and 294
  readings above 55 spm, beyond human cadence.
- **A "missed stroke" wasn't one.** 106 consecutive counter ticks, every delta exactly 1. What
  looked like a dropped stroke was `1A9` dipping to 19–21 in ~9% of samples, so the *displayed*
  rate is now a trimmed mean over 15 s. The raw value still drives every decision.
- **The tablet's WebView is Chrome 70**, so Coast Flight picks its CesiumJS version at runtime:
  current CesiumJS uses optional chaining, which is a *syntax* error there — the globe just goes
  black with no message. 1.95 is the newest release that parses.
- **`055` is not a distance.** It is the low byte of a metres counter; real distance is `057`.

## Hardware

```
Device : WR USB sensor, 04D8:000A (Microchip CDC-ACM)
Tablet : Android 9, Ergatta rower tablet (minSdk 23)
Decoded: 088 watts · 14A speed · 1A9 rate · 140 strokes
         057 distance · 1A0 heart rate · 1E1 clock
```

Any WaterRower S4 exposing USB serial should work, as should other Android tablets. Reports from
different hardware are very welcome — open an issue.

## Build it yourself

```sh
# Android SDK required; the app has one dependency (usb-serial-for-android).
gradle assembleDebug

# Optionally bake in your laptop's dashboard URL, otherwise it is discovered over UDP:
gradle assembleDebug -PdiagnosticServerUrl=http://<your-laptop-ip>:8787
```

The dashboard needs no dependencies at all:

```sh
node server/server.js      # http://localhost:8787
```

For Coast Flight's satellite maps, supply a [Cesium Ion](https://cesium.com/ion/) token — either
`CESIUM_ION_TOKEN` in the environment or `server/cesium.local.json` as `{"ionToken":"..."}`. It is
served to the app once over the LAN and cached there; **no token is ever committed or compiled
into the APK.** Without one the flight falls back to OpenStreetMap.

Protocol and physics run without Android, so they are tested with plain `javac`:

```sh
javac -d /tmp/t app/src/main/java/com/codex/waterrowerdiagnostic/S4Protocol.java \
  tools/prototest/com/codex/waterrowerdiagnostic/S4ProtocolTest.java
java -cp /tmp/t com.codex.waterrowerdiagnostic.S4ProtocolTest
```

## Layout

```
app/          Android app: protocol, instruments, games, Coast Flight WebView
server/       Optional laptop dashboard: HTTP, SSE, capture, UDP discovery
tools/        Capture analysis (Python) and offline tests (plain javac)
docs/         The GitHub Pages site and the published APK
CLAUDE.md     Engineering notes: what was measured, what was ruled out, and why
```

## Safety

- No root, no system modification, no change to boot behaviour.
- Ergatta is not disabled, replaced or altered — it stays the fallback.
- Manually launched. Exit and back both confirm first.
- No account, no analytics, no ads. Records stay in the tablet's own storage.
- Nothing leaves your network except CesiumJS and map tiles on the flight screen.

## Licence

MIT. Not affiliated with WaterRower or Ergatta.

If it made a row go quicker, you can [sponsor the project](https://github.com/sponsors/dustin-gamble).
