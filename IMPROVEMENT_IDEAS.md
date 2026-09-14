# WAKE — Improvement Ideas

> **Status, 2026-09-14:** built in 3.13.0 - 3.18.0, except sound (the rower said no sound, so Rhythm
> Row and all audio were dropped). Handing the rower back to Ergatta is resolved by decision: a tablet
> restart is the accepted way back. Everything below is built but **not yet seen on the tablet**.
>
> | Idea | Status |
> |---|---|
> | Precise energy + load-scale calibration | Built (3.13.0, `PulseMeter`, CALIBRATE) |
> | Tune every game to the rower | Built (3.14.0, `RowerProfile`) |
> | Merges: Race, Zone Row plans, one Canyon | Built (3.14.0) |
> | Coast Flight list, Head Race, Canyon rings | Built (3.14.0 - 3.16.0) |
> | Row Runner, Skyline, Wave Rider, Collector, Mega Pull, gauges | Built (3.15.0) |
> | River Explorer, Stroke Coach, Crew Boat, Night Grid, Regatta, Daily Row | Built (3.17.0) |
> | Heart Zone Row + BLE strap | Built, untested without a strap (3.17.0) |
> | Levels, weekly goal, streak, Continue, Session Art, first-run help | Built (3.18.0) |
> | Race a friend's recording | Built via the laptop (3.18.0) |
> | Handle sensor steering: River, Wave Rider; Stroke Coach handle level | Built, untested without the sensor |
> | Sound, Rhythm Row | Not built - the rower asked for no sound |

Where WAKE could go next: games to add, remove and improve, plus bigger ideas. Written on
2026-09-13, after building ZONE ROW (3.12). It follows the original [GAME_IDEAS.md](GAME_IDEAS.md).

**The goal:** keep the rower rowing longer and longer.

**The test every idea has to pass:** is something at stake in the next ten seconds? The first
twelve games failed that test. They were "dashboards with a score". The three built in response,
Zombie Run, Row Runner and Boss Fight, pass it.

## What changed since GAME_IDEAS.md was written

Some constraints behind the original list no longer hold:

- **Strokes arrive one at a time.** Since the USB ownership fix (3.10), every stroke lands on its
  own, so stroke-by-stroke rhythm games are possible now.
- **The pulse stream is continuous at 40 per second.** Its value rises and falls within a single
  stroke. That is enough to see the *shape* of a stroke (drive, peak, recovery), not just that it
  happened. It is the most under-used signal on the link.
- **The tablet has Bluetooth.** It can talk to a heart-rate strap directly (the standard BLE heart
  rate service) without going through the monitor, whose heart-rate register reads 0. A handle
  tilt sensor (WitMotion WT901BLECL) is supported in code but not yet tested on the rower.
- **WAKE makes no sound.** There is no audio anywhere in the app, even though the tablet can play
  through its speaker or Bluetooth headphones.
- **Your measured range is known:** 129 W, 3.85 m/s, 25 spm and 2:08/500m on average.

---

## 1. Remove or merge: from 19 cards to about 13 stronger ones

Too many cards feel similar. That makes each one smaller on the home screen and dilutes the good
ones. These merges come from your own verdicts:

| Today | Proposal | Why |
|---|---|---|
| **Pace Boat** + **Ghost Race** | **One "Race" card**; pick the opponent: a set pace, your best, your last session, or a pacer that adapts to you | Same game, same complaint ("we pull ahead and it never catches up"). An adaptive pacer fixes it for good. |
| **Intervals** + **Sprint Ladder** + **The Run** | **Become ZONE ROW workout plans**: Pyramid, Ladder, Sprints, Streak | You said Intervals was "hard, doesn't seem to move" and Sprint Ladder was "hard to get the bar to move". ZONE ROW already has the lanes; a plan just moves the target zone over time. This is also the row-schedule game you asked for. |
| **Journey** | **Fold into River Explorer or Coast Flight** as your lifetime distance | "Doesn't look like we move." A lifetime total is better shown by a map you have actually uncovered. |
| **Canyon Flight** + **Canyon Chase** | **One canyon game** with two modes: fly (altitude from power) and drive (steer with the handle sensor) | Two canyon cards, both with steering problems. |
| **Tug of War** | **Rework with levels, or cut** | "Didn't work well." Keep it only if levels make it feel fair. |
| **Rocket Launch** | **Keep as a 60-second power test**, and tell people that's what it is | Works as a quick check of your power, not as a session. |

Keep as they are, with the improvements below: Zombie Run, Row Runner, Coast Flight, Skyline,
Wave Rider, Mega Pull, Head Race, Collector, ZONE ROW.

