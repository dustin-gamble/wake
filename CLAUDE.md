# CLAUDE.md

This file gives agent-facing context for continuing WAKE, the Ergatta / WaterRower
diagnostic and rowing app.

## Read this first if you are picking up the games work

The instrument side is done and stable. Games are specified in [GAME_IDEAS.md](GAME_IDEAS.md)
and **seventeen games, a Records screen and the browser-based Coast Flight are built**
(3.5.0). All were confirmed working on
the tablet. The user's verdict on the first twelve was that they "suck" - they are
dashboards with a score. The three built in response - **Zombie Run, Row Runner, Boss
Fight** - have something at stake in the next ten seconds and are the template for anything
further. Read the note at the top of GAME_IDEAS.md before designing a new one.

Four things that will cost you a day each if you skip them:

1. **There is no Android emulator in this environment.** You can compile but not see the
   UI. Ship a build and ask the user what it looks like. Do not assume a layout works.
2. **Never hold a displayed value flat while waiting for a fresh reading.** The S4 holds
   stale values instead of decaying them. See "the decay IS the instrument" below - this
   single mistake caused more rework than everything else combined.
3. **Verify your edit actually landed before shipping.** A patch script that asserts out
   mid-way leaves the source unchanged, and the build still succeeds with a bumped version
   number. Grep for your change in the source, then build.
4. **Do not add to the poll loop casually.** Every address costs cycle time for all the
   others, and anything that blocks the serial reader breaks the link.
5. **The tablet's WebView is Chrome 70.** Anything in a WebView - Coast Flight - has to be
   written for a 2018 engine: no optional chaining, no `inset`, no flex `gap`. A modern desktop
   browser hides every one of these and the tablet shows a blank screen with no error. See the
   Coast Flight section.

The app is named **WAKE** (launcher label and the title on screen). The package id stays
`com.codex.waterrowerdiagnostic` - renaming it would orphan the installed app's saved
settings and USB permission grant, so leave it alone.

## Project

Path:

```text
<repo>
```

User-facing APK output:

```text
<repo>/docs/downloads/ergatta-row-diagnostic-debug.apk
```

This is a local-only Android diagnostic app plus laptop dashboard. Do not deploy it publicly unless the user explicitly asks.

## Coast Flight runs inside the app (3.6.0)

A Cesium globe that flies a bird down the California coast, driven by the live rower data. Up to
3.5.2 it ran in Chrome on the laptop's dashboard; it is now a WebView **inside WAKE**, because
the browser round trip sent data tablet -> laptop -> browser to draw something the tablet already
knew, and it stranded the user in a browser on a tablet with no app switcher.

- Page: `app/src/main/assets/coastflight/fly.html` + `fly.js`, loaded from
  `file:///android_asset/`. The laptop's `/fly` copy is kept for testing on a desktop browser,
  but nothing in the app points at it any more.
- Screen: `CoastFlightGame extends GameView`, so it gets the coasted `BoatSpeedModel`, the
  smoothed distance, the rowing clock and the vitals strip like every other game. The WebView is
  stacked **on top** of the GameView by `gameScreen(title, game, controls, overlay)`; the
  GameView underneath keeps its frame loop, and that loop is what pushes data into the page.
- Data crosses at ~12Hz via `evaluateJavascript` calling `window.wakeFeed({w,r,s,t,m,k})`.
  `s` is the **coasted** speed and `t` the rowing clock: the page must never re-derive either.
- The page talks back through `WakeNative` (`CoastFlightGame.Bridge`): map mode, trip
  persistence, `ready()`, `home()`, and `report()`. The bridge class is **public and static** on
  purpose - `addJavascriptInterface` reaches it by reflection, which is unreliable on anonymous
  or private classes.
- Streaming to the laptop is irrelevant to it now, and the laptop can be off.
- **This is the one thing that leaves the local network**: the CesiumJS library and the map
  tiles. The user asked for this explicitly. Do not extend it to anything else.
- `file://` pages need `setAllowUniversalAccessFromFileURLs(true)` or CesiumJS cannot fetch its
  own workers and the globe stays black. Nothing remote is executed except CesiumJS, over TLS.

### The tablet's WebView is Chrome 70, and that decides the CesiumJS version

Measured, not assumed: the tablet reports
`Mozilla/5.0 (Linux; Android 9; Ergatta Rower) ... Chrome/70.0.3538.110`.

**CesiumJS started emitting optional chaining (`?.`) in 1.99, which is a syntax error before
Chrome 80.** The bundle then fails to parse and you get a silent blank globe. Scanning the
published builds for constructs Chrome 70 lacks gives a hard cliff:

| CesiumJS | `?.` | `??` | verdict on Chrome 70 |
|---|---|---|---|
| 1.86.1 | 0 | 0 | parses |
| **1.95** | **0** | **0** | **parses - newest that does; workers clean too** |
| 1.99 | 6 | 1 | fails |
| 1.126 | 51 | 17 | fails |

So `fly.html` reads the Chrome major version and loads **1.95 below Chrome 80**, the current
release above it. Keep new API calls inside the `LEGACY` branches: 1.95 takes provider instances
in the `Viewer` constructor (`imageryProvider`, `createWorldImagery()`), while current Cesium
uses `baseLayer` and the `*Async` factories.

Runtime gaps are shimmed at the top of `fly.html` (`globalThis`, `Object.fromEntries`,
`String.replaceAll`, `Promise.allSettled`, `queueMicrotask`). Syntax cannot be shimmed - that is
why the version is chosen by engine.

**The same trap applies to CSS.** Chrome 70 does not have the `inset` shorthand (87), `gap` on a
flex container (84), or unprefixed `backdrop-filter` (76). A desktop browser hides all three.

Consequence the user should know: **Google Photorealistic 3D needs a current CesiumJS, so it is
unavailable on this tablet.** The tiers offered there are Bing satellite with real terrain, or
OSM. Updating Android System WebView from the Play Store would unlock it - the page picks the
newer library automatically - but that is the user's call, not something to do for them.

### Maps: three tiers, best first

| Mode | Source | Needs |
|---|---|---|
| `photo3d` | Google Photorealistic 3D Tiles (Ion asset 2275207) | Ion token **and** Chrome >= 80 |
| `satellite` | Cesium World Imagery (Bing aerial) + Cesium World Terrain | Ion token |
| `osm` | OpenStreetMap on a smooth ellipsoid | nothing |

Tapping the **Map** card cycles modes and reloads, because the three need different viewer
setups. Choice persists via the bridge as `flight.map`. In `photo3d` the globe is hidden
(`scene.globe.show = false`) and route polylines stop clamping to ground, as there is no globe to
clamp to.

**Attribution is a licensing requirement**, not decoration: Google and Bing both require a
visible credit, so the Cesium credit container is *restyled* small and translucent, never
hidden. An earlier version hid it with `display:none` - do not do that again.

### Keeping the Ion token out of GitHub

Three layers, because one is not enough:

1. **It is never in the repo.** `server/cesium.local.json` is gitignored and the server injects
   the token at request time. Verified across the whole history, not just the working tree:
   every blob of every commit scanned, zero hits.
2. **A pre-commit hook refuses to commit one.** `tools/scan-secrets.py` reads the *staged blobs*
   (what actually gets pushed, unlike a working-tree grep) and blocks JWTs, private keys, cloud
   keys, LAN addresses and home paths. Enable it in a fresh clone with:

   ```sh
   git config core.hooksPath tools/git-hooks
   ```

   **Test any change to that scanner against a real-shaped token.** The first version required 20
   characters in a JWT header segment; a real header is 17, so it passed a token straight through
   and only a live commit test caught it.
