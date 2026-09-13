# WAKE — Game Ideas

Twenty game concepts for the rower, ranked. Written against what the machine actually
gives us, not what would be nice.

## What we have to build with

**Live signals, confirmed working on this unit:**

| Signal | Address | Quality |
|---|---|---|
| Power (watts) | `088` | Instantaneous, 0–270 observed, refreshes ~1s |
| Boat speed (m/s) | `14A` | Averaged, 0–4.85, lags ~1s |
| Distance (m) | `057` | Real, monitor-displayed, counts up |
| Stroke count | `140` | Exact counter |
| Stroke rate (spm) | `1A9` | 0–37 observed |
| Elapsed time | `1E1` + rollovers | Seconds |
| Flywheel pulses | `Pxx` | ~40/s while moving, stops when the paddle does |
| **Coasted speed** | derived | The needle value — rises on the drive, decays after |

**Not available:** heart rate (`1A0` answers but always 0 — no strap), instantaneous
speed (`148` refused), stroke timing (`142` refused). **Do not design around these.**

**Build constraints:**

- Custom `Canvas` views only. No game engine, no external art assets, no physics library.
  Everything drawn with paths, arcs, gradients and text — as `PaddleView` and `GaugeView`
  already are.
- The laptop server (`server/server.js`) is available for persistence: personal bests,
  ghost recordings, session history. It already stores every event as JSONL.
- Single player on one tablet. Two-player only via the laptop as a relay.
- Nothing may block the serial reader or add load to the poll loop.

**Cost key:** **S** = a day or less, one new View. **M** = a few days, new View plus
server persistence. **L** = a week or more, or needs infrastructure we do not have.

---

## Home screen

A launcher, not a menu. Three rows:

1. **Resume / Quick row** — straight to the gauge screen, the current instrument panel.
2. **Games** — a horizontally scrolling row of cards, each showing its own personal best
   so the number itself is the invitation.
3. **History** — last ten sessions, distance and average power.

Every game shares the same contract: it receives `S4Protocol.Status` updates and draws
itself. That keeps games isolated from the protocol and means a broken game cannot break
the link. One `GameView` base class, one `onStatus(Status)` method.

---

## What the first hands-on session taught us (3.0.0)

Everything in the original list worked first time, and the user's verdict was that they
all "suck". Fair. They are **dashboards with a score**: they measure the row, they do not
threaten it or make it play. Three additions built on the feedback:

- **Zombie Run** - a horde behind you at a chosen pace that creeps faster, surges without
  much warning, and eats you if the gap closes. Safe houses every 500 m are rest intervals
  you have to reach. *Pressure.*
- **Row Runner** - a side-scrolling platformer. Boat speed is run speed and the world is in
  real metres. Pits auto-jump at the edge with a length set by your speed; walls need your
  peak watts over the last two seconds; coins are coins. Five hearts. *Embodiment.*
- **Boss Fight** - a sea monster with a health bar. Every stroke hits for your watts
  (double during its wind-up). It attacks every 11-18 s and you have three seconds to get
  above a dodge speed. It regenerates if you stop. Six bosses. *Bursts and tension.*

Two more in 3.2.0, same rule:

- **Canyon Flight** - boat speed is altitude, with a glide. Ring gates come at different
  heights every ~100 m; miss one and lose a life. Gates wander up and down, so you are
  changing gear constantly - intervals wearing a wingsuit. *Variation with stakes.*
- **Mega Pull** - a carnival high-striker. Five strokes, hardest you have got; the puck rises
  to your peak watts and rings the bell if you clear a target that creeps up with your
  record. Ten seconds, pure peak, good between sets. *A different shape of effort.*

Two more in 3.3.0:

- **Canyon Chase** - a pursuer on your tail through a twisting canyon in perspective. Your
  speed sets your position across the canyon, so holding the racing line through a bend
  means finding and holding a pace; the horizon rolls into every turn and a rear-view
  mirror shows the hunter growing. Scrape a wall and it closes. *3D pressure.*
- **Rocket Launch** - watts are thrust against gravity. Hold above the hover threshold and
  you climb, drop below and you fall. Each stage sheds mass so the threshold drops - the
  reward for a long effort is that it gets easier. Karman line at 100 km.
  *The long grind, counterpoint to Mega Pull's single burst.*

Two more in 3.4.0, both stepping outside the chase formula:

- **Skyline** - power buys concrete, blocks crane in over the lowest plot and fall at a
  speed set by how hard you are pulling, towers rise and light their windows as a slow
  day-night cycle turns. Isometric, camera panning around the city, faster while you work.
  **No fail state on purpose** - the one to row to when a chase would be exhausting - and
  the only game with a persistent world: every block you have ever placed is still standing.