---

## 2. Improve what's there, from your feedback

### Coast Flight — waiting on your list
- Start **hovering in mid-air** until you begin rowing.
- **Flapping wings** in time with your strokes; glide on the recovery.
- **Weave with the stroke** instead of flying a straight line: lift on each drive, dip in between.
- **Fly low and follow the ground** a little, so hills and cliffs slide past close by.
- **Preload the map tiles** ahead on the route while you are still hovering.
- **Higher resolution**, within what the tablet's old browser engine can handle.
- **Place names on the ground** (towns, beaches, bridges), shown only near the bird to stay fast.

### Head Race — fill the black space
- A **gap-to-opponent graph** over time, so you can see yourself gaining or losing.
- **One bar per stroke** showing its power, colour-coded against your average.
- A **distance-to-go strip** with a marker for each crew.
- A **live stroke-shape trace** from the pulse stream, beside your boat.

### Canyon Flight — rings are too hard to fly through
- Make the rings **bigger**, and **pull the bird toward the centre** once it's close.
- Set altitude from a **rolling average of power** with a small dead zone, so one weak stroke
  doesn't drop you.
- **Place each ring at the height you have been flying recently**, so it's always reachable.

### Row Runner — make it fun
- **Jump automatically when a stroke lands.** Time obstacles to arrive when your next stroke is
  due, based on your stroke rate.
- **Walls take power:** a hard stroke smashes through, a weak one bounces you back.
- **Coins in arcs** you collect by keeping a steady rhythm.

### Skyline — a block factory
- Each drive **fills a crane's hopper**; harder strokes make bigger blocks.
- **Windows light up at night** from the energy you have produced.
- Show the **session's building rising** next to your all-time skyline.

### Wave Rider
- A **sweet-spot bar at the side** showing where you are against the ideal speed band.

### Tug of War
- **Levels** where the opponent's strength is set relative to *your* average power, not a fixed
  number of watts.

### Collector
- **Change lanes smoothly** from an averaged speed with some hysteresis, so the boat glides
  between zones instead of snapping.

### Mega Pull
- **Screen shake scaled to your watts**, a **record line** at your best pull, and a
  **"NEW BEST" burst** when you beat it.

### ZONE ROW
- **Workout plans** (see the merges above).
- **Zones that fit you:** set the zone edges from your last ten sessions instead of fixed times.
- **Spoken callouts** (see audio below): "Split 2:06, rate 26, halfway."

### Gauges screen
- **A stroke-shape trace:** the last few strokes drawn from the 40-per-second pulse stream.
- **One bar per stroke** showing its power, so a weak stroke stands out.

---

## 3. New games

Ranked by how likely each is to keep you rowing longer.

### 1. River Explorer — first-person, with a minimap (you asked for this)
You look over the bow of your boat on a winding river. Your speed moves the boat.
- **The minimap starts blank and fills in as you explore**, and it is saved, so every session
  uncovers more.
- **Landmarks** appear every few minutes: waterfalls, bridges, herons, a lighthouse.
- **Where the river forks**, you pick a branch by tapping between strokes, or later by leaning
  the handle sensor.
- **Replaces Journey** as the "lifetime distance" game.
- **Why it keeps you rowing:** there's always something just around the next bend.

### 2. Stroke Coach — see the shape of every stroke
Uses the pulse stream to draw each stroke's shape: how long the drive is, when power peaks, and
the ratio of drive to recovery.
- Your **best stroke** is kept as a faint outline behind each new one.
- **Aim to match it:** a score for consistency, and tips like "peak arrives late" or
  "rushing the recovery".
- **Unique to WAKE.** No commercial app shows the stroke shape on this machine.

### 3. Crew Boat — you set the rhythm, the crew follows
You are the stroke seat of an eight. Seven rowers copy your rhythm.
- **Steady strokes** bring the crew into sync, and the boat surges forward.
- **Uneven strokes** make oars clash and splash, and the boat slows.
- **Rewards steady, even rowing,** which is exactly what makes long sessions easier.

### 4. Rhythm Row — row to the beat (needs audio)
Music plays at your target stroke rate.
- **Take the catch on the beat** to score, timed from the pulse stream, which is precise enough
  now.
- **Tracks get faster in stages:** 20 → 24 → 28 spm.
- You could choose music from the tablet or a Bluetooth source.