3. **GitHub secret scanning and push protection are enabled** on the repository. Generic
   (non-provider) pattern scanning could not be turned on - it needs Advanced Security - which is
   precisely the category a Cesium token falls into, so layer 2 is the one doing the work here.

### The Ion token

**No token lives in source, and none is compiled into the APK** (verified with `strings` on the
dex). The laptop reads it from `CESIUM_ION_TOKEN` or `server/cesium.local.json` (gitignored) and
serves it at `GET /api/cesium-token`; the app fetches it once on laptop discovery and on entering
the flight, then caches it in app prefs as `cesium-ion-token`, so the maps keep working with the
laptop off. Only its length is ever logged. If it arrives while the globe is already up without
one, the page is reloaded to promote OSM to satellite.

Never inline the token in a page, in this repo or a neighbouring one: injecting it at request
time keeps a bearer credential out of source control entirely.

- Flight model: power above 60 W climbs, below it sinks; boat speed sets airspeed (~250 km/h at
  a solid pace, deliberately not faster - an earlier 550 km/h blurred the coast). The sun is
  pinned to a California afternoon because the real clock often put the coast in darkness.
- Trip progress persists as `flight.along`, so the ~900 km route is a journey across many
  sessions. There is a restart link in the footer.
- Page failures are reported to the laptop as `coast-flight` events, **forced even with streaming
  off**, including the engine and CesiumJS version at boot and the first 12 console errors. There
  is no emulator for this tablet, so a failure that does not reach the laptop cannot be seen.

## Streaming to the laptop is OFF by default (2.8.0)

Serialising `rowing-status` at ~8Hz plus `raw-bytes` costs CPU the game graphics need on
this tablet, so it is off unless the user turns it on: a toggle on the home screen, and a
"Stream to laptop" checkbox in the diagnostics drawer. The choice is remembered
(`stream-to-laptop` in the app prefs). `publishRowingStatus` and `publishRawBytes` return
before building any JSON when it is off.

Lifecycle events still send regardless - they are `force`d and rare: `app-started`,
`laptop-discovered`, exit, and since 2.9.1 the connection events too (`serial-opened`,
`serial-open-failed`, `connection-closed`, `serial-read-stopped`, `s4-port-reopen`,
permission results). So auto-connect can be verified from the laptop with streaming off. **If you need a capture to debug
something, ask the user to switch streaming on first.** Discovery still runs regardless.

## Hard Guardrails

- Do not root the tablet.
- Do not modify Android system software.
- Do not disable, remove, or alter the Ergatta app.
- Preserve normal Ergatta behavior as the safe fallback.
- Keep the diagnostic app manually launched.
- Keep network traffic local unless the user explicitly asks otherwise.

## Current Build

Latest source version target:

```text
3.7.3-debug
```

Build and publish in one go (this is the loop used all session):

```sh
ANDROID_HOME=.../work/android-sdk ANDROID_SDK_ROOT=.../work/android-sdk \
  .../work/tools/gradle-8.13/bin/gradle assembleDebug \
  -PdiagnosticServerUrl=http://<laptop-ip>:8787
cp app/build/outputs/apk/debug/app-debug.apk server/public/downloads/ergatta-row-diagnostic-debug.apk
```

The user installs from the dashboard's Install APK link, so publishing to
`server/public/downloads/` is what actually delivers a build. Bump `versionCode` and
`versionName` in `app/build.gradle` every time or the user cannot tell builds apart.

Package:

```text
com.codex.waterrowerdiagnostic.debug
```

Compatibility:

```text
minSdk 23
targetSdk 28
Android 9 tested on Ergatta tablet
```

Current laptop URL baked into local builds:

```text
http://<laptop-ip>:8787
```

## What Works

Link and protocol:

- Auto-connect on launch; no button press. UDP discovery finds the laptop on port `8788`.
- `CdcAcmSerialDriver` over USB, stable: 0 write failures since 0.7.0.
- Seven addresses decode continuously (see Verified On Hardware).
- Events stream to the laptop dashboard; every event is appended to `events.jsonl`.

App UI (`WAKE`):

- Three analog gauges - speed, power, rate - with needles that carry momentum.
- Water paddle spinning at the measured speed, coasting down when the drive stops.
- Live plot: boat speed (filled) over power, 60s at 5Hz, traced from the coasted needle.
- Metric tiles: time, distance, pace, rate, power, strokes.
- Diagnostics as a **slide-in drawer** from the right, full height and scrollable.
- Home screen with Quick Row, twelve game cards showing personal bests, last-session
  summary, Records screen, and the streaming toggle.
- Screen stays on (`FLAG_KEEP_SCREEN_ON`, added in 2.9.0 - it was never set before).
- Exit and back both confirm first; back from a game or the gauges returns home.

Dashboard (laptop):

- Live metrics, water wheel, S4 memory map table, filterable event stream.
- Capture export and session archive.
- Flags frozen data: heartbeats arriving while no decoded value changes.

Observed rower device:

```text
Device: WR USB sensor
VID:PID: 04D8:000A
Manufacturer: Microchip Technology Inc.
Serial driver: CdcAcmSerialDriver
Tablet IP: <tablet-ip>
```

## Resolved: the wedged USB write path

Through 0.6.x every session died the same way - writes began failing with
`rc=-1` about 2.5s after each open, at rest and under load, on every build.

**0.7.0 runs clean: 1 open, 0 write failures, 0 reopens across 537 events.**

Which change fixed it is not established. The plausible candidates are the 250ms
inter-command gap (down from 40ms) and dropping the four addresses this unit refuses, but
the rower was also physically reconnected during the same period, so the evidence does not
separate them. If the stall returns, those are the first two knobs.

### It came back on 2026-09-13, and nothing in software fixed it

After a clean 10km session on 3.7.3, the link began failing again mid-morning: `rc=-1` at
`after=0msec`, first failure 2-3s after every open, cooldown, reopen, repeat to `s4-reopen-limit`.

**Eliminated, in this order, all of them by experiment:**

- *The build I had just shipped.* A version table showed 9 clean opens across 1.5.0-3.7.3 and 5
  failing opens all on 3.7.4, which looked decisive. It was not: all five were inside one 90-second
  bad spell. **Reverting 3.7.4 to 3.7.3's exact code and reinstalling changed nothing.** Correlating
  a fault with a version needs opens spread across time, not a cluster.
- *Fast relaunch after exit.* Historic clean opens exist at gaps of 0s, 10s, 11s, 12s and 15s.
- *Cable replug.* 16 faults in the 40s after `usb-attached`.
- *Monitor power-cycle.* 11 faults after.
- *Full tablet reboot.* 21 faults after - the worst of the three.
- *Pulse traffic saturating the link.* The user reported "dead while rowing, alive when stopped",
  which fits it perfectly. The data refutes it: in 5-second buckets, **13% of spinning buckets
  contained a write failure against 35% of still ones**. Failures are *more* common at idle. A
  plausible mechanism that matches a symptom is still worth testing before acting on.

**Untested at time of writing: the USB cable and the OTG adapter.** An instant `rc=-1` with no
timeout, at idle, surviving every reset, is what a marginal physical connection looks like. Note
that the 0.6.x episode above also ended around a physical reconnection.

**3.9.0 stops guessing and asks the USB stack.** On a write failure, `probeWritePath()` checks
four things at the moment it happens and publishes an `s4-write-probe` event with a `verdict`:

