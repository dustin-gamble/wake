package com.codex.waterrowerdiagnostic;

import android.content.Context;

/**
 * RACE: one card for every kind of opponent on the river.
 *
 * <ul>
 *   <li><b>RIVALS</b> (default) - three crews with personalities, all rowing off your recent average
 *       so the field stays close however you feel today. SHADOWS sit on your stern and find more
 *       when you lead (the old single rival). FLYERS go out hard and fade. CLOSERS sit back and kick
 *       for the last quarter. Each is tuned so a steady row at your average only just beats it, so
 *       the race is decided by where you push. They talk: passes, splits, the sprint, a lane stolen.</li>
 *   <li><b>BEST+LAST</b> - your best and your last recording at this distance in one race (and a
 *       friend's, when one has been imported). When the last run was the best, one boat.</li>
 *   <li><b>PACE</b> - a boat at a fixed split, defaulting to two seconds faster than your typical.</li>
 *   <li><b>BEST</b> - a recording of your fastest run at this distance.</li>
 *   <li><b>LAST</b> - a recording of your most recent finished run at this distance.</li>
 *   <li><b>FRIEND</b> - someone else's recording, imported through the laptop dashboard.</li>
 * </ul>
 *
 * <p>Replaced separate Pace Boat and Ghost Race cards, which were the same game with the same
 * complaint. Recordings are one number per second under {@code ghost.<m>} (best) and
 * {@code ghostlast.<m>} (last); the best time stays {@code time.<m>}. Recorded metres are race
 * metres, so they include whatever the lane's water gave that day.
 */
final class GhostRaceGame extends PaceBoatGame {

    enum Opponent {
        ADAPTIVE("RIVALS"), GHOSTS("BEST+LAST"), PACE("PACE"), BEST("BEST"), LAST("LAST"), FRIEND("FRIEND");

        final String label;

        Opponent(String label) {
            this.label = label;
        }
    }

    private static final int KIND_PACE = 0;
    private static final int KIND_GHOST = 1;
    private static final int KIND_RIVAL = 2;

    private static final int SHADOWS = 0;
    private static final int FLYERS = 1;
    private static final int CLOSERS = 2;
    private static final String[] RIVAL_NAMES = {"SHADOWS", "FLYERS", "CLOSERS"};
    private static final String[] RIVAL_TAGS = {"SHADOWS  ·  ON YOUR STERN", "FLYERS  ·  OUT HARD",
            "CLOSERS  ·  SAVING IT"};
    private static final int[] RIVAL_COLORS = {0xFFB48CFF, 0xFFF0655D, 0xFFF5C518};
    /** Lines per rival, indexed by the SAY_* events in PaceBoatGame. */
    private static final String[][] RIVAL_LINES = {
            {"WE'LL BE RIGHT HERE.", "TOLD YOU. RIGHT HERE.", "NOT FOR LONG.", "YOU CAN'T SHAKE A SHADOW.",
                    "STILL ON YOUR STERN.", "HEY - THAT WAS OUR WATER!", "WE MATCH WHATEVER YOU'VE GOT.",
                    "CLOSE. NOT CLOSE ENOUGH.", "GOOD ROW. WE'LL BE BACK."},
            {"CATCH US IF YOU CAN!", "SEE YA!", "WE'RE... JUST... PACING.", "WHO MOVED THE FINISH?!",
                    "LOOK AT THAT SPLIT!", "OI! OUR LANE!", "IS THIS A RACE OR A PICNIC?",
                    "FIRST OFF THE LINE, FIRST OVER IT.", "WENT OUT TOO HARD... AGAIN."},
            {"WAKE US AT 250.", "RIGHT ON SCHEDULE.", "ENJOY IT WHILE IT LASTS.", "HERE WE COME!",
                    "PATIENCE.", "TAKE IT. WE DON'T NEED IT.", "TICK. TOCK.",
                    "ALWAYS SAVE SOMETHING.", "YOU LEFT US NOTHING. RESPECT."},
    };
    /** Rival stroke rates against your typical: the flyers rate high, the closers low until the kick. */
    private static final float[] RIVAL_RATE = {0f, 3f, -1f};