- **Wave Rider** - the first **two-sided** game here. Position on the wave face is the
  integral of your speed minus the wave's, so matching its pace holds you still. Too slow
  and the lip breaks over you; too fast and you run out onto the shoulder and lose it. The
  pocket scores double, barrels triple, and the wave changes pace in sets so the target
  keeps moving.

Worth noting what Wave Rider exposed: every other game rewards pulling harder, which makes
them all the same argument in different costumes. A game that punishes **both** ends is a
genuinely different demand on the rower, and there is room for more of that.

**Coast Flight** (3.5.0) sits outside the game list because it is not a `GameView` at all: a
Cesium globe served by the laptop, driven by the SSE stream, flying a bird down the California
coast with power as lift and boat speed as airspeed. See CLAUDE.md for why it lives in the
browser and what that costs (it is the only part of this project that touches the internet).

The rule these follow and the originals did not: **something must be at stake in the next
ten seconds.** A gap that closes, a pit that is coming, an attack with a countdown. A number
that gets bigger if you try harder is not a game.

Stroke events: `GameView.onStroke(watts)` fires once per stroke-counter increment. Polled
every ~0.95 s, so up to a second late - fine for damage, useless for rhythm.

## The ranking

Ranked on motivation first, buildability second. A game that is trivial to build but does
not make you pull harder is worth less than one that does.

### Tier 1 — BUILT (2.3.0)

All five are in the app. See `CLAUDE.md`, "Game Architecture", for how they are wired.
Note The Run was built as a **streak** mechanic rather than a per-stroke decay score: the
coasted speed between readings is too model-driven to score individual strokes honestly, so
instead a run line sits at 70% of your rolling peak and the streak ends when the boat drops
below it. Same motivation, measurable instead of synthetic.

**1. Ghost Race — M — built**
Race a recording of your own previous session over the same distance. Two boats on a
river, yours and the ghost, separated by whatever gap your pace difference creates.
*Why it works:* the single most reliable motivator in endurance sport is a version of
yourself from last week. The gap is legible at a glance and every stroke moves it.
*Signals:* distance vs elapsed time. *Needs:* server stores past sessions keyed by
distance; replay interpolates ghost position.
*Risk:* nothing motivating until there is a first recording — seed with a default pace boat.

**2. The Run — S — built (as a streak)**
Not a game so much as a scoring layer. Each stroke is scored on how well the boat holds
speed through the recovery: high peak plus shallow decay scores high, a stroke that dumps
speed scores low. Running average shown as a 0–100 "run" score with a per-stroke bar.
*Why it works:* it rewards technique rather than raw effort, so it gives a reason to row
well on easy days. It is also the one metric that uses our coast curve as the instrument.
*Signals:* coasted speed between stroke-counter ticks. *Needs:* nothing new server-side.
*Risk:* the coast constant is tuned by feel, so scores are self-consistent but not
absolute. Fine for improvement-over-time, not for comparing between machines.

**3. Pace Boat — S — built**
A target boat moves at a pace you pick (or your PB pace). Stay level, fall behind, or pull
ahead. Same idea as Ghost Race but with no recording needed.
*Why it works:* immediate, needs no history, and the target is honest — you set it.
*Signals:* distance, elapsed. *Needs:* nothing.
*Risk:* none. This is the cheapest real motivator on the list and should probably be
built first even though Ghost Race ranks higher.

**4. Interval Coach — S — built**
Structured work/rest with a full-screen state: a big countdown, a target power band, and
a bar that turns green while you are inside it. Configurable pyramids and classic sets.
*Why it works:* structure beats willpower. Most people row longer with a plan.
*Signals:* power, elapsed. *Needs:* nothing.

**5. Distance Journey — M — built**
A named route accumulating across sessions — a lake, a river, a coastline — drawn as a
simple polyline with a marker for your position. "You are 34km along, 12km to the next
landmark."
*Why it works:* it makes short sessions add up to something, which is what keeps people
coming back midweek.
*Signals:* distance. *Needs:* server stores cumulative total and route definitions.

### Tier 2 — mostly BUILT (2.7.0)

Built: Storm, Power Zones, Sprint Ladder, Tug of War, Collector, Depth Dive, and Head Race
from Tier 3. Time Trial is covered by Pace Boat and Ghost Race, which both record
`time.<metres>`; a Records screen on the home page lists every best. Not built: Rhythm
Match (the stroke counter refreshes every ~0.95s, too coarse for beat timing) and Crew Boat
(more art than the no-assets constraint allows).

**6. Time Trial with PB board — S — covered by Pace Boat / Ghost Race + Records**
Standard 500m / 1000m / 2000m / 5000m against your own records. Split table, projected
finish, PB line.
*Why:* the benchmark everyone understands. *Needs:* server PB storage.