| verdict | what it means | what to do |
|---|---|---|
| `driver-state` | a raw `bulkTransfer` works where the driver's `write()` did not | fault is inside usb-serial-for-android's port state; write directly or reinit the driver object |
| `endpoint-halt` | `CLEAR_FEATURE(ENDPOINT_HALT)` then a retry works | the bulk OUT endpoint stalls; the app now clears it in place on every failure instead of reopening |
| `connection-dead` | the connection's file descriptor is invalid | the `UsbDeviceConnection` is gone; reopening is correct |
| `unrecovered` | raw transfer and halt-clear both fail | none of the above; read `rawBulkTransferRc`, `clearHaltRc`, `interfaceClaimed` |

A stalled endpoint is the strongest prior: it fails instantly (the `after=0msec`), persists across
app restarts, and only clears on an explicit request or a full device reset. Clearing a halt is a
standard request every USB device must accept - the Linux kernel does exactly this on a stalled
pipe - and changes nothing on the monitor. The probe is capped at 12 reports a session and one
per 10s, because it holds the I/O lock for up to three 250ms timeouts.

**What the probe found (3.9.0, six reports, all identical):**

```
fileDescriptor 43 | interfaceClaimed true | rawBulkTransferRc -1 | clearHaltRc -1 | afterClearHaltRc -1
```

- Not the serial library: a raw `bulkTransfer` on the same endpoint fails identically.
- Not a stalled endpoint: clearing the halt changes nothing.
- Not a dead connection: the descriptor is valid and the interface is claimed.
- **`clearHaltRc -1` is the key.** That request goes over the control pipe, endpoint 0 - a
  completely separate channel from the bulk data pipe. Failing at the same instant means **every
  host-to-device transfer is blocked at once**, while device-to-host reads carry on.
- **The blackout is short.** The next write succeeded 0.1-0.7s after each probe.

The *cause* of the blackout is still unknown. But the multi-second dead gauges were not it - they
were **the app's reaction**. A refused write triggered a 1.5s cooldown; ten triggered a port
teardown with a 4s settle, which cannot help when the descriptor is valid, and which also killed
the reader, fragmenting the pulse stream. Four of those and it gave up (`s4-reopen-limit`).

**3.9.1 rides through it instead:** the same command is retried every 100ms for up to a second,
longer than any blackout measured, with no cooldown, and the port is reopened only when the
connection's file descriptor is actually invalid. If the gauges still die, the blackout has grown
past a second - check `s4-write-probe` timings before changing anything else.

### The 15-stroke control (3.9.1) - the rower is not the problem

The user rowed exactly 15 strokes and paused. Scored with `control.py` (scratchpad), which counts
two things separately: what the monitor counted, and what the app saw live.

```
18:05:07     0 -> 61   startup read
18:05:32    61 -> 72   +11 in ONE update, after 25.5 s with no counter update
18:05:44    72 -> 75   +3
18:06:17    75 -> 76   +1
```

**61 -> 76 is exactly 15: the S4 counted every stroke.** The app received the stroke counter three
times across the whole control, one update carrying 11 strokes after 25 seconds of silence - the
"captures a couple, then stops for a bit" the user described, measured.

**Memory reads are almost entirely starved.** Counting a reply only when a genuinely new one lands,
3.9.1 got about 20 successful reads across all seven addresses in 279s; the stroke counter got 2.
A first version of that count reported stroke rate answered 125 times - it was one stale
`IDS1A900` held across 166 samples. **Count replies on change, never per sample.**

**The pulse detector caught 5 of the 15.** Effort tracks rowing clearly (0.8-0.99 while pulling,
0 between), but the pulse stream itself dropped out for 7.0-7.5s at a time, about every 11s.

**All five of those dropouts contained a probe** - and the probe force-claimed the interface, which
can disrupt in-flight transfers. That is suggestive, not proven: it was checked by looking for
dropouts after the 12-probe cap ran out, and found none, but **no rowing happened after the cap
either**, so the test could not tell. 3.9.2 removes the force-claim (it answered true all 18 times
anyway) and caps the probe at 3. A second 15-stroke control on 3.9.2 is the clean A/B.

### Both directions die about 2.7 s after the port opens (3.9.2)

Measured with `packetsSeen`, a true counter of every packet the reader framed - immune to the stale
`lastPacket` trap above:

| build | packets framed | last packet | then silent for |
|---|---|---|---|
| 3.7.3 (healthy morning) | 22,642 -> 44,803 | at session end | 0 s |
| 3.9.1 | 1 -> 1,379 | 125 s after open | 311 s |
| 3.9.2 | 1 -> 6 | **2.9 s after open** | 328 s, reader still running |

Reads die at the same moment writes do - first write failure lands a median **2.68 s** after open
across 48 opens. So this was never "writes blocked while reads carry on". The whole link goes dead.

**Refuted on the way here, each by its own data:**
- *The monitor only streams pulses while it hears the host.* Pulse flow started within 1.5 s of a
  successful write 0 of 6 times; 0 of 11 successful writes were followed by pulses.
- *The probe's forced claim was restoring the link.* Flow started within 1.5 s of a claim 3 of 6
  times - no fingerprint. Library `open()` force-claims anyway (`CdcAcmSerialPort.openInterface`,
  `claimInterface(iface, true)`), so every open was already a steal.
- *`PING` means the monitor went idle for lack of a host.* The healthy morning session sent 30.

**WAKE registers no USB device filter** in its manifest; it requests the rower at runtime. A kiosk
rowing app almost certainly registers one, which gives it first claim at boot and on attach.

**3.9.3 asks the ownership question directly.** The probe calls `claimInterface(iface, false)` -
non-forcing - which succeeds if this process still owns the interface and fails if another has
taken it. It takes nothing back. `verdict: interface-taken` would confirm another claimant; a
`true` refutes that theory too. No rowing needed: the failure arrives ~3 s after opening WAKE.

### ROOT CAUSE, measured (3.9.3): something else takes the rower's interface ~2.7 s after open

Two probes, both the same answer:

```
18:23:08   2.7 s after open   interfaceStillOurs false   reader silent 167 ms
18:23:18  12.7 s after open   interfaceStillOurs false   reader silent 10,185 ms
verdict: interface-taken
```

`claimInterface(iface, false)` is non-forcing: it succeeds if this process still owns the
interface and fails if anything else does. It failed. **About 2.7 s after WAKE opens the port,
another owner takes the interface, and both directions die together** - the 2.68 s median across
48 opens is that handover, not a timeout, not a stall, not the monitor.

This is the first theory today confirmed by direct measurement rather than inference. Everything
before it - build, relaunch timing, pulse load, cable, monitor state, saved prefs, endpoint halt,
driver state, a monitor watchdog - was refuted by its own data.

**Who the owner is, stated with its uncertainty.** Android's `claimInterface(iface, true)` can
detach a *kernel driver* but cannot take an interface from another app's usbfs handle. On 3.9.1
those forcing claims returned true all 18 times and pulse data flowed in bursts afterwards. So the
owner is most likely the **kernel serial driver re-binding** to the rower - plausibly because
Ergatta's software opens the device through it - rather than another app holding it directly. Not
proven which. Either way it sits on Ergatta's side, which fits Ergatta reading the rower perfectly
while WAKE cannot.

**Why it worked this morning** is still open: WAKE ran 18 minutes clean from 08:07, and the
handover began at 08:26, immediately after the user exited WAKE back to Ergatta. Something on
Ergatta's side started claiming the device then, and it re-arms on boot.

