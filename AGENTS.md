# AGENTS.md — start here if you are an AI agent customizing WAKE

WAKE is an Android app that reads a WaterRower S4 monitor over USB and turns it into analog
instruments, games and a Cesium flight. It was built with an AI agent, on an Ergatta rower tablet,
one build at a time with the rower rowing and reporting back. Your human wants to make it their own.
This file gets you productive without repeating the mistakes that cost the original project days.

## Read in this order

1. **This file** — setup, the rules, and how to test with no emulator.
2. **[README.md](README.md)** — what the app is and does.
3. **[CLAUDE.md](CLAUDE.md)** — the full engineering log. It is long and partly history (some version
   numbers in it are out of date — trust the code over the prose). Search it rather than reading
   it end to end. The sections that matter most for changes:
   - *Game Architecture* — the `GameView` contract and the steps for adding a game
   - *Tune games to THIS envelope* — why a game "doesn't move", every time
   - *The coast shape* and *Design Principle: the decay IS the instrument*
   - *The tablet's WebView is Chrome 70* — before touching Coast Flight
   - *Hard Guardrails* and the USB interface-ownership sections — before touching the link

Claude Code loads `CLAUDE.md` automatically. Other agents: read it explicitly.

## First steps

```sh
# Your human's fork, on a branch of its own
git clone https://github.com/<their-account>/wake.git && cd wake
git checkout -b my-wake

# Turn on the secret scanner BEFORE the first commit. Never bypass it with --no-verify.
git config core.hooksPath tools/git-hooks
```

Toolchain: **JDK 17+** and the **Android SDK with platform 35** (set `ANDROID_HOME`, or `sdk.dir` in
`local.properties`). Then:

```sh
./gradlew assembleDebug            # -> app/build/outputs/apk/debug/app-debug.apk
```

## There is no emulator. Your human is the display.

The tablet is a kiosk-locked Android 9 device with a 1920x1080 screen at density 1.0. You can
compile but you cannot see the UI, and layouts that look right in your head are routinely wrong on
it. So work in small builds:

1. Make one change. **Grep that it actually landed in the source**, then build.
2. Bump `versionCode` and `versionName` in `app/build.gradle` every build — it is how your human
   tells builds apart on the tablet.
3. Get it onto the tablet (below), ask what they see, and fix from their description.

Build with `-PscreenshotUpload=true` and the app shows a **camera button on every screen** that
sends that screen to the laptop dashboard (`server/data/screenshots/`), so you can see it too.
Never ship that flag in anything published.

## Getting an APK onto an Ergatta rower

**Via the laptop dashboard (best for iterating):**

```sh
node server/server.js                                       # no npm install needed
./gradlew assembleDebug -PdiagnosticServerUrl=http://<laptop-ip>:8787 -PscreenshotUpload=true
cp app/build/outputs/apk/debug/app-debug.apk server/public/downloads/ergatta-row-diagnostic-debug.apk
```

On the rower: **restart the screen, tap the top-left corner of the log-in screen 8 times, open the
browser**, go to `http://<laptop-ip>:8787`, and tap **Install APK**. The monitor is already plugged
in on these machines. Tablet and laptop must be on the same Wi-Fi.

**Via their fork's GitHub Pages:** `docs/` is the site. Enable Pages from `/docs`, put a build made
*without* the two `-P` flags in `docs/downloads/`, and point the download button at it.

`tools/publish-apk.sh` does both builds at once, but assumes the original author's SDK layout
(`../android-sdk`, `../tools/gradle-8.13`). Adapt it or build by hand.

### "App not installed": the signing clash

Debug APKs are signed with each machine's own debug key. If the tablet already has WAKE from the
original project's page, Android refuses to install your build over it. Either uninstall that one
first (Settings → Apps), or — easier on a kiosk — give the fork its own identity so both install
side by side: in `app/build.gradle` change `applicationIdSuffix '.debug'` to something like
`'.mine'`, and change the launcher label in `app/src/main/res/values/strings.xml` so your human
can tell them apart. Leave `namespace` and the base `applicationId` alone.