    private Opponent opponent = Opponent.ADAPTIVE;
    private boolean paceChosen;

    // The field.
    private int crews = 1;
    private final int[] kind = new int[MAX_CREWS];
    private final float[][] ghost = new float[MAX_CREWS][];     // metres at second 0, 1, 2 ...
    private final double[] ghostFinish = new double[MAX_CREWS];
    private final String[] name = new String[MAX_CREWS];
    private final String[] tag = new String[MAX_CREWS];
    private final int[] color = new int[MAX_CREWS];
    private final int[] rival = new int[MAX_CREWS];

    private final StringBuilder recording = new StringBuilder();
    private int recordedSeconds = -1;

    // The rivals.
    private final double[] rivalMeters = new double[MAX_CREWS];
    private final double[] rivalSpeed = new double[MAX_CREWS];
    private double yourAverage;
    private double lastTickTime;

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
        loadCrews();
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
        loadCrews();
    }

    /** Builds the field for the chosen opponent. A missing recording falls back to the pace boat. */
    private void loadCrews() {
        crews = 0;
        switch (opponent) {
            case ADAPTIVE:
                for (int r = 0; r < RIVAL_NAMES.length && crews < MAX_CREWS; r++) {
                    kind[crews] = KIND_RIVAL;
                    rival[crews] = r;
                    name[crews] = RIVAL_NAMES[r];
                    tag[crews] = RIVAL_TAGS[r];
                    color[crews] = RIVAL_COLORS[r];
                    crews++;
                }
                break;
            case GHOSTS: {
                String best = bests.getString("ghost." + raceMeters);
                String last = bests.getString("ghostlast." + raceMeters);
                boolean same = best != null && best.equals(last);
                addGhost(best, bests.get("time." + raceMeters, 0f), "YOUR BEST", same ? "YOUR BEST (= LAST)" : "YOUR BEST",
                        0xFFF5C518);
                if (!same) {
                    addGhost(last, bests.get("timelast." + raceMeters, 0f), "YOUR LAST", "YOUR LAST", BLUE);
                }
                addGhost(bests.getString("ghostfriend." + raceMeters), bests.get("timefriend." + raceMeters, 0f),
                        friendName(), friendName(), 0xFFB48CFF);
                if (crews == 0) {
                    addPace("NO RECORDINGS YET - PACE BOAT " + PersonalBests.formatPace(targetPace()));
                }
                break;
            }
            case BEST:
                if (!addGhost(bests.getString("ghost." + raceMeters), bests.get("time." + raceMeters, 0f),
                        "YOUR BEST", "YOUR BEST", 0xFFF5C518)) {
                    addPace("NO BEST YET - THIS RUN SETS IT");
                }
                break;
            case LAST:
                if (!addGhost(bests.getString("ghostlast." + raceMeters), bests.get("timelast." + raceMeters, 0f),
                        "YOUR LAST", "YOUR LAST RACE", BLUE)) {
                    addPace("NO LAST RACE YET - PACE BOAT " + PersonalBests.formatPace(targetPace()));
                }
                break;
            case FRIEND:
                if (!addGhost(bests.getString("ghostfriend." + raceMeters), bests.get("timefriend." + raceMeters, 0f),
                        friendName(), friendName(), 0xFFB48CFF)) {
                    addPace("NO FRIEND'S RACE AT " + raceMeters + " m - IMPORT ONE");
                }
                break;
            default:
                addPace(null);
                break;
        }
    }

    private void addPace(String label) {
        kind[crews] = KIND_PACE;
        ghost[crews] = null;
        name[crews] = "PACE BOAT";
        tag[crews] = label;     // null: the live pace label from PaceBoatGame
        color[crews] = BLUE;
        crews++;
    }

    private boolean addGhost(String saved, float time, String shortName, String label, int col) {
        if (crews >= MAX_CREWS || saved == null || saved.isEmpty()) {
            return false;
        }
        String[] parts = saved.split(",");
        if (parts.length <= 2) {
            return false;
        }
        float[] g = new float[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                g[i] = Float.parseFloat(parts[i]);
            }
        } catch (NumberFormatException e) {
            return false;
        }
        kind[crews] = KIND_GHOST;
        ghost[crews] = g;
        ghostFinish[crews] = time > 0 ? time : g.length;
        name[crews] = shortName;
        tag[crews] = label + "  " + PersonalBests.formatTime((float) ghostFinish[crews]);
        color[crews] = col;
        crews++;
        return true;
    }

    private String friendName() {
        String n = bests.getString("friend.name." + raceMeters);
        return n == null ? "YOUR FRIEND" : n.toUpperCase(java.util.Locale.US);
    }

    /** The best recording at this distance, for sharing: one sample a second plus the distance. */
    String bestRecording() {
        return bests.getString("ghost." + raceMeters);
    }

    float bestTime() {
        return bests.get("time." + raceMeters, 0f);
    }

    /** Stores a friend's recording for this distance and races it. */
    void importFriend(String friend, int meters, float time, String samples) {
        bests.putString("ghostfriend." + meters, samples);
        bests.putFloat("timefriend." + meters, time);
        bests.putString("friend.name." + meters, friend);
        opponent = Opponent.FRIEND;
        setRaceMeters(meters);
        start();
    }

    boolean hasGhost() {
        for (int i = 0; i < crews; i++) {
            if (kind[i] == KIND_GHOST) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void onStart() {
        // The LAST (and maybe BEST) recording changed if a race just finished: rebuild the field first,
        // because PaceBoatGame lays the lanes out from crewCount().
        loadCrews();
        super.onStart();
        recording.setLength(0);
        recordedSeconds = -1;
        yourAverage = profile.typicalSpeed();
        lastTickTime = 0;
        for (int i = 0; i < MAX_CREWS; i++) {
            rivalMeters[i] = 0;
            rivalSpeed[i] = profile.typicalSpeed();
        }
    }

    /* ---------- the crews ---------- */

    @Override
    protected int crewCount() {
        return crews;
    }

    private static double ghostAt(float[] g, double t) {
        int i = (int) t;
        if (i >= g.length - 1) {
            return g[g.length - 1];
        }
        return g[i] + (g[i + 1] - g[i]) * (float) (t - i);
    }

    @Override
    protected double crewMeters(int i) {
        switch (kind[i]) {
            case KIND_GHOST:
                return ghostAt(ghost[i], raceTime());
            case KIND_RIVAL:
                return rivalMeters[i];
            default:
                return paceBoatMeters();
        }
    }

    @Override
    protected float crewSpeed(int i) {
        if (state != State.RACING) {
            return 0f;
        }
        switch (kind[i]) {
            case KIND_GHOST: {
                int s = (int) raceTime();
                float[] g = ghost[i];
                return s >= g.length - 1 ? 0f : g[s + 1] - g[s];
            }
            case KIND_RIVAL:
                return (float) rivalSpeed[i];
            default:
                return super.crewSpeed(i);
        }
    }

    @Override
    protected String crewName(int i) {
        return name[i];
    }

    @Override
    protected String crewLabel(int i) {
        return tag[i] != null ? tag[i] : super.crewLabel(i);
    }

    @Override
    protected int crewColor(int i) {
        return color[i];
    }

    @Override
    protected boolean crewIsRecording(int i) {
        return kind[i] == KIND_GHOST;
    }

    @Override
    protected float crewStrokeRate(int i) {
        float base = (float) profile.typicalRate();
        if (kind[i] != KIND_RIVAL) {
            return base;
        }
        float r = base + RIVAL_RATE[rival[i]];
        if (rival[i] == CLOSERS && raceMeters - crewDistance(i) < kickMetres()) {
            r = base + 5f;
        }
        return r;
    }

    @Override
    protected double crewFinishTime(int i) {
        return kind[i] == KIND_GHOST ? ghostFinish[i] : super.crewFinishTime(i);
    }

    @Override
    protected String taunt(int i, int event) {
        if (kind[i] != KIND_RIVAL || event < 0 || event >= RIVAL_LINES[rival[i]].length) {
            return null;
        }
        return RIVAL_LINES[rival[i]][event];
    }

    @Override
    protected String rightHudValue() {
        if (crews == 1 && kind[0] == KIND_PACE) {
            return super.rightHudValue();
        }
        int lead = leadingCrew();
        if (kind[lead] == KIND_GHOST) {
            return PersonalBests.formatTime((float) ghostFinish[lead]);
        }
        return pace(crewSpeed(lead));
    }

    @Override
    protected String rightHudCaption() {
        if (crews == 1 && kind[0] == KIND_PACE) {
            return super.rightHudCaption();
        }
        int lead = leadingCrew();
        return name[lead] + (kind[lead] == KIND_GHOST ? " FINISH" : " /500");
    }

    @Override
    protected String winText() {
        switch (opponent) {
            case ADAPTIVE:
                return "YOU BEAT ALL THREE CREWS";
            case GHOSTS:
                return hasGhost() ? "YOU BEAT EVERY RECORDING" : "FIRST RECORDING SET";
            case BEST:
                return hasGhost() ? "YOU BEAT YOUR BEST" : "BEST RECORDED";
            case LAST:
                return hasGhost() ? "YOU BEAT YOUR LAST RACE" : "YOU BEAT THE PACE BOAT";
            case FRIEND:
                return hasGhost() ? "YOU BEAT " + friendName() : super.winText();
            default:
                return super.winText();
        }
    }

    @Override
    protected String loseText() {
        switch (opponent) {
            case ADAPTIVE:
                return "THE RIVALS GOT YOU";
            case GHOSTS:
                return hasGhost() ? "A RECORDING GOT THERE FIRST" : super.loseText();
            case BEST:
                return hasGhost() ? "YOUR BEST WON" : super.loseText();
            case LAST:
                return hasGhost() ? "YOUR LAST RACE WON" : super.loseText();
            case FRIEND:
                return hasGhost() ? friendName() + " WON" : super.loseText();
            default:
                return super.loseText();
        }
    }

    /* ---------- the race ---------- */

    /** The closers' kick: the last quarter, never shorter than the sprint. */
    private double kickMetres() {
        return Math.max(250.0, raceMeters * 0.25);
    }

    /**
     * The speed a rival wants now, as a multiple of your recent average. FLYERS and CLOSERS are solved
     * so that their whole race takes 0.3-0.4% longer than yours at a dead-steady average: you win if
     * you hold your pace, and lose if you let them have the part of the race they are built for.
     */
    private double rivalWanted(int i, double youMeters) {
        double base = yourAverage;
        double d = crewDistance(i);
        switch (rival[i]) {
            case FLYERS: {
                double fast = 0.35 * raceMeters;
                double slow = 0.65 * raceMeters / (1.003 * raceMeters - fast / 1.07);
                return d < fast ? Math.max(base, profile.typicalSpeed()) * 1.07 : base * slow;
            }
            case CLOSERS: {
                double kick = kickMetres();
                double cruise = (raceMeters - kick) / (1.004 * raceMeters - kick / 1.08);
                return raceMeters - d > kick ? base * cruise : Math.max(base * 1.08, profile.highSpeed() * 1.01);
            }
            default: {
                // SHADOWS, the original rival: 10 m ahead of it and it finds 6% more, 10 m behind and it
                // gives 2% away.
                double gap = youMeters - d;
                double nudge = Math.max(-0.06, Math.min(0.08, 0.02 + 0.004 * gap));
                return base * (1 + nudge);
            }
        }
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
        if (dt <= 0 || dt >= 0.5) {
            return;
        }
        // Your average over roughly the last 20 s: what every rival rows off.
        yourAverage += (boat.value() - yourAverage) * Math.min(1.0, dt / 20.0);
        for (int i = 0; i < crews; i++) {
            if (kind[i] != KIND_RIVAL) {
                continue;
            }
            double wanted = rivalWanted(i, meters);
            // The closers' kick comes on quickly enough to see; the others change pace gently.
            double settle = rival[i] == CLOSERS ? 1.5 : rival[i] == FLYERS ? 2.0 : 3.0;
            rivalSpeed[i] += (wanted - rivalSpeed[i]) * Math.min(1.0, dt / settle);
            rivalMeters[i] += rivalSpeed[i] * dt;
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