**This is not fixable by retrying.** A USB interface has one owner. The options all touch the hard
guardrail about preserving Ergatta, so they are the user's decision, not an implementation detail:
- *Take it back while WAKE is in front* (force-claim when `interface-taken`). Makes WAKE work;
  whatever Ergatta runs in the background loses the rower until WAKE exits.
- *Find what started the claiming at 08:26 and avoid triggering it.*
- *Leave Ergatta's claim alone* and accept that WAKE cannot run alongside it.

### The chosen fix: take the rower back while WAKE is on screen, give it back when not (3.10.0)

The user chose this over hunting the trigger or leaving Ergatta's claim alone. Two halves, and the
second one is the one that must never regress:

**Take it back.** On every refused write, after the ownership probe has recorded who held the
interface, `reclaimInterfaces()` force-claims the **control interface, then the data interface** on
the existing connection, and the write is retried. Both, because this device is CDC-ACM:

| iface | class | endpoints | role |
|---|---|---|---|
| 0 | 2 communication, ACM | interrupt IN `0x81` | the kernel `cdc_acm` driver binds here |
| 1 | 10 cdc-data | bulk OUT `0x02`, bulk IN `0x82` | commands, replies and pulses |

A forcing claim detaches a kernel driver; it cannot take an interface from another app's handle.
Rate-limited to one reclaim per 250 ms so a tug-of-war cannot spin the CPU. Reported as
`s4-interface-reclaimed` (first three, then one per 5 s) with `controlClaimed` / `dataClaimed`.

**Give it back.** WAKE used to override only `onCreate` and `onDestroy`, so going Home left the
connection open. With reclaiming added that would have kept stealing from Ergatta out of sight.
`onStop` now publishes `s4-interface-released` and closes the connection the instant WAKE is not
visible (skipped for configuration changes); the library's `closeInt` releases both interfaces.
`onStart` retakes the rower only if `onStop` released it, so the normal launch - where auto-connect
already opens the port - is not a double open.

**Why this sits inside the Ergatta guardrail:** Ergatta is not disabled, removed or modified, and
its behaviour with WAKE closed or backgrounded is exactly as before. It only loses the rower while
WAKE is the app on screen. **If you change this, keep the release in `onStop`.** Without it WAKE
would deprive Ergatta of the rower whenever it is merely in the background.

### It works: 3.10.0, first minute on the machine

```
                        3.9.3             3.10.0
packets framed          1 -> 6            1 -> 2,460
last packet             2.5 s after open  60.0 s - still flowing
write failures          12                0
stroke counter updates  0                 14
reclaims                -                 +2.6 s  +5.5 s  +8.4 s  +14.3 s, every one control and data true
```

Pulses ran at 40/s - the same rate as the healthy morning session. The other owner re-takes the
interface about every 3 s; WAKE takes it back inside the one-second write-retry window, so no
request is actually lost and none is logged as a failure. That also confirms the owner is one a
forcing claim can displace - consistent with the kernel driver re-binding.

**Strokes arrive individually again - resolved.** A continuous piece on 3.10.0, scored with
`control.py`: the monitor counted 14 and the app saw all 14 live, **missed 0**. Every stroke came
as its own +1 update, a median 1.8 s apart at 29-30 spm - no lumps, where the broken 3.9.1 control
delivered 11 strokes in one update after 25 s of silence. Max packet age 26 ms against the healthy
morning's 23 ms; watts changed once per stroke. The pulse detector caught 13 of 14, up from 5 of 15,
because the pulse stream is now continuous. The earlier "+5 in 20 s" worry was the monitor's 20 s
summary window, not the link.

**Still to verify on the machine:** the release half - that `s4-interface-released` fires on leaving
WAKE, that WAKE then touches the rower zero times, and that Ergatta reads it normally. That is the
guardrail; do not ship anything that weakens it.

**If you are picking this up:** do not start by changing code. The link either works or it does
not, and the capture tells you which within 45 seconds - count `s4-write-failed` since the last
`app-started` and look at median `lastPacketAgeMs`. Healthy is 0 faults and under ~400ms.

### What was measured while it was broken

Time from `serial-opened` to first write failure, 36 sessions across five builds:

```text
open 22:49:09 -> 2.8s   pulses=0     (first session, flywheel still)
open 22:54:54 -> 2.8s   pulses=0
open 22:59:09 -> 2.6s
open 23:03:11 -> 2.5s
```

### Ruled out, each by experiment

- *A timed-out write poisons the pipe.* Failures began at `after=0msec` with no
  preceding timeout.
- *Concurrent read/write on one UsbDeviceConnection.* 0.4.0 serialized every read and
  write behind one lock and cut the read timeout to 120ms. No change.
- *Re-sending a refused address.* 0.4.1 retired on the first `ERROR`; still wedged.
- *Another app stealing the interface.* 0.5.1 called `claimInterface(force)` and retried:
  `reclaimed=true, writeSucceededAfterReclaim=false`, 7 of 7. The claim was never lost,
  so force-stopping the Ergatta app would not have helped.
- *Pulse traffic from rowing.* The first session failed at 2.8s with `pulses=0`.
- *Cumulative damage from the reopen loop.* Session one failed at the same 2.5s, before
  any reopen logic existed.
- *Wrong baud rate.* `04D8:000A` is a Microchip native-USB CDC device with no physical
  UART, so the setting never reaches hardware; the link yields clean ASCII at 19200.

Distinguish the two failure modes, they are not the same thing:

- `rc=-1` on write: USB transport. Back off and retry; never blacklist the command.
- `ERROR` packet from the monitor: that address/width really is unreadable.

## Protocol Correlation

The monitor talks asynchronously: `Pxx` pulses, `PING`, `SS`/`SE` and `_WR_` arrive
whenever the flywheel moves, interleaved with `IDx` replies. Never treat "a packet
arrived" as "my command was answered" - through 0.2.5 a stroke pulse would satisfy the
writer's wait, and a later `ERROR` would then be blamed on whichever command happened to
be current. Correlation now matches the address echoed in the `IDx` reply.

## Verified On Hardware (1.5.1, 2026-09-11)

Addresses that answer on this unit:

```text
088 watts          14A avgSpeedCmS (m/s x100)   140 strokes (exact counter)
1A9 strokeRate     057 displayDistance (metres) 1A0 heartRate (always 0)
1E1 clockSec
```

Refused at every width, do not re-add without evidence:

```text
055  low byte only of a metres counter, not a distance value
1A6  not in the official memory map at all
148  "total distance per second" - refused by this firmware
142  stroke time - refused by this firmware
1E2 1E3  display minutes and hours
```