## The rules

**Hard guardrails — do not cross these, whatever you are asked:**

- Do not root the tablet or modify Android system software.
- Do not disable, remove or alter the Ergatta app. It must stay the working fallback.
- Keep WAKE manually launched: no boot receivers, no auto-start.
- Keep network traffic local. The only exception is CesiumJS and map tiles on Coast Flight.
- **Keep the USB release in `MainActivity.onStop`.** While on screen WAKE takes the rower's
  interface back from its other owner; without the release it would starve Ergatta from the
  background. Even with it, Ergatta may need a screen restart after a WAKE session — tell your human.

**Secrets:** no Cesium Ion token, key, LAN address or home-directory path in source, ever. The
token is served by the laptop from the gitignored `server/cesium.local.json`. The pre-commit hook
enforces this — if it blocks a commit, fix the content, never the hook.

## How the code fits together

Everything is in `app/src/main/java/com/codex/waterrowerdiagnostic/`.

| File | What it is |
|---|---|
| `S4Protocol.java` | Packet framing and memory decode. No Android — tested with plain `javac`. |
| `MainActivity.java` | USB link, screens, home grid, diagnostics drawer. Large. |
| `GameView.java` | Base class for every game: coasted speed, smoothed distance, rowing clock. |
| `Coast.java`, `BoatSpeedModel.java` | How every needle and boat winds down. Tested. |
| `*Game.java` | One file per game. `ZoneRowGame` and `CollectorGame` are small, readable examples. |
| `GameIconView.java` | The drawn icons on the home grid. |
| `app/src/main/assets/coastflight/` | Coast Flight's page — Chrome 70 JavaScript and CSS only. |
| `server/` | The optional laptop dashboard (plain Node, no dependencies). |

**Adding a game:** create `FooGame extends GameView` and implement `render(Canvas, dt)`; add an
`openFoo()` in `MainActivity` that calls `showGame(game, gameScreen("FOO", game, null))`; add a
`gridCard(...)` in `buildHome()` with a glyph in `GameIconView`. Details in CLAUDE.md.

**Rules of thumb that each cost a day to learn:**

- Use `boat.value()` for speed, never the raw `status.waterSpeedMps` — the raw value steps once a
  second and sits flat between readings.
- **Never hold a displayed value flat** while waiting for a fresh reading.
- `onStroke()` fires about a second after the drive. Use it to count strokes, never to read power.
  Peaks come from `onStatusChanged()`.
- **Tune difficulty to the actual rower.** The original was tuned to one person: median 129 W,
  3.85 m/s, 25 spm, 2:08/500m. Your human may be faster or slower. With the dashboard running and
  streaming on, their real numbers land in `server/data/events.jsonl` — measure before setting
  thresholds.
- Do not add addresses to the monitor's poll loop casually; every one slows all the others.
- Coast Flight runs in a **Chrome 70 WebView**: no `?.`, no `??`, no CSS `inset` or flex `gap`.
  A desktop browser hides these; the tablet shows a blank screen.

## Tests

```sh
S=app/src/main/java/com/codex/waterrowerdiagnostic T=tools/prototest/com/codex/waterrowerdiagnostic
javac -d /tmp/wake-tests $S/S4Protocol.java $S/Coast.java $S/BoatSpeedModel.java \
  $T/S4ProtocolTest.java $T/BoatSpeedModelTest.java
java -cp /tmp/wake-tests com.codex.waterrowerdiagnostic.S4ProtocolTest
java -cp /tmp/wake-tests com.codex.waterrowerdiagnostic.BoatSpeedModelTest
```

## Sharing back

If your human builds something others would enjoy, open a pull request against
`dustin-gamble/wake` `main`: one change per PR, tested on a real rower, with a screenshot.
