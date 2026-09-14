package com.codex.waterrowerdiagnostic;

import android.content.Context;

/**
 * RACE: one card for every kind of opponent on the river.
 *
 * <ul>
 *   <li><b>RIVAL</b> (default) - adapts to you. It sits at your recent average speed, pulls away a
 *       little when you get ahead and eases when you fall behind, so the race stays close however
 *       you feel today. This is the fix for "we pull ahead and it never catches up".</li>
 *   <li><b>PACE</b> - a boat at a fixed split, defaulting to two seconds faster than your typical.</li>
 *   <li><b>BEST</b> - a recording of your fastest run at this distance.</li>
 *   <li><b>LAST</b> - a recording of your most recent finished run at this distance.</li>
 * </ul>
 *
 * <p>Replaced separate Pace Boat and Ghost Race cards, which were the same game with the same
 * complaint. Recordings are one number per second under {@code ghost.<m>} (best) and
 * {@code ghostlast.<m>} (last); the best time stays {@code time.<m>}.
 */
final class GhostRaceGame extends PaceBoatGame {

    enum Opponent {
        ADAPTIVE("RIVAL"), PACE("PACE"), BEST("BEST"), LAST("LAST");

        final String label;

        Opponent(String label) {
            this.label = label;
        }
    }

    private Opponent opponent = Opponent.ADAPTIVE;
    private boolean paceChosen;

    private float[] ghost;          // metres at second 0, 1, 2 ...
    private double ghostFinish;
    private final StringBuilder recording = new StringBuilder();
    private int recordedSeconds = -1;

    // the adaptive rival
    private double rivalMeters;
    private double rivalSpeed;
    private double yourAverage;
    private double lastTickTime;
    private double rivalFinish = -1;

    GhostRaceGame(Context context, PersonalBests bests) {
        super(context, bests);
        setRaceMeters(1000);
    }

    Opponent opponent() {
        return opponent;
    }

    void nextOpponent() {
        Opponent[] all = Opponent.values();
        opponent = all[(opponent.ordinal() + 1) % all.length];
        loadGhost();
        start();
    }

    /** The rower picked a pace from the chip; stop following the profile. */
    void choosePace(float secondsPer500) {
        paceChosen = true;
        setTargetPace(secondsPer500);
    }

    @Override
    void setProfile(RowerProfile p) {
        super.setProfile(p);
        if (!paceChosen) {
            setTargetPace(Math.round(profile.typicalSplit()) - 2);
        }
    }

    @Override
    void setRaceMeters(int meters) {
        super.setRaceMeters(meters);
        loadGhost();
    }