Official map: [tbressler/waterrower-core MemoryLocation.java](https://github.com/tbressler/waterrower-core/blob/master/src/main/java/de/tbressler/waterrower/model/MemoryLocation.java)

### Decode notes

- **Stroke rate is `1A9` with NO multiplier.** An inherited `x2` produced 74spm and 294
  readings above 55spm, which is beyond human cadence. A test pins this; do not reinstate.
- **Displayed stroke rate is a trimmed mean over 15s** (3.6.1), `Status.strokeRateAverage`.
  1A9 is steadier than it looks - sd 1.6spm across 2700 samples of a held 25spm - but it dips to
  19-21 in ~9% of them, and a needle falling six points reads as a stroke that did not count.
  **It was not a missed stroke:** the same capture has 106 consecutive counter ticks with every
  delta exactly 1, and observed tick intervals of 2-3s against a 2.4s cadence. Nothing is being
  dropped; the raw rate was just noisy. The trimmed mean drops the single highest and lowest
  sample, which removes an isolated dip outright where a plain mean would still sag. **Display
  only** - the coast trigger and the games keep `strokeRate` raw. Pinned by a test.
- What remains is poll quantisation: the counter is read about once a second, so a per-stroke
  reaction lands 2s or 3s after the last one rather than evenly at 2.4s. That jitter is inherent
  at this poll rate and is not worth chasing with a shorter command gap - see the write path.
- **Distance is real** via `057`, since 1.2.0. The earlier integrated estimate under-counted
  badly (350m against a true 1914m) and is now only a fallback.
- **Pace is derived** as `50000 / avgSpeedCmS`, clamped: nothing below 50 cm/s, nothing
  above 10 min/500m. Unclamped it produced 16666s.
- **Elapsed time counts `1E1` rollovers**, as the minute and hour registers are unreadable.
- **Heart rate is dead.** `1A0` answers but is always 0 - no strap paired. Do not design
  around it.

### Poll scheduling

Hot fields are read every cycle, cold fields share one slot per cycle. Hot: `088`, `14A`,
`1A9`, `140`. The stroke counter is hot **because it drives the coast trigger** - polled
cold its change-age reflected poll latency rather than real strokes, and the needle dipped
mid-stroke. If you add a hot field, everything else gets slower; check the cost.

Command gap is user-selectable in diagnostics (60-400ms, default 150ms). Lower is fresher
but historically destabilised the write path.

## Instrument feel, as tuned by the rower on the fixed link (3.11.0)

Four changes, all at the rower's request once the link was working again:

- **Speed bleeds slowly.** Coast drag default lowered a fourth time, 0.12 -> **0.04**, in the
  gauge, the paddle and `BoatSpeedModel` alike. At 3.85 m/s across a 2.4 s stroke gap, 0.12 shed
  53% of the speed and 0.04 sheds 27%. Options now 0.02-0.12, default index 2.
- **Stroke rate timed from strokes and held until the next is due** (the rower's own model).
  `S4Protocol.averagedStrokeRate` averages the last six stroke-counter intervals, so it moves only
  when a stroke lands and by a sixth of the change. Between strokes it holds. Once a stroke is
  later than 1.35x the expected interval it falls as 60 / time-since-last-stroke. Counter resets
  and lumped reads clear the timing; under three intervals it falls back to the 1A9 trimmed mean.
  **Only possible since the ownership fix** - strokes arriving eleven at a time had no interval.
  The rate needle no longer has water drag: a rate is not a flywheel, and with drag the needle sank
  between every stroke even while the figure it was fed held.
- **The paddle wheel shows RPM, and turns at the RPM it shows.** The S4 memory map has no rotation
  register, only m/s. The monitor measures distance by counting paddle turns, so rotations are
  proportional to metres at any pace: `RPM = speed x 60 x rotations-per-metre`.
  **Calibrated at 0.65 per metre** (about 1.5 m per turn, ~150 RPM at 3.85 m/s): the rower counted
  two firm strokes at 10 turns each against 29 m of settled distance, 0.69/m, and 0.61/m if the
  uncounted first stroke was also 10. Settled readings:

  ```
  strokes 126  1279 m  ->  127  1299 m  ->  128  1312 m (+13, 10 turns)  ->  129  1328 m (+16, 10 turns)
  ```

  **A first attempt gave 0.375 and was wrong by 1.7x.** 25 of its 32 m landed after the paddle had
  stopped; at the true rate its 12 turns are ~18 m, so ~13 m was the previous piece's distance not
  yet read. **Distance (`057`) is polled slowly - always let it settle before calibrating**, and
  start from a distance reading taken well after any earlier rowing.
  To re-check: one firm stroke from still, count turns until it stops, wait 30 s, repeat.
- **The plot scrolls smoothly.** It used to jump a column left every 200 ms when a sample landed,
  and allocated a `Path` and `LinearGradient` on every draw. It now slides by the elapsed fraction
  of a measured sample period, redraws every frame while visible, eases its scale, and allocates
  nothing in `onDraw`.

## Tune games to THIS envelope, not to a guess (3.7.0)

Measured from 3221 samples of real rowing on this machine. Every game constant that gates
progress must be placed against these numbers:

| | p10 | median | p90 | max |
|---|---|---|---|---|
| Watts | 93 | **129** | 162 | 205 |
| Boat speed | 3.0 | **3.85 m/s** | 4.06 | 4.19 |
| Stroke rate | 23 | **25** | 26 | 27 |
| Pace /500m | | **128 s** | | 119 s |

Every "it doesn't move / it never lifts off / the bar won't budge" complaint traced to a constant
set without this table:

- **Rocket Launch** asked for 130 W to hover. The median is 129 W, so it sat on the pad forever.
- **Collector**'s three speed bands topped out at 3.4 m/s, below the median, so the boat was
  permanently in the fast lane and changing lane meant nearly stopping - which read as lag.
- **Pace Boat** defaulted to 135 s/500m against a rower who holds 128, so the opponent lost and
  never came back. Not a bug in the chase logic; the opponent was simply slower.
- **Sprint Ladder** started at 120 W with 12% steps, putting rung 4 beyond the measured peak.

Two failures were about *when* a value is sampled rather than how big it is:

- **Mega Pull** read watts inside `onStroke`, which fires when the stroke *counter* increments -
  about a second after the drive, when instantaneous power has collapsed toward zero. It was
  building the tower out of the troughs. Peaks must come from `onStatusChanged`.
- Anything wanting peak power has the same trap. `onStroke` is for counting and for hits, never
  for magnitudes.

**Removed in 3.7.0** on the user's verdict, after all were seen on the machine: Boss Fight
("isn't fun", no readable feedback that you are attacking), Storm ("no good"), Power Zones ("not
working"), Depth Dive ("didn't work"). Their record keys are still formatted in the Records
screen so old results are not orphaned. Seventeen games remain.

## Publishing: use the script, not gradle by hand (3.7.1)

```sh
./tools/publish-apk.sh          # LAPTOP_URL=... to override the baked-in dashboard
```

It builds **two different APKs** and they must not be mixed up:

| | goes to | dashboard URL | screenshot upload |
|---|---|---|---|
| local | `server/public/downloads/` | baked in | **on** (`-PscreenshotUpload=true`) |
| public | `docs/downloads/` (GitHub Pages) | none, discovery only | **off** |

It also writes `server/public/downloads/version.json`, which the dashboard reads to show which
build the Install button is handing out and whether the connected tablet is behind it. Build by
hand and that line goes stale, which is the whole reason the script exists.

## Screenshots from the tablet (3.7.0, gated in 3.7.1)

The tablet is kiosk-locked: no file manager, no app switcher, and Android's own screenshot lands
in a gallery that cannot be opened. So the app photographs itself:

- **Long-press the vitals strip** in any game, or **long-press WAKE on the home screen**, or use
  **Screenshot** in the diagnostics drawer (it closes the drawer first, then waits 450ms).
- The PNG is POSTed to `/api/screenshot` and lands in `server/data/screenshots/<screen>-<time>.png`.
- It draws the *view hierarchy*, so every canvas screen comes out exactly as seen. **Coast Flight
  will come out blank** - it is WebGL on a GPU surface, outside the view draw pass. Use the
  hardware screenshot for that one.
- **Never automatic.** There is no timer and no capture on entering a screen; it fires only on a
  deliberate long-press or button, one image per action.
- **Compiled out of the published build** via `BuildConfig.SCREENSHOT_UPLOAD`. Verified on the
  dex: the public APK has zero `setOnLongClickListener` calls, so no trigger survives, and the
  method body is inside `if (BuildConfig.SCREENSHOT_UPLOAD)` so a false constant empties it.
  Dead lambda bodies do linger in the string pool - javac desugars lambdas before it drops
  constant-false blocks, and a debug APK is not minified - so grepping the dex for
  `X-Shot-Name` is not a useful test. Reachability is.

## Can this tablet take a Bluetooth sensor? (3.7.1)

A `device-capabilities` event is now sent once per session, forced, carrying
`bluetoothLe` / `bluetoothAdapter` / `bluetoothEnabled` plus the screen metrics. It exists to
answer the handle-mounted IMU question from the capture instead of by buying hardware. Reading
the adapter needs only the normal `BLUETOOTH` permission; nothing is scanned, paired or
connected, and that is the extent of the Bluetooth code in the app.

## Steering: the handle tilt sensor (3.7.3)

Hardware: a **WitMotion WT901BLECL** strapped to the rowing handle. Chosen over a cheaper bare
IMU because it runs its own Kalman filter and reports *angles*, not raw acceleration - which
matters more here than in most places, since the handle travels ~1.2m per stroke and a bare
accelerometer cannot separate gravity from that motion. The gyro carries orientation through the
drive; the fusion happens in the sensor.

- `HandleSensor.java` - BLE scan, connect, notify, and frame reassembly. Notifications do not
  respect message boundaries, exactly like the rower's serial link, so frames are buffered to 11
  bytes and checksummed before decoding. Angle packet is `0x55 0x53 <6 bytes> ... <checksum>`,
  little-endian int16, degrees = raw / 32768 * 180.
- **The service and characteristic UUIDs are documented, not verified.** On connect the callback
  logs every service and characteristic it finds to the laptop as a `handle-sensor` event, and
  falls back to the first characteristic that supports notifications. Fix the constants from that
  log on first contact and drop the fallback if it proves unnecessary.
- **Android 9 returns an empty BLE scan without `ACCESS_FINE_LOCATION` - no error, just nothing.**
  It is requested at runtime before the first scan. Do not remove that.
- Games read `steering()` on `GameView`, -1 to +1, and `hasSteering()` to know whether a sensor is
  actually feeding it. No game touches Bluetooth.
- Neutral is captured on the first reading, because the sensor can be strapped on at any angle.
  **Centre Steering** in the diagnostics drawer re-zeroes it after remounting. Full lock at 35
  degrees of roll, 3 degree dead zone.

**Canyon Chase drove lateral position from boat speed** across a 1.0-4.8 m/s band. This machine is
rowed at 3.0-4.2, so the craft sat pinned against the right wall and the only way to steer left was
to stop rowing - which is exactly what "it just runs into the wall" meant. It now steers from the
handle when one is connected, and the speed fallback spans the speeds actually produced.

## Game Architecture

Everything lives in `MainActivity` as swappable screens inside a `FrameLayout`
(`screenHost`): home, instruments, or one game. The diagnostics drawer sits above all of them.

**Contract.** A game extends `GameView` and implements `render(Canvas, dt)`. The activity
calls `onStatus(Status, driving)` on the UI thread whenever a reading lands. Games never
touch the protocol or the serial link, so a broken game cannot break the connection.

**What `GameView` gives you, and why you must use it:**

- `boat` - a `BoatSpeedModel`: the coasted speed, same physics as the gauge. Use
  `boat.value()`, never `status.waterSpeedMps` directly, or the boat on screen will step
  once a second and sit flat between readings.
- `sessionMeters` - dead-reckoned from the coasted speed each frame and pulled toward the
  monitor's real distance as it arrives. Smooth enough to move a boat with.
- `sessionSeconds`, `driving`, `status`, and text helpers (`bold`, `label`, `pace`, `clock`).
- The **vitals strip** (`GaugeStripView`) is added above every game by `gameScreen()`, so a
  game never needs to draw speed/power/pace/rate/distance/strokes or the stopwatch itself.
  Draw only what is specific to the game; the strip covers the numbers.
- `boatSpeed()` - the coasted speed, which is what the strip is fed.
- `activeSeconds` - **the rowing clock.** Starts on the first stroke, pauses after 15s
  without one, resumes on the next. Shown in every game's header by the activity; a game
  should use it for any displayed time. `sessionSeconds` never pauses and is for internal
  timers that must keep running while the user rests (interval rest phases, boss attacks).
- `onStroke(watts)` - fires once per stroke-counter increment. Up to ~1s late (poll rate),
  so good for hits and damage, not for beat timing.
- `start()` / `stop()` lifecycle; `onStart`/`onStop`/`onStatusChanged` hooks.

**Adding a game:**

1. Create `FooGame extends GameView` (or `extends PaceBoatGame` for anything on a river with
   an opponent - override `opponentDistance()`, `opponentLabel()`, `onRaceFinished()`).
2. Add `openFoo()` in `MainActivity` calling `showGame(game, gameScreen(title, game, chips))`.
3. Add a `gridCard(...)` in `buildHome()` with an icon `Kind` (add a glyph in
   `GameIconView` - a few primitives on a 24-unit grid) and a PB key;
   `refreshPersonalBests()` formats by key prefix. The home is a **fixed grid with no
   scrolling** - columns from screen width (6 at >=1000dp), rows from the card count - so
   adding a card past 18 needs a 7th column or a 4th row and everything gets smaller.

**Persistence** is `PersonalBests` over SharedPreferences, on the tablet, so games work with
the laptop off. Ghost recordings are one integer per second, comma-separated, under
`ghost.<metres>`. The Journey's lifetime total is `journey.total`, banked every minute and
on exit; the monitor's own distance counter is the source and the app re-bases if it resets.

**Shared drawing:** `RiverRenderer` (water, boats, wakes, spray) and `Fx` (particles, shake,
glow, vignette, speed lines). Boats are hull paths; there are no assets. The pattern in the
hero games: `c.save(); c.translate(shake.dx, shake.dy); ...world...; c.restore();` then
vignette and speed lines over the top, HUD last so it never shakes.

**The Run's scores are relative to the drag setting** and are for beating yourself only.

## Screen Layout

**Home** (3.2.0): one-line header (name, RECORDS, STREAM toggle, exit), then a fixed grid
of eighteen cards - GAUGES plus seventeen games - each with a drawn icon (58dp), name and
personal best, sized to fill the screen with no scrolling. Columns come from screen width
(6 at >=1280dp, 5 at >=980dp, 4 at >=700dp) - fewer columns means bigger icons, so if the
cards look cramped raise the thresholds rather than shrinking the icon.

**Game screens**: a header row (back, title, any per-game chips), the **vitals strip** (74dp -
raised from 62dp in 3.6.1 to fit a bigger stopwatch, which is the figure the user actually
watches), then the game filling the rest. Last-session summary in the footer.

**Gauges screen**, top to bottom: name and status chip with a small exit icon, connection
banner, three analog gauges (speed / power / rate), water paddle beside the speed-and-power trace,
metric tiles, power and rate bars, then a Diagnostics button.

Diagnostics is a **slide-in drawer** from the right (62% of width, capped 460dp), full
height, scrollable, with a scrim behind it. It overlays the instruments rather than
sharing their height - inline it starved both. Dismiss via the drawer's close icon, the
scrim, or back. Two non-obvious details: the drawer must be `setClickable(true)` or taps
fall through to the scrim and close it, and children inside the ScrollView need explicit
heights because weights collapse to zero there.

Games will need a home screen in front of all this; see Suggested Next Steps.

## Design Principle: the decay IS the instrument

The speed needle falling away between strokes is not decoration and not merely accuracy.
It is the primary feedback the app exists to give:

- A real boat decelerates between strokes. Rowers call this the **run** of the boat, and
  reading it is how you judge your own stroke - a strong drive shows a high peak and a slow
  decay, a sloppy one dumps speed straight away.
- Watching the speed bleed off is what makes you want to take the next stroke. A needle that
  holds flat removes the reason to keep pulling.

Consequences for anything built later:

- **Never hold a value flat because a reading has not been refreshed.** If the paddle is
  slowing, show it slowing. Holding the monitor's last average is the single mistake that
  has caused the most rework on this project.
- Prefer a signal that resolves within a stroke over one that is smoother but averaged.
  `14A` is an *instant average* and hides exactly the variation that matters.
- Judge the between-stroke curve on the machine, not from a plot. If the decay does not
  make the user want to pull again, it is wrong regardless of what the fit says.

## Water Physics And The Coast-Down

The machine is WaterRower hardware in an Ergatta fit-out, so the published rowing-ergometer
physics applies directly.

### The model

Flywheel equation of motion, with drag torque quadratic in speed:

```text
I dw/dt = T - k w^2        drag torque D = k w^2       power P = k w^3
```

With no applied torque this integrates to

```text
w(t) = w0 / (1 + (k/I) w0 t)
```

which is what the gauges and the paddle use, with `K = k/I`. On a water rower the paddle in
the tank is effectively the entire resistance, so **do not add a linear friction term** - an
earlier build did, and it was only compensating for a drag coefficient set far too low.

Drag is asymptotic, so the needle is snapped to zero below 0.5% of full scale. That is
display quantisation, not physics.

Source: <https://eodg.atm.ox.ac.uk/user/dudhia/rowing/physics/ergometer.html>

### Knowing when to coast: what does NOT work

The S4 holds stale values rather than decaying them, so "recently answered" does not mean
"still true". Three triggers were tested against captured data:

- **`watts == 0`** - rejected. Zero in **27% of samples taken while actively rowing**, with
  runs up to 9.7s. The S4 reports instantaneous power, which really is zero between strokes.
- **Speed (`14A`) value unchanged** - rejected as primary. It is an average that holds its
  last figure for ~10s after you stop. While rowing it changes every 2.7s median, 4.66s
  worst case, so a safe threshold would exceed 5s.
- **`flywheelMoving` (pulses arriving)** - rejected. The paddle keeps turning for many
  seconds after the drive ends, so this stays true through the whole coast.

### What is used

**The stroke counter (`140`) going quiet.** It is exact, ticks once per stroke, and has no
quantisation ambiguity. The allowance scales with stroke rate - 2.5 stroke periods, floor
4s - so it cannot misfire at low ratings.

Additionally the needle may **never fall faster than drag allows**, whatever the monitor
suddenly reports. A reading that collapses is the average catching up, not the paddle
stopping dead.

### Unresolved

- **The drag constant is unmeasured.** `k = 0.6` was chosen by feel. At 25spm (strokes ~2.4s
  apart) it implies the paddle sheds ~80% of its speed between strokes, which is almost
  certainly too aggressive. Address `148` ("total distance per second", as against `14A`'s
  "instant average") is being probed: if it is genuinely instantaneous it gives the real
  per-stroke rise and fall, and `k` can be fitted to this machine instead of guessed.

- `P = k w^3` implies speed scales as `watts^(1/3)`. Tested on 2498 samples: CV 18.0% for
  the cube law versus 15.6% for a square-root fit, errors to +/-50%. **Inconclusive** -
  watts and `14A` are asynchronous samples with different averaging windows, so the test
  cannot separate a wrong law from bad sampling.
- **`Pxx` is a count per interval, not a period - and it does carry effort.** The earlier
  reading was half right. Packets arrive at a fixed **40.0/s** whenever the flywheel turns (p10
  39.9, p90 40.1, r^2 0.00 against speed), so the *arrival rate* says only "moving or not", and
  `1/Pxx` as a period correlates at r = -0.23. But the *payload* was never tested as a count.
  Against 7330 moving samples it rises monotonically with speed and power:

  | Pxx | n | mean speed | mean watts |
  |---|---|---|---|
  | 7 | 1324 | 3.75 m/s | 119 W |
  | 9 | 1325 | 3.78 m/s | 123 W |
  | 11 | 703 | 3.84 m/s | 130 W |
  | 12 | 151 | 3.92 m/s | 147 W |
  | 13 | 20 | 4.53 m/s | 205 W |

  Per-sample correlation is weak (r = +0.24 speed, +0.30 watts), so it is noisy stroke to stroke
  and needs smoothing over several packets; values 1-3 sit off the trend and are probably coast.

  **This is the most under-used signal on the link.** It arrives at 25ms resolution, unsolicited,
  with no write involved - so it survives a broken write path, and it is forty times finer than
  the ~1s poll and the ~2.5s `088` refresh. The architecture should be the reverse of today's:
  pulses as the primary liveness signal driving the needles, memory reads as the slow
  authoritative correction. "It feels dead", "it misses strokes" and "when I stroke hard it
  doesn't capture" are one problem - a one-hertz sample rate on a sub-second event.
- The drag factor could be measured properly rather than tuned by eye: `k = I d(1/w)/dt`,
  fitting a line to `1/w` against time during a recovery phase. Needs a usable per-pulse
  timing signal, which the `Pxx` value does not appear to give.

## Coast Flight performance on the old engine

Chrome 70 on tablet silicon needed real cuts, all gated on `LEGACY` so a modern WebView keeps the
full quality: `resolutionScale` 0.6, `maximumScreenSpaceError` 6 (far fewer tiles), lighting and
fog off, no ground atmosphere, terrain without vertex normals, the 26 route labels dropped to
bare points, and the camera updated at ~30Hz instead of every frame - each `setView` is a full
scene traversal.

**The map card could not switch maps** because the page called `window.location.reload()`, which
has to pass `shouldOverrideUrlLoading` - and that is blocked wholesale to stop a stray link
stranding the user in a browser. The page now asks Java to reload through the bridge instead.

## Analysing A Capture

```sh
python3 tools/analyze-capture.py [path/to/events.jsonl] [--version 0.7]
```

Reports link health, per-address decode ranges, the stroke-rate question and the
water-speed fit. Works on archived captures in `server/data/`.

## Protocol Tests

`S4Protocol` has no Android dependencies, so it runs under plain javac:

```sh
javac -d /tmp/protoclasses app/src/main/java/com/codex/waterrowerdiagnostic/S4Protocol.java \
  tools/prototest/com/codex/waterrowerdiagnostic/S4ProtocolTest.java
java -cp /tmp/protoclasses com.codex.waterrowerdiagnostic.S4ProtocolTest
```

Covers packet framing across read boundaries, memory decode, clock rollover, pulse packets
not satisfying a pending read, and ERROR settling only the outstanding request.

## Run Dashboard

From project root:

```sh
node server/server.js
```

URLs:

```text
http://localhost:8787
http://<laptop-ip>:8787
```

The running server stores events in:

```text
server/data/events.jsonl
```

## Build APK

Use local tools already downloaded under the parent `work` directory:

```sh
ANDROID_HOME=<tools>/android-sdk \
ANDROID_SDK_ROOT=<tools>/android-sdk \
<tools>/tools/gradle-8.13/bin/gradle assembleDebug \
-PdiagnosticServerUrl=http://<laptop-ip>:8787
```

After building, copy:

```sh
cp app/build/outputs/apk/debug/app-debug.apk server/public/downloads/ergatta-row-diagnostic-debug.apk
cp app/build/outputs/apk/debug/app-debug.apk <repo>/docs/downloads/ergatta-row-diagnostic-debug.apk
```

Verify version:

```sh
<tools>/android-sdk/build-tools/35.0.0/aapt dump badging app/build/outputs/apk/debug/app-debug.apk
```

## Main Files

Android (`app/src/main/java/com/codex/waterrowerdiagnostic/`):

- `MainActivity.java` - UI, USB enumeration, auto-connect, serial read/write, upload
  queue, laptop discovery, diagnostics drawer. Large; the UI builders are separated from
  the connection logic.
- `S4Protocol.java` - packet framing, request/response correlation, memory decode, derived
  values, coast signals. **No Android dependencies**, so it compiles under plain javac and
  is the only part with tests.
- `GaugeView.java` - analog dial. `waterDrag(k)` gives the needle momentum, `attack(n)`
  sets how fast it climbs, `setDrag(k)` retunes live.
- `PaddleView.java` - water wheel. Magnitude from `14A`, responsiveness from the pulse
  stream.
- `SparklineView.java` - boat speed over power, fed from the coasted needle at 5Hz.
- `BarMeterView.java` - eased, auto-ranging bars.
- `BoatSpeedModel.java` - the coast physics as pure Java, shared by games. Tested.
- `GameView.java` - base for every game: coasted boat, smoothed distance, lifecycle.
- `RiverRenderer.java` - water, boats, foaming wakes and bow spray.
- `Fx.java` - shared effects: a particle pool, screen shake, radial glow, vignette and speed
  lines. Use these rather than hand-rolling; every hero game does.
- `PersonalBests.java` - SharedPreferences store for records, ghosts and the journey.
- The games, one file each: `PaceBoatGame` (and `GhostRaceGame`, a subclass), `TheRunGame`,
  `IntervalGame`, `JourneyGame`, `StormGame`, `PowerZonesGame`, `SprintLadderGame`,
  `TugOfWarGame`, `CollectorGame`, `DepthDiveGame`, `HeadRaceGame`, `ZombieRunGame`,
  `RowRunnerGame`, `BossFightGame`, `CanyonFlightGame`, `MegaPullGame`, `CanyonChaseGame`,
  `RocketLaunchGame`, `SkylineGame`, `WaveRiderGame`.
- `GameIconView.java` - a drawn glyph per game for the home grid.
- `GaugeStripView.java` - the vitals bar shown above every game.

Tooling:

- `tools/analyze-capture.py` - link health, decode ranges, calibration fits. Works on
  archived captures.
- `tools/prototest/.../S4ProtocolTest.java` - offline protocol checks, plain javac.
- `tools/prototest/.../BoatSpeedModelTest.java` - coast model checks, plain javac.

Server (`server/`):

- `server.js` - HTTP, dashboard API, SSE, APK download, UDP discovery, capture export.
- `public/app.js`, `index.html`, `style.css` - the dashboard.

Docs:

- `GAME_IDEAS.md` - 20 ranked game concepts with signals, cost and risk.

## Event Types

Lifecycle:

```text
app-started        app-linked         app-exit-requested
laptop-discovered  manual-test        usb-snapshot
device-selected    usb-attached       usb-detached
```

Connection:

```text
serial-opened          serial-open-failed     serial-reader-started
serial-reader-stopped  serial-read-stopped    connection-closed
open-without-device    raw-usb-reader-started raw-usb-reader-stopped
raw-usb-read-empty
```

S4 transport and protocol:

```text
s4-write-failed     s4-write-cooldown   s4-write-recovered
s4-start-failed     s4-port-reopen      s4-reopen-limit
s4-interface-reclaim s4-monitor-error   s4-polling-paused
s4-polling-stopped
```

Data:

```text
rowing-status   raw-bytes
```

Coast Flight:

```text
coast-flight-opened   coast-flight
```

`coast-flight` carries `stage` and `detail`: the engine and CesiumJS version at boot, then any
page failure or console error. Forced, so it arrives with streaming off.

`usb-snapshot` is a one-time device inventory carrying the full descriptor tree.
Continuous rower status is `rowing-status`; every other event carries only a short
device identity.

## Suggested Next Steps

**Games** - twelve built, see [GAME_IDEAS.md](GAME_IDEAS.md) for what is left and why.

**The games have been seen running and all work.** Tuning the three new ones is the
open work: the horde's creep and surge rates, Row Runner's pit widths and wall watts, and
the boss's HP and attack cadence were all set without playing them. The rowing-status telemetry now carries a `screen` field naming the
active game, so the capture shows what the user was looking at when something went wrong.
Likely first fixes: card widths on the home scroller, text sizes in the HUDs, and the
Storm/Tug/Ladder difficulty ramps, all of which were set without seeing them.

**Last session** is `last.session` as `metres|seconds|avgWatts`, saved when returning home
and on exit. Session time runs from the first stroke, not from launch.

**PB keys in use** (all in `PersonalBests`): `time.<m>`, `ghost.<m>`, `run.streak`,
`run.score`, `intervals.<plan>`, `journey.total`, `storm.<W>`, `ladder.<W>`, `tug.<level>`,
`flight.along` and `flight.map` (Coast Flight's trip and chosen map, not records),
`collector.score`, `dive.joules`, `zombie.<pace>`, `runner.distance`, `runner.coins`,
`boss.level`, `canyon.gates`, `megapull.peak`, `chase.distance`, `rocket.altitude`,
`city.blocks`, `city.tallest`, `surf.score`, `surf.ride`.

**Skyline is the only game with persistent world state.** `city.blocks` is a lifetime
count and the city is rebuilt from it on entry, so the towers you see are every block you
have ever placed. It is saved in `onStop()`, which runs from `showScreen` and `exitApp` -
if you add another persistent game, save it the same way rather than per-frame. `refreshPersonalBests()` and `showRecords()` both format
by prefix; add a new key to both.

**Still open on the instrument side:**

1. **The coast drag constant is unmeasured.** Default `k = 0.12`, tunable in the drawer.
   It has been lowered three times (0.6 -> 0.3 -> 0.12), each time because the user said
   it was still too fast. A real paddle holds its run far longer than a naive constant
   suggests. It cannot be fitted from data: the monitor reports nothing during coast, and
   `148` would have been the way in.
2. **Water-speed calibration.** `P = k w^3` implies speed scales as `watts^(1/3)`. Tested
   on 2498 samples: CV 18.0% versus 15.6% for a square-root fit. Inconclusive - watts and
   `14A` are asynchronous samples with different averaging windows.
3. **Drag factor could be measured** as `k = I d(1/w)/dt` if a per-pulse timing signal
   ever becomes available. The `Pxx` arrival rate is not it (fixed 40Hz), but the payload is a
   count per interval and is worth fitting - see the decode notes.

## Dashboard Notes

- `POST /api/session/reset` archives `events.jsonl` to `events-<timestamp>.jsonl` and
  starts a clean capture. Nothing is deleted.
- `GET /api/export` downloads the current capture.
- Routine events carry a short device identity; the full USB descriptor tree is only in
  `usb-snapshot`. Before 0.3.0 every event embedded the whole tree.
