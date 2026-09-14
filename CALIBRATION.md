# Calibrating WAKE's energy measurement

WAKE measures the work you do from the paddle itself. The monitor sends a count of paddle pulses
every 25 ms, which is the paddle's speed 40 times a second. The water is a flywheel with drag, so
speed over time gives power:

```
power = I × ω × (c × ω² + dω/dt)
```

- **ω** is the paddle's speed in pulses per second.
- **c** is how hard the water drags on the paddle. WAKE measures it itself: every recovery, when
  you're not pulling, the paddle coasts, and the rate it slows down *is* the drag.
- **I** is the paddle's inertia, the one number WAKE can't see.

Until you calibrate, WAKE scales **I** so its power matches the monitor's own readings. The monitor
uses WaterRower's formula. A load scale and a tape measure replace that formula with your own
measurements.

Calories are **mechanical work ÷ 25%**, the typical efficiency of human muscle, excluding resting
metabolism.

## You'll need

- A **hanging load scale** (luggage or crane scale), ideally one that reads steadily while you pull.
- A **tape measure**.
- About **10 minutes**.

Open WAKE and tap **CALIBRATE** at the top of the home screen. Work down the steps on the left.

## 1. Boat distance (automatic)

**Row about 200 m at any pace.** WAKE counts pulses against the monitor's metres. When it's done
you'll see *pulses per metre*, and *pulses per paddle turn* worked out from your earlier count of
0.65 turns per metre.

## 2. Water drag (automatic)

**Row ten normal strokes.** Every recovery is measured. The step ticks when 10 coasts are in.
Drag is saved, so later sessions start calibrated.

## 3. Handle travel

1. Sit with the handle **at the catch** (strap fully wound in).
2. Lay the tape measure along the strap's path.
3. Set **PULL DISTANCE** to what you'll pull. 100 cm is a good choice.
4. **Pull slowly and steadily** to that mark, then **let go**.
5. Repeat **3–5 times**. Each pull shows its pulse count.
6. When the spread is under about 8%, tap **SAVE HANDLE**.

WAKE works out where your pull ended from the physics, so the paddle spinning on after you let go
isn't counted. On a simulated steady pull this measured within 2%. **Pull evenly, not fast.**

## 4. Force

1. **Hook the load scale between the handle and your hands.** Make sure it's secure. A scale that
   slips can hit you.
2. **Pull at an even speed** so the scale reading settles, then let go.
3. Set **SCALE READ** to the steady reading (use **USE LB** if your scale reads pounds) and tap
   **ADD PULL**.
4. Do **three or more pulls at different speeds**: gentle, medium, firm.
5. Check the line under the table: *"force follows speed squared"* should be **90% or more**.
   Water drag rises with the square of speed, so this confirms the readings are consistent.
6. Tap **SAVE FORCE**.

Why this works: during a steady pull the paddle isn't speeding up, so all the power you put in
goes into the water. Force × handle speed = drag power, which gives the inertia directly.

**Read the steady value, not the peak.** If your scale has peak-hold, turn it off, or pull very
smoothly so peak and steady are the same.

## Results

The **RESULTS** step shows your last stroke:

| Figure | What it means |
|---|---|
| **Measured watts vs monitor watts** | How far WaterRower's formula is from your own measurement |
| **Joules** | Work done in that stroke |
| **Peak handle force** | In kg, from the power and the handle speed |
| **Drive length** | Handle travel during the drive, in cm |
| **Drive : recovery** | Coaches aim for about 1 : 2 |
| **Session work and kcal** | Since WAKE opened |
| **kcal per hour** | At the power of that stroke |

The gauge screen's **KCAL** tile shows "~" until the force step is saved, and ZONE ROW's calories
read **MEASURED** after it.

Every saved step is sent to the laptop dashboard as a `calibration` event, so the numbers can be
checked later.

## Starting again

- **START OVER** clears the pulls on the current step.
- **CLEAR FORCE CALIBRATION** on the results step goes back to matching the monitor.
