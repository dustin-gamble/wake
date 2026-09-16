# Testing WAKE 3.19.5 on the rower

Everything below was rowed on a tablet-sized emulator with a simulated rower and checked from
screenshots. It has **not** run on the tablet yet, so the things most likely to differ are speed
(smoothness on the tablet's older hardware) and anything that depends on your real stroke.

**While you test:** use the **📷 button** on any screen that looks wrong or feels slow. It lands on
the laptop. Leave WAKE with **Exit**, and restart the tablet before going back to Ergatta.

## 1. Install (2 min)

- [ ] Dashboard → **Install APK** → **3.19.5**.
- [ ] Home shows 21 cards, the week ring, level bar and streak, with no scrolling.

## 2. The big one: Coast Flight wings

You asked for wings that flap during the stroke's drive and glide on the recovery.

- [ ] Take 3 strokes to take off.
- [ ] In flight, do the wings **beat while you pull** and **hold still, raised, while you slide
      forward**? (Measured on the emulator: about a 1 s beat per stroke, then a glide.)
- [ ] Does the beat start with your leg drive, or noticeably late? Tell me which.

## 3. Games that changed the most (a minute or two each)

For each: **does it look busy and alive, and is anything hard to read or slow?**

- [ ] **Head Race / Regatta / Race** — crowd on the bank (cheers when you lead), buoys, 250 m
      boards, a finish line that sails in, splashes on each stroke, "PASSED" callouts, confetti.
- [ ] **Collector** — blue sky, reeds, spinning coins / gems / stars, catch bursts, **combo x5 = +5**,
      ducks and jumping fish. Scores run a little higher than before because of the combo bonus.
- [ ] **Tug of War** — two teams of three heaving on each stroke, a mud pit, losers fall in.
- [ ] **Mega Pull** — fairground: tents, bulb strings, spotlights, crowd jumps when the bell rings.
- [ ] **Canyon (FLY)** — sunset (it used to render dark grey), a little plane, clouds, birds, rings
      you fly through, streak counter.
- [ ] **Canyon (DRIVE)** — tap MODE. Mesas, vultures, rock layers, boulders, dust behind the craft.
      At your fastest the craft now stays on screen against the wall instead of vanishing.
- [ ] **Rocket Launch** — puffy clouds, smoke trail, launch tower, the booster tumbling away at a
      stage change, a jet and a weather balloon on the way up, the moon and satellites in space.
- [ ] **Skyline / Night Grid / River Explorer** — moving skies: clouds, a plane, searchlights and
      shooting stars (Skyline at night), a lit train and fireflies (Night Grid), a balloon (River).
- [ ] **Row Runner** — pixel mountains, a castle, pixel birds and an airship behind the run.
- [ ] **Zombie Run** — the runner and zombies have faces now.
- [ ] **Daily Row** — progress ring and medal; a win stamps DAY WON with confetti, a miss shows how
      close it came with a dull medal.
- [ ] **Stroke Coach** — a small rower in the bottom-left that moves with your drive and recovery.
- [ ] **Crew Boat** — the sync % and caption now sit on dark pills so they read on the sky.

## 3b. What changed in 3.19.6 (from your own tablet screenshots)

You said "more movement, things on the screen, visual animation for rowing". These are the answers:

- [ ] **Oars that row** — in **Race**, **Head Race**, **Regatta** and **Collector** the boats now have
      riggers, shafts and blades that sweep from catch to finish **off your real drive**, leave puddles
      where the blade works, and feather on the recovery. The rower leans back through the drive.
      Does the sweep line up with your own stroke, or does it lag?
- [ ] **Wave Rider** — chop running down the face, sun glitter, churn at the base, spray off the rail,
      a carve trail behind the board. The face used to be one flat triangle.
- [ ] **Row Runner** — the underground is soil layers with buried rocks, roots, coins and fossils, and
      grass tufts along the surface.
- [ ] **Night Grid** — a wind farm whose blades turn with your stroke rate, a road with a car, and an
      aurora once the town is fully lit.
- [ ] **Stroke Coach** — a properly sized rower that slides with your drive and recovery, and a live
      paddle trace filling what used to be an empty column.
- [ ] **Zombie Run** — bigger figures and a torch beam lighting the road ahead.

## 4. Quick checks

- [ ] **Records** — Crew Boat's fastest 1000 m shows as a time (e.g. 4:45), not seconds.
- [ ] **Shuffle** — switches games with the clock and needle carrying over.
- [ ] **Gauges / Zone Row** — unchanged; they should look exactly as before.

## Known and left alone

- **Wave Rider** still ends the ride if you row well above the wave's pace for a while ("too far
  ahead"). That is its design (it punishes both ends), and you tuned it, so it was not changed.
- **Canyon DRIVE without the handle sensor** steers by speed, and your top speed puts you against the
  right wall. Say if sprinting there feels unfair and I will set the band from your rowing profile.

## What to tell me

For each screen: **works / looks wrong / feels slow**, plus a screenshot.