**7. Storm — M — built**
Waves build over time; holding power above a rising threshold keeps the boat level. Drop
below and the boat takes water, shown as the screen tilting and darkening. Survive as long
as possible.
*Why:* escalating pressure is a strong driver for short pieces. *Signals:* power.
*Risk:* punishing if tuned badly — needs a forgiving ramp.

**8. Rhythm Match — S**
A metronome sets a target stroke rate; you score by how close each catch lands to the
beat. Visual pulse plus per-stroke timing error.
*Why:* rate discipline is genuinely hard and this makes it a game.
*Signals:* stroke counter timestamps. *Risk:* our stroke-counter refresh is ~0.95s, which
is coarse for beat timing. Measure feasibility before building.

**9. Power Zones — S — built**
Colour-banded target zones; time accumulated in each zone shown as a stacked bar. A
session "profile" you try to shape.
*Why:* good for structured training, low effort to build. *Signals:* power.

**10. Sprint Ladder — S — built**
Progressively shorter sprints with decreasing rest, each one requiring a higher peak
power than the last. Fail when you cannot hit the target.
*Why:* peak power is satisfying to chase and sessions stay short.

**11. Tug of War — M — built**
A rope marker pulled toward you by your power against a computer opponent whose strength
ramps. Win by dragging the marker past a threshold.
*Why:* direct, physical, obvious. *Signals:* power vs a curve.

**12. Collector — M — built**
Objects drift down the river; you gather them by holding speed in the band where they
appear. Fast band, slow band, mixed.
*Why:* makes variable-pace work playful rather than tedious.

**13. Depth Dive — S — built**
Cumulative work (joules, integrated power) lowers a submersible past named depth markers.
Purely additive, no fail state.
*Why:* good for long steady sessions where a competitive frame would be exhausting.

**14. Crew Boat — M**
An eight where the other seven rowers match a target rate; your blade goes out of sync
visually when you drift off it. No score, just visible sync.
*Why:* aesthetically strong and teaches rate discipline gently.
*Risk:* drawing eight animated rowers with no assets is more art than it sounds.

### Tier 3 — interesting, but weaker or more expensive

**15. Head Race — M — built**
Staggered start against several AI boats with different pacing strategies — one goes out
fast and fades, one negative-splits. Teaches race craft.
*Risk:* AI pacing profiles need tuning to feel fair.

**16. Flappy Boat — S**
Power controls vertical position through a gap-filled course. Silly, and the control
mapping is unlike rowing.
*Why lower:* fun for five minutes, encourages spiky power rather than sustained work —
mildly counterproductive.

**17. Two-Tablet Race — L**
Real head-to-head via the laptop as relay.
*Why lower:* needs a second rower and a second tablet. Infrastructure cost is high for a
rare use case.

**18. Fishing — M**
Hold a narrow speed band to reel in a fish; too fast or slow and the line breaks.
*Why lower:* enforces a narrow band, which is not how most sessions should be structured.

**19. Territory Map — L**
Hex map claimed by accumulated distance over weeks.
*Why lower:* needs persistent map state, rendering, and long-horizon payoff. Expensive
for a reward that arrives slowly.

**20. Story Voyage — L**
Multi-session narrative with chapters unlocked by distance.
*Why lower:* the bottleneck is writing and art, not code. Least suited to the constraint
that everything is drawn with paths and text.

---

## Recommended order

Twenty-one games and a Records screen are built; Zombie Run, Row Runner and Boss Fight are
the ones with actual game feel and should be the template for anything further. The next step is not another game: **none of
this has been seen running.** Put it on the tablet, watch a session, and expect layout and
tuning fixes before adding anything. After that, Rhythm Match is worth a feasibility test
and Two-Tablet Race is the only remaining idea with real upside.

Original plan, kept for the record: build **Pace Boat (3)** first — it is the cheapest thing on the list that genuinely makes
you row harder, and it exercises the whole game-screen contract without needing server
work. Then **The Run (2)**, because it is scoring rather than simulation and reuses the
coast curve we already have. Then **Ghost Race (1)** once session persistence exists,
since Pace Boat will already have built most of the rendering.

**Shared infrastructure worth building once:**

- `GameView` base class with `onStatus(Status)` — every game draws itself, none touch the
  protocol.
- A river renderer: scrolling water, a boat, a wake. Ghost Race, Pace Boat, Head Race,
  Collector and Storm all need it.
- Session recording and PB storage on the laptop server.
- Home screen with game cards showing personal bests.

**Design rule carried over from the gauges:** the stat on screen must move with the stroke.
A number that updates once a second and sits flat between updates gives no reason to pull
harder. See the "decay IS the instrument" note in `CLAUDE.md`.
