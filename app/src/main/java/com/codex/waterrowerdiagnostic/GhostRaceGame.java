package com.codex.waterrowerdiagnostic;

import android.content.Context;

/**
 * Ghost Race: race a recording of your own best run over the same distance.
 *
 * <p>The ghost is a list of metres-at-each-second from your fastest previous race, stored on the
 * tablet. Until one exists you race a pace boat instead, and that first completed run becomes the
 * ghost. Every race that sets a new best replaces it.
 */
final class GhostRaceGame extends PaceBoatGame {

    private float[] ghost;          // metres at second 0, 1, 2 ...
    private double ghostFinish;
    private final StringBuilder recording = new StringBuilder();
    private int recordedSeconds = -1;

    GhostRaceGame(Context context, PersonalBests bests) {
        super(context, bests);
        setRaceMeters(2000);
    }

    @Override
    void setRaceMeters(int meters) {
        super.setRaceMeters(meters);
        loadGhost();
    }

    private void loadGhost() {
        ghost = null;
        String saved = bests.getString("ghost." + raceMeters);
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
        ghostFinish = bests.get("time." + raceMeters, g.length);
    }

    boolean hasGhost() {
        return ghost != null && ghost.length > 2;
    }

    @Override
    protected void onStart() {
        super.onStart();
        recording.setLength(0);
        recordedSeconds = -1;
    }

    @Override
    protected double opponentDistance() {
        if (!hasGhost()) {
            return super.opponentDistance();
        }
        double t = raceTime();
        int i = (int) t;
        if (i >= ghost.length - 1) {
            return ghost[ghost.length - 1];
        }
        float frac = (float) (t - i);
        return ghost[i] + (ghost[i + 1] - ghost[i]) * frac;
    }

    @Override
    protected float opponentSpeed() {
        if (!hasGhost()) {
            return super.opponentSpeed();
        }
        double t = raceTime();
        int i = (int) t;
        if (i >= ghost.length - 1 || state != State.RACING) {
            return 0f;
        }
        return ghost[i + 1] - ghost[i];
    }

    @Override
    protected String opponentLabel() {
        return hasGhost()
                ? "YOUR GHOST  " + PersonalBests.formatTime((float) ghostFinish)
                : "NO GHOST YET - THIS RUN BECOMES IT";
    }

    @Override
    protected String winText() {
        return hasGhost() ? "YOU BEAT YOUR GHOST" : "GHOST RECORDED";
    }

    @Override
    protected String loseText() {
        return "YOUR GHOST WON";
    }

    @Override
    protected double opponentFinishTime() {
        return hasGhost() ? ghostFinish : super.opponentFinishTime();
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
    }

    @Override
    protected void onRaceFinished(double time, boolean won) {
        // A new best time (or the very first run) becomes the ghost for next time.
        if (newBest || !hasGhost()) {
            recording.append(',').append(raceMeters);
            bests.putString("ghost." + raceMeters, recording.toString());
            bests.recordLowest("time." + raceMeters, (float) time);
        }
    }
}