    private void loadGhost() {
        ghost = null;
        if (opponent != Opponent.BEST && opponent != Opponent.LAST) {
            return;
        }
        boolean last = opponent == Opponent.LAST;
        String saved = bests.getString((last ? "ghostlast." : "ghost.") + raceMeters);
        if (saved == null || saved.isEmpty()) {
            return;
        }
        String[] parts = saved.split(",");
        float[] g = new float[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                g[i] = Float.parseFloat(parts[i]);
            }
        } catch (NumberFormatException e) {
            return;
        }
        ghost = g;
        ghostFinish = last ? bests.get("timelast." + raceMeters, g.length)
                : bests.get("time." + raceMeters, g.length);
    }

    boolean hasGhost() {
        return ghost != null && ghost.length > 2;
    }

    @Override
    protected void onStart() {
        super.onStart();
        recording.setLength(0);
        recordedSeconds = -1;
        rivalMeters = 0;
        rivalSpeed = profile.typicalSpeed();
        yourAverage = profile.typicalSpeed();
        lastTickTime = 0;
        rivalFinish = -1;
    }

    @Override
    protected double opponentDistance() {
        if (hasGhost()) {
            double t = raceTime();
            int i = (int) t;
            if (i >= ghost.length - 1) {
                return ghost[ghost.length - 1];
            }
            float frac = (float) (t - i);
            return ghost[i] + (ghost[i + 1] - ghost[i]) * frac;
        }
        if (opponent == Opponent.ADAPTIVE) {
            return rivalMeters;
        }
        return super.opponentDistance();
    }

    @Override
    protected float opponentSpeed() {
        if (state != State.RACING) {
            return 0f;
        }
        if (hasGhost()) {
            int i = (int) raceTime();
            return i >= ghost.length - 1 ? 0f : ghost[i + 1] - ghost[i];
        }
        if (opponent == Opponent.ADAPTIVE) {
            return (float) rivalSpeed;
        }
        return super.opponentSpeed();
    }

    @Override
    protected String opponentLabel() {
        switch (opponent) {
            case ADAPTIVE:
                return "RIVAL  ·  KEEPS IT CLOSE";
            case BEST:
                return hasGhost() ? "YOUR BEST  " + PersonalBests.formatTime((float) ghostFinish)
                        : "NO BEST YET - THIS RUN SETS IT";
            case LAST:
                return hasGhost() ? "YOUR LAST RACE  " + PersonalBests.formatTime((float) ghostFinish)
                        : "NO LAST RACE YET - " + super.opponentLabel();
            default:
                return super.opponentLabel();
        }
    }

    @Override
    protected String winText() {
        switch (opponent) {
            case ADAPTIVE:
                return "YOU BEAT THE RIVAL";
            case BEST:
                return hasGhost() ? "YOU BEAT YOUR BEST" : "BEST RECORDED";
            case LAST:
                return hasGhost() ? "YOU BEAT YOUR LAST RACE" : "YOU BEAT THE PACE BOAT";
            default:
                return super.winText();
        }
    }

    @Override
    protected String loseText() {
        switch (opponent) {
            case ADAPTIVE:
                return "THE RIVAL WON";
            case BEST:
                return "YOUR BEST WON";
            case LAST:
                return hasGhost() ? "YOUR LAST RACE WON" : super.loseText();
            default:
                return super.loseText();
        }
    }

    @Override
    protected double opponentFinishTime() {
        if (hasGhost()) {
            return ghostFinish;
        }
        if (opponent == Opponent.ADAPTIVE) {
            if (rivalFinish >= 0) {
                return rivalFinish;
            }
            return finishTime + (raceMeters - rivalMeters) / Math.max(0.5, rivalSpeed);
        }
        return super.opponentFinishTime();
    }

    @Override
    protected void onRaceTick(double time, double meters) {
        int sec = (int) time;
        if (sec > recordedSeconds) {
            // One sample per second, first sample at t=0 is always 0 m.
            while (recordedSeconds < sec) {
                recordedSeconds++;
                if (recording.length() > 0) {
                    recording.append(',');
                }
                recording.append(Math.round(meters));
            }
        }
        double dt = time - lastTickTime;
        lastTickTime = time;
        if (opponent == Opponent.ADAPTIVE && dt > 0 && dt < 0.5) {
            // Your average over roughly the last 20 s, then a nudge from the gap: 10 m ahead and
            // the rival finds 6% more, 10 m behind and it gives 2% away.
            yourAverage += (boat.value() - yourAverage) * Math.min(1.0, dt / 20.0);
            double gap = meters - rivalMeters;
            double nudge = Math.max(-0.06, Math.min(0.08, 0.02 + 0.004 * gap));
            double wanted = yourAverage * (1 + nudge);
            rivalSpeed += (wanted - rivalSpeed) * Math.min(1.0, dt / 3.0);
            rivalMeters += rivalSpeed * dt;
            if (rivalMeters >= raceMeters && rivalFinish < 0) {
                rivalFinish = time;
            }
        }
    }

    @Override
    protected void onRaceFinished(double time, boolean won) {
        recording.append(',').append(raceMeters);
        String run = recording.toString();
        // Every finished race becomes LAST; a new best (or the first run) also becomes BEST.
        bests.putString("ghostlast." + raceMeters, run);
        bests.putFloat("timelast." + raceMeters, (float) time);
        if (newBest || bests.getString("ghost." + raceMeters) == null) {
            bests.putString("ghost." + raceMeters, run);
            bests.recordLowest("time." + raceMeters, (float) time);
        }
    }
}