### 5. Night Grid — your power lights up a town
A dark valley at night. The power you produce keeps houses lit.
- **Stay above a steady wattage** and street by street lights come on.
- **Stop rowing** and the town goes dark from the edges inwards.
- **The town grows across sessions** with your lifetime energy.
- **Why it keeps you rowing:** stopping has a visible cost.

### 6. Regatta Season — a career mode
A season of races against AI crews set relative to your own range.
- **Win to move up a division;** unlock new boats and colours.
- **One race a day,** so there's a reason to come back tomorrow.

### 7. Daily Row — one shared challenge a day
- The **same short piece every day**, generated from the date, and a **streak calendar**.
- **Fits a Reddit community well:** "today's WAKE Daily was brutal".

### 8. Heart Zone Row — needs a Bluetooth heart-rate strap
- Connect a strap **straight to the tablet** (not through the monitor).
- A ZONE ROW-style screen by **heart-rate zone**.
- Later, **zone targets on every game**.

---

## 4. Bigger ideas

### Keep-rowing systems that work across every game
- **Experience points and levels** across all games, earned from metres rowed and efforts made.
- **A weekly goal ring** on the home screen: "34 of 60 minutes this week".
- **A streak counter** for days in a row, with **one free rest day a week** so it doesn't punish
  recovery.
- **A "Continue" button** that goes straight to your last game.

### Tune every game to the person rowing
**The biggest single fix.** Every "the bar won't move" complaint came from a threshold set without
real numbers.
- WAKE could **learn the rower's range from their own sessions**, on the tablet: low, typical and
  high power, speed, stroke rate and split.
- **Every game sets its targets from that,** with no manual tuning.
- **Essential for other people** downloading WAKE from Reddit: they won't row at 129 W.

### Audio (there is currently none)
- A **water splash on the catch**, and oar sounds in Crew Boat.
- **Spoken callouts** every 250 m or each minute, in ZONE ROW and the races.
- Warnings such as **"the horde is gaining"** in Zombie Run.
- **Settings:** off, speaker, or headphones.

### Session Art — a poster from your workout
- At the end of a session, **draw your power and rhythm as a unique image**: a ring or wave
  pattern.
- **Save it** with your distance and time, and **share it**, which is ready-made for Reddit and
  GitHub.

### Race someone else, safely
- **Export a ghost race** as a small file and **import one** from a friend.
- Race their recording on your rower.
- **No servers, no accounts.** You choose when a file moves, which keeps within "nothing leaves
  your network" unless you choose to share.

### Handle tilt sensor — once it's tested
- **Steering** in River Explorer, the canyon game and Wave Rider.
- **Stroke Coach extra:** detect a handle that isn't level (one arm pulling harder).

### For people coming from Reddit
- **A 60-second first-run tour:** take a stroke, watch the needle, pick a game.
- A **"What your numbers mean"** card on the home screen.
- **A clear "Back to Ergatta" note:** exit WAKE, and restart the screen if Ergatta then doesn't
  read strokes.

---

## 5. Not games, but first

- **Fix handing the rower back to Ergatta.** After a WAKE session, Ergatta can't read strokes
  until the screen is restarted. Ideas to test:
  - **Stop reclaiming** the USB connection after about 60 s without a stroke.
  - **Release it** as soon as the rower walks away.
  - **Reclaim only when a command fails**, not on a timer.
- **Update the game count** on the page and README once the merges happen. There are 19 cards
  today; the page says 18.
- **Take the screenshots** for GitHub and Reddit with the new camera button.

---

## Suggested order

| # | Idea | Effort | Why now |
|---|---|---|---|
| 1 | Handing the rower back to Ergatta | M | It protects the fallback |
| 2 | Learn each rower's range, and tune games to it | M | Fixes "doesn't move" everywhere, and helps every new user |
| 3 | Merge cards: Race, ZONE ROW plans, one canyon game | M | Fewer, stronger, bigger cards |
| 4 | Coast Flight list, Head Race graphics, easier Canyon rings | M | Your outstanding feedback |
| 5 | Audio: catch splash and spoken callouts | S | Cheap, and makes everything feel alive |
| 6 | River Explorer | L | The game you asked for, and it keeps you exploring |
| 7 | Stroke Coach | M | Unique to WAKE, and uses the richest signal |
| 8 | Crew Boat | M | Rewards steady rowing, so sessions last longer |
| 9 | Weekly goal, streaks, levels | M | Reasons to come back tomorrow |
| 10 | Session Art | S | Shareable, and good for Reddit |
| 11 | Night Grid, Regatta Season, Daily Row, Rhythm Row | M–L | Once the core is strong |

**Effort:** S = about a day, M = a few days, L = a week or more.
