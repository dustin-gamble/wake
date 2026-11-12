package com.codex.waterrowerdiagnostic;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;

/**
 * Tug of War: your power against a computer team whose strength ramps. The rope marker moves
 * toward whoever is pulling harder; drag it past your line to win, let it cross theirs and lose.
 *
 * <p>Direct and physical. The opponent starts weak and grows, so the first minute is winnable and
 * the question is how long you can hold it off.
 *
 * <p>3.19.4 (the emulator screenshot was a line and a dot): an evening field with a crowd, a mud pit
 * under the middle of the rope, two teams of three leaning back and heaving on every stroke, dust
 * kicked up by whoever is losing ground, and the losing team tumbling into the mud.
 *
 * <p>Upgrades the rower approved:
 * <ul>
 * <li><b>Teams with personalities.</b> Each level is a named team that pulls its own way - one
 * takes breathers you can punish, one pulls on a beat, one sneaks in a yank, one grinds and tires,
 * one winds up and heaves. Their current move is called out, so there is always something coming in
 * the next ten seconds.</li>
 * <li><b>Anchor.</b> Hold a steady rate and your anchor digs in: while anchored you slip at a
 * quarter of the speed when they out-pull you. It only stops slipping; it never gains ground.</li>
 * <li><b>A crowd that gets louder</b> as the lead grows: banners go up, noise rings, chants.</li>
 * <li><b>A tournament.</b> An eight-team knockout bracket, toggled with the button top right.</li>
 * <li><b>Mud splashes</b> from the feet of whichever team is sliding into the pit.</li>
 * </ul>
 *
 * <p>3.23.0, the second set the rower approved:
 * <ul>
 * <li><b>A league with promotion and relegation.</b> Three divisions of four sides, a season of
 * three matchdays, three points a win. Win the division and you go up, finish bottom and you go
 * down; a division above pulls 18% harder. The table, the other tie's result and the season verdict
 * are all on screen. State lives in {@link TugOfWarLeague}.</li>
 * <li><b>Anchor rounds.</b> Mid-pull the referee calls one: a hold line is pegged at wherever the
 * marker is and you have thirty seconds to not let it slip a hand's width. Hold it and they tire;
 * lose it and they surge. Something at stake, on a clock, every minute or so.</li>
 * <li><b>Rivals who taunt, and a comeback.</b> Every team has its own lines and gloats when it is
 * ahead; a side that has beaten you twice more than you have beaten it taunts harder. Being
 * behind arms the comeback meter - out-pull them from back there and it fills, then you get eight
 * seconds at +35%, which is enough to take a pull back from almost anywhere.</li>
 * <li><b>Best of three, with a rest between.</b> A match is first to two pulls. Between them the
 * losing side picks itself out of the mud and there is a twenty second breather with the score up,
 * the rival talking, and the next pull counting down.</li>
 * <li><b>A strength rating that grows across sessions.</b> Time on the rope, pulls, matches, anchor
 * rounds and comebacks all feed it; it carries a rank from ROOKIE to TITAN and is worth up to 10%
 * on the rope, against divisions that are 18% apart - so it opens the way up rather than
 * flattening the game.</li>
 * </ul>
 *
 * <p>3.25.0 graphics pass (nothing about play changed): a mown pitch that recedes to a vanishing
 * point, painted touch, centre and win lines with the anchor round's HOLD/BREAK on the grass, a
 * shaded mud pit, a setting sun in a valley of hazy hills, stars and lit clouds, a near-side crowd
 * behind a pitch-side board that carries the stats, pullers with jointed limbs, boots that dig in
 * on the heave and shadows under them, a rope with twist and highlight that sags slack and snaps
 * straight, and HUD panels and bars in the app's family. Size-only geometry is built once in
 * {@code buildScene}.
 */
final class TugOfWarGame extends GameView {

    private enum Phase { READY, PULLING, REST, WON, LOST }

    /* ---------- the teams ---------- */

    private static final int LAZY = 0;       // takes breathers
    private static final int RHYTHM = 1;     // pulls on a beat
    private static final int SNEAKY = 2;     // sudden yanks with little warning
    private static final int GRINDER = 3;    // builds, then tires
    private static final int HEAVER = 4;     // telegraphed wind-up and heave
    private static final int SLOWSTART = 5;  // weak at first, strong late
    private static final int FRONTRUN = 6;   // strong at first, fades

    private static final int HAT_FLOWER = 0;
    private static final int HAT_CAP = 1;
    private static final int HAT_BANDANA = 2;
    private static final int HAT_HORNS = 3;
    private static final int HAT_HEADBAND = 4;
    private static final int HAT_BEANIE = 5;
    private static final int HAT_HAIR = -1;

    private static final class Team {
        final String name;
        final String motto;
        /** Base strength as a share of the rower's own typical watts. */
        final float share;
        final int style;
        final int shirt;
        final int hat;

        Team(String name, String motto, float share, int style, int shirt, int hat) {
            this.name = name;
            this.motto = motto;
            this.share = share;
            this.style = style;
            this.shirt = shirt;
            this.hat = hat;
        }
    }

    /**
     * Teams 0-4 are levels 1-5. The shares are the old level shares, so each level's average pull
     * is unchanged; the personality moves it around that average. 5 and 6 only appear in the cup.
     */
    private static final Team[] TEAMS = {
            new Team("DAISY CHAIN", "they take breathers - punish them", 0.75f, LAZY, 0xFFE8C547, HAT_FLOWER),
            new Team("MILL LADS", "they pull on the beat", 0.88f, RHYTHM, 0xFF7A8FA6, HAT_CAP),
            new Team("RIVER RATS", "they sneak in a yank", 1.0f, SNEAKY, 0xFF9A72C8, HAT_BANDANA),
            new Team("IRON OXEN", "they grind, then tire", 1.1f, GRINDER, 0xFFB0663E, HAT_HORNS),
            new Team("STORM CREW", "they wind up and heave", 1.22f, HEAVER, BAD, HAT_HEADBAND),
            new Team("BARN OWLS", "slow to wake, strong late", 0.84f, SLOWSTART, 0xFFB89B6A, HAT_BEANIE),
            new Team("HARBOUR SEALS", "fast out of the blocks", 0.96f, FRONTRUN, 0xFF4F8CC0, HAT_CAP),
    };
    private static final String[] BRACE_CALLS = {
            "WINDING UP - BRACE  1", "WINDING UP - BRACE  2", "WINDING UP - BRACE  3"};
    static final String[] LEVEL_NAMES = {"EASY", "STEADY", "EVEN", "STRONG", "BRUTAL"};

    /** What each side shouts when it is ahead, in the order of the style constants above. */
    private static final String[][] TAUNTS = {
            {"IS THAT IT?", "WAKE US WHEN YOU PULL", "WE'RE HAVING A SIT DOWN"},          // LAZY
            {"IN  TIME,  LADS", "ONE... TWO... GONE", "TICK. TOCK."},                     // RHYTHM
            {"DIDN'T SEE THAT, DID YOU?", "SNOOZE, LOSE", "MIND THE ROPE"},               // SNEAKY
            {"WE DO THIS ALL DAY", "SLOW AND SURE", "YOU'LL TIRE FIRST"},                 // GRINDER
            {"BRACE YOURSELF", "HERE IT COMES", "TIMBERRR"},                              // HEAVER
            {"WE'RE ONLY WARMING UP", "WAIT FOR IT", "THE LATE SHIFT STARTS NOW"},        // SLOWSTART
            {"TOO SLOW!", "OFF LIKE A SHOT", "CATCH US IF YOU CAN"},                      // FRONTRUN
    };
    /** Reserved for a side that has beaten you twice more than you have beaten it. */
    private static final String[] NEMESIS_TAUNTS = {
            "AGAIN? YOU NEVER LEARN", "SAME ROPE, SAME ENDING", "WE OWN YOU"};

    /* ---------- the team table, for the league ---------- */

    static int teamCount() {
        return TEAMS.length;
    }

    static String teamName(int i) {
        return TEAMS[Math.max(0, Math.min(TEAMS.length - 1, i))].name;
    }

    static float teamShare(int i) {
        return TEAMS[Math.max(0, Math.min(TEAMS.length - 1, i))].share;
    }

    /* ---------- the cup ---------- */

    private static final int YOU = -1;
    private static final int NONE = -2;
    private static final String[] ROUND_NAMES = {"QUARTER-FINAL", "SEMI-FINAL", "FINAL"};
    private static final String CUP_KEY = "tugcup.wins";
    private final int[] cupQuarter = new int[8];
    private final int[] cupSemi = new int[4];
    private final int[] cupFinal = new int[2];
    private int cupChampion = NONE;
    private int cupRound;
    private boolean cupOut;
    private final java.util.Random cupRng = new java.util.Random();

    /* ---------- state ---------- */

    private final PersonalBests bests;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RiverScenery scenery;
    private final Fx.Particles fx = new Fx.Particles();
    private final android.graphics.Path rope = new android.graphics.Path();
    private final RectF tmp = new RectF();
    private final RectF modeButton = new RectF();
    private android.graphics.LinearGradient skyShader;

    /* ---------- the scene, built once per view size (3.25.0 graphics pass) ---------- */

    private final android.graphics.Path ropeSpan = new android.graphics.Path();
    private final android.graphics.Path shape = new android.graphics.Path();
    private final android.graphics.Path stripePath = new android.graphics.Path();
    private final android.graphics.Path bandPath = new android.graphics.Path();
    private final android.graphics.Path hillFar = new android.graphics.Path();
    private final android.graphics.Path hillNear = new android.graphics.Path();
    private android.graphics.LinearGradient groundShader;
    private android.graphics.RadialGradient pitShader;
    private android.graphics.RadialGradient vignetteShader;
    private android.graphics.DashPathEffect twist;
    private float builtW;
    private float builtH;
    /** The pitch's perspective: vanishing point, and the screen row the pullers stand on. */
    private float vpX;
    private float vpY;
    private float groundRow;
    /** Output of {@link #joint}, so limbs are solved without allocating. */
    private float jx;
    private float jy;
    /** HUD panel gradients, keyed by their top and bottom, so a fixed panel never rebuilds one. */
    private final android.graphics.LinearGradient[] panelShaders = new android.graphics.LinearGradient[12];
    private final float[] panelTop = new float[12];
    private final float[] panelBottom = new float[12];
    private int panelNext;
    /** Text widths measured as they are drawn, for the backing panels on the next frame. */
    private float leftBlockW = 300f;
    private float rightBlockW = 160f;
    private float gaugeSubW = 150f;
    private float callW = 260f;

    private static final int OUTLINE = 0xFF161B24;
    private static final int TROUSERS = 0xFF2A2F3A;
    private static final int SKIN_SHADE = 0xFFC9975C;
    private static final int[] SPECTATORS = {0xFF243042, 0xFF3A2C3E, 0xFF2E3A2C, 0xFF40322A, 0xFF1E2A3A, 0xFF35343C};
    private static final int[] HAIR = {0xFF16120F, 0xFF2B1D14, 0xFF4A3220, 0xFF1C1C22, 0xFF5E4630};
    private Phase lastPhase = Phase.READY;
    private double endedAt;
    private float heave;
    private int lastStrokes = -1;

    /* ---------- league, best-of-three, anchor rounds, rivals, rating ---------- */

    private static final int MODE_MATCH = 0;
    private static final int MODE_LEAGUE = 1;
    private static final int MODE_CUP = 2;
    private static final String[] MODE_NAMES = {"SINGLE MATCH", "LEAGUE", "TOURNAMENT"};
    /** Pre-lowered, because the button is drawn every frame it is up. */
    private static final String[] MODE_NEXT = {"tap for league", "tap for tournament", "tap for single match"};
    /** Pulls needed to take a match, and the breather between them. */
    private static final int PULLS_TO_WIN = 2;
    private static final double REST_SECONDS = 20.0;
    /** An anchor round is thirty seconds, and the line breaks if the marker slips this far. */
    private static final double ROUND_SECONDS = 30.0;
    /**
     * How far the marker may slip before the line breaks, as a share of half the field. A heave
     * from the STORM CREW is +42% for about three seconds, which costs 0.34 unanchored and 0.085
     * with the anchor dug in - so this is set above one unanchored heave and the anchor is what
     * makes a round comfortable rather than a coin toss.
     */
    private static final double ROUND_SLACK = 0.30;

    private final TugOfWarLeague league;
    private int mode = MODE_LEAGUE;

    /** Best of three. */
    private int yourPulls;
    private int theirPulls;
    private int pullResult;         // the last pull: +1 you, -1 them, 0 none
    private double matchSeconds;    // runs across the whole match, so a team can tire in the third pull
    private double restSeconds;

    /** Anchor round: hold the line for thirty seconds. */
    private boolean roundActive;
    private double roundEndsAt;
    private double nextRoundAt;
    private double roundLine;
    private float roundFlash;
    private boolean roundFlashGood;
    private boolean roundBurstPending;
    private int roundsThisMatch;

    /** A fatigue or surge the opponent is carrying out of an anchor round. */
    private float themEventMul = 1f;
    private double themEventUntil;

    /** Comeback: arms when you are well behind, fills while you out-pull them, then pays out. */
    private float comeback;
    private float comebackBoost;
    private boolean comebackUsed;
    private float comebackFlash;
    private boolean comebackBurstPending;
    private boolean tauntedThisPull;

    /** What the rival is shouting right now. */
    private String taunt;
    private float tauntLife;
    private double nextTauntAt;
    private int tauntSeed;

    /** Rating is awarded a point for every ten seconds on the rope. */
    private float ratingTick;

    private int level = 2;          // 1 easy .. 5 brutal
    private int matchTeam = 1;
    private Phase phase = Phase.READY;
    private double position;        // -1 = they win, +1 = you win
    private double pullSeconds;
    /** Marker velocity, eased: + means they are sliding toward the pit, - means you are. */
    private float slide;
    private float themMul = 1f;
    private String teamCall;
    private boolean teamCallUrgent;

    /** Anchor: 0..1, builds while the stroke rate holds steady. */
    private float anchor;
    private boolean anchorSteady;
    private float rateSpread = 99f;
    private boolean anchorBurstDone;
    private final float[] rateVal = new float[48];
    private final double[] rateAt = new double[48];
    private int rateNext;
    private double lastRateSample = -1;

    /** How loud each half of the crowd is, eased. */
    private float yourLoud;
    private float theirLoud;
    private final float[] chantX = new float[12];
    private final float[] chantY = new float[12];
    private final float[] chantAge = new float[12];
    private final int[] chantWord = new int[12];
    private final boolean[] chantYours = new boolean[12];
    private int chantNext;
    private static final String[] YOUR_CHANTS = {"PULL!", "HEAVE!", "DIG IN!", "GO GO GO", "YES!"};
    private static final String[] THEIR_CHANTS = {"HEAVE HO!", "DRAG 'EM!", "BOO!", "PULL!"};

    /** Mud splats left on the pit by a sliding team's feet. */
    private final float[] splatX = new float[28];
    private final float[] splatY = new float[28];
    private final float[] splatR = new float[28];
    private final float[] splatLife = new float[28];
    private int splatNext;

    TugOfWarGame(Context context, PersonalBests bests) {
        super(context);
        this.bests = bests;
        this.league = new TugOfWarLeague(bests);
        this.scenery = new RiverScenery(getResources().getDisplayMetrics().density);
        for (int i = 0; i < chantAge.length; i++) {
            chantAge[i] = 99f;
        }
        newCup();
    }

    private boolean cup() {
        return mode == MODE_CUP;
    }

    private boolean leagueMode() {
        return mode == MODE_LEAGUE;
    }

    /** Picking a level from the header chip is a single match, so it leaves the cup and the league. */
    void setLevel(int l) {
        level = Math.max(1, Math.min(5, l));
        mode = MODE_MATCH;
        phase = Phase.READY;
    }

    int level() {
        return level;
    }

    /** The team on the rope this match - fixed at the start, so a cup win does not swap the losers mid-fall. */
    private Team team() {
        return TEAMS[matchTeam];
    }

    private int opponentIndex() {
        if (leagueMode()) {
            return league.opponentTeam();
        }
        if (!cup()) {
            return level - 1;
        }
        if (cupRound == 0) {
            return cupQuarter[1];
        }
        if (cupRound == 1) {
            return cupSemi[1];
        }
        return cupFinal[1];
    }

    @Override
    protected void onStart() {
        if (cup() && (cupOut || cupChampion != NONE)) {
            newCup();
        }
        resetMatch();
        // sessionSeconds restarts at zero, so old rate samples would look like the future.
        for (int i = 0; i < rateAt.length; i++) {
            rateAt[i] = 0;
        }
        lastRateSample = -1;
        rateSpread = 99f;
        anchorSteady = false;
    }

    /** A whole new match: new opponent, best of three back to 0-0. */
    private void resetMatch() {
        matchTeam = opponentIndex();
        phase = Phase.READY;
        yourPulls = 0;
        theirPulls = 0;
        pullResult = 0;
        matchSeconds = 0;
        restSeconds = 0;
        roundsThisMatch = 0;
        league.clearSeasonMessage();
        taunt = null;
        tauntLife = 0;
        nextTauntAt = 12;
        resetPull();
    }

    /** One pull of the best of three. The match score and the match clock survive it. */
    private void resetPull() {
        position = 0;
        pullSeconds = 0;
        slide = 0;
        anchor = 0;
        anchorBurstDone = false;
        themMul = 1f;
        teamCall = null;
        roundActive = false;
        // The first call has to land before the marker has run past the +/-0.7 gate below, or the
        // round is never called at all. A pull is scaled so a 40 W edge covers the field in 12 s,
        // and the easiest league fixture (0.75 share x 0.88 division = 93 W against this rower's
        // 129 W typical) is a ~36 W edge - the marker is at 0.68 by 11 s and past the gate by 12 s.
        // 18 s meant that fixture never saw a round. 10 s leaves room in every fixture measured.
        nextRoundAt = 10;
        roundEndsAt = 0;
        themEventMul = 1f;
        themEventUntil = 0;
        comeback = 0;
        comebackBoost = 0;
        comebackUsed = false;
        tauntedThisPull = false;
        nextTauntAt = 12;
    }

    @Override
    protected void onStop() {
        league.save();
    }

    @Override
    protected void onStatusChanged(S4Protocol.Status s) {
        if (phase == Phase.READY && driving && boat.value() > 0.3f) {
            phase = Phase.PULLING;
            pullSeconds = 0;
            position = 0;
        }
        sampleRate(s);
    }

    /**
     * Keeps ~10 s of stroke-rate readings, one every quarter second, and measures their spread.
     * The interval-timed rate holds between strokes and moves by a sixth of a change per stroke,
     * but the counter is read about once a second, so a perfectly even 25 spm still wobbles by
     * about a stroke a minute each way: 2.5 spm of spread is "steady" on this link.
     */
    private void sampleRate(S4Protocol.Status s) {
        double now = sessionSeconds;
        if (now - lastRateSample < 0.25) {
            return;
        }
        lastRateSample = now;
        float r = (float) (s.strokeRatePrecise > 0 ? s.strokeRatePrecise : s.strokeRate);
        rateVal[rateNext] = r;
        rateAt[rateNext] = now;
        rateNext = (rateNext + 1) % rateVal.length;
        float lo = Float.MAX_VALUE;
        float hi = -Float.MAX_VALUE;
        double oldest = now;
        int n = 0;
        for (int i = 0; i < rateVal.length; i++) {
            if (rateAt[i] <= 0 || now - rateAt[i] > 8.0) {
                continue;
            }
            lo = Math.min(lo, rateVal[i]);
            hi = Math.max(hi, rateVal[i]);
            oldest = Math.min(oldest, rateAt[i]);
            n++;
        }
        rateSpread = n > 0 ? hi - lo : 99f;
        // Not `driving`: that flag needs the speed register to have changed in the last 1.2 s, and it
        // changes only every ~2.7 s while rowing, so it drops out mid-piece and the anchor could
        // never finish filling. stillRowing stays true through a steady piece. The floor is a
        // share of the rower's own typical rate, not a fixed number.
        float floor = (float) Math.max(12.0, profile.typicalRate() * 0.65);
        anchorSteady = n >= 8 && now - oldest >= 6.0 && lo >= floor && rateSpread <= 2.5f && s.stillRowing;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() != MotionEvent.ACTION_DOWN) {
            return super.onTouchEvent(event);
        }
        if (phase != Phase.PULLING && modeButton.contains(event.getX(), event.getY())) {
            mode = (mode + 1) % MODE_NAMES.length;
            if (cup()) {
                newCup();
            }
            start();
            return true;
        }
        if (phase == Phase.REST) {
            startNextPull();           // skip the breather
            return true;
        }
        if (phase == Phase.WON || phase == Phase.LOST) {
            if (cup() && !cupOut && cupChampion == NONE) {
                resetMatch();          // on to the next round
            } else if (leagueMode()) {
                resetMatch();          // on to the next fixture
            } else {
                start();
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

    /* ---------- best of three ---------- */

    /** A pull has been taken. Scores it, then either ends the match or calls a breather. */
    private void endPull(boolean youWon) {
        pullResult = youWon ? 1 : -1;
        // A pull ending ends whatever was live on it. Neither is stepped outside PULLING, so left
        // set they freeze: the anchor round panel would sit through the twenty second breather with
        // a stopped countdown and its HOLD/BREAK lines still pegged to the rope, and the comeback
        // would keep multiplying the watts readout and the MARGIN stat by 1.35 while nobody pulls.
        roundActive = false;
        comebackBoost = 0f;
        if (youWon) {
            yourPulls++;
        } else {
            theirPulls++;
        }
        league.award(youWon ? 6f * team().share : 1.5f);
        if (yourPulls >= PULLS_TO_WIN || theirPulls >= PULLS_TO_WIN) {
            boolean won = yourPulls >= PULLS_TO_WIN;
            phase = won ? Phase.WON : Phase.LOST;
            endedAt = sessionSeconds;
            finishMatch(won);
        } else {
            phase = Phase.REST;
            restSeconds = 0;
            if (!youWon) {
                speak(tauntLine());
            }
        }
    }

    private void finishMatch(boolean won) {
        league.award(won ? 18f * team().share : 4f);
        if (!won) {
            speak(tauntLine());
        }
        if (leagueMode()) {
            league.recordMatch(won, yourPulls, theirPulls);
        } else if (cup()) {
            league.noteRival(matchTeam, won);
            if (won) {
                cupWon();
            } else {
                cupLost();
            }
            league.save();
        } else {
            league.noteRival(matchTeam, won);
            if (won) {
                bests.recordHighest("tug." + level, (float) matchSeconds);
            }
            league.save();
        }
    }

    private void startNextPull() {
        resetPull();
        phase = Phase.PULLING;
        pullResult = 0;
    }

    /* ---------- personalities ---------- */

    /**
     * How hard the team is pulling right now, as a multiple of its base share, and what it is
     * doing ({@link #teamCall}). Every pattern averages close to 1 over a minute, so a level's
     * difficulty is unchanged - only where the danger lands moves.
     */
    private float teamMultiplier(Team t, double s) {
        teamCall = null;
        teamCallUrgent = false;
        switch (t.style) {
            case LAZY: {
                double p = s % 14.0;
                if (s > 8 && p >= 10 && p < 13) {
                    teamCall = "THEY'RE BLOWING - PUNISH IT";
                    return 0.62f;
                }
                if (s > 8 && p >= 8.5 && p < 10) {
                    teamCall = "THEY'RE FLAGGING";
                    return 0.95f;
                }
                return 1.09f;
            }
            case RHYTHM: {
                float beat = (float) Math.sin(s * Math.PI * 2 / 2.4);
                if (beat > 0.6f) {
                    teamCall = "HEAVE - HO - HEAVE";
                }
                return 1f + 0.14f * beat;
            }
            case SNEAKY: {
                double p = s % 11.0;
                if (s > 9 && p >= 9.3 && p < 10) {
                    teamCall = "THEY'RE UP TO SOMETHING";
                    teamCallUrgent = true;
                    return 0.94f;
                }
                if (s > 9 && (p >= 10 || p < 0.6)) {
                    teamCall = "YANK!";
                    teamCallUrgent = true;
                    return 1.4f;
                }
                return 0.95f;
            }
            case GRINDER: {
                float build = 0.88f + 0.2f * (float) Math.min(1.0, s / 90.0);
                if (s > 150) {
                    teamCall = "THE OXEN ARE TIRING";
                    return build * (float) Math.max(0.78, 1.0 - (s - 150) / 300.0);
                }
                if (s > 60) {
                    teamCall = "GRINDING";
                }
                return build;
            }
            case HEAVER: {
                double p = s % 9.0;
                if (s > 4 && p >= 4 && p < 7) {
                    teamCall = BRACE_CALLS[Math.max(0, Math.min(2, (int) Math.ceil(7 - p) - 1))];
                    teamCallUrgent = true;
                    return 0.88f;
                }
                if (s > 4 && p >= 7) {
                    teamCall = "HEAVE!";
                    teamCallUrgent = true;
                    return 1.42f;
                }
                return 0.93f;
            }
            case SLOWSTART: {
                if (s < 45) {
                    teamCall = "STILL WAKING UP - GO NOW";
                }
                return 0.72f + 0.38f * (float) Math.min(1.0, s / 60.0);
            }
            case FRONTRUN:
            default: {
                if (s < 20) {
                    teamCall = "FAST START - HOLD ON";
                    teamCallUrgent = true;
                }
                return 1.28f - 0.4f * (float) Math.min(1.0, s / 40.0);
            }
        }
    }

    /**
     * Opponent watts: the team's share of YOUR typical power, scaled by the division, shaped by how
     * it pulls, carrying any fatigue or surge from the last anchor round, and ramping through the
     * pull so a stalemate always breaks.
     */
    private float opponentWatts() {
        float share = team().share * (leagueMode() ? league.divisionStrength() : 1f);
        float base = (float) profile.typicalWatts() * share;
        return base * themMul * themEventMul + (float) pullSeconds * 0.25f + (float) matchSeconds * 0.06f;
    }

    /**
     * What you are actually putting on the rope: the reading, plus what the strength rating is
     * worth (up to 10%), times the comeback boost while it is running.
     */
    private float yourWatts(int watts) {
        return watts * (1f + league.rankBonus()) * (comebackBoost > 0 ? 1.35f : 1f);
    }

    /* ---------- anchor rounds, comebacks, taunts ---------- */

    /**
     * The referee's anchor round: a hold line pegged at wherever the marker is, thirty seconds to
     * not let it slip a hand's width. Holding it leaves them blowing; losing it lets them surge.
     */
    private void stepAnchorRound(float dt) {
        if (!roundActive) {
            if (pullSeconds > nextRoundAt && Math.abs(position) < 0.7) {
                roundActive = true;
                roundLine = position;
                roundEndsAt = pullSeconds + ROUND_SECONDS;
                roundsThisMatch++;
            }
            return;
        }
        if (position < roundLine - ROUND_SLACK) {
            roundActive = false;
            nextRoundAt = pullSeconds + 45;
            roundFlash = 1f;
            roundFlashGood = false;
            roundBurstPending = true;
            themEventMul = 1.10f;                 // they smell blood
            themEventUntil = matchSeconds + 10;
            speak(tauntLine());
        } else if (pullSeconds >= roundEndsAt) {
            roundActive = false;
            nextRoundAt = pullSeconds + 55;
            roundFlash = 1f;
            roundFlashGood = true;
            roundBurstPending = true;
            themEventMul = 0.86f;                 // thirty seconds of nothing has cost them
            themEventUntil = matchSeconds + 15;
            position = Math.min(1.0, position + 0.06);
            league.award(10f);
            league.noteAnchorHeld();
        }
    }

    /**
     * The comeback: being well behind arms it, out-pulling them from back there fills it, and it
     * pays out eight seconds at +35% - enough to take a pull back from almost anywhere.
     */
    private void stepComeback(float dt, float yours, float them) {
        comebackBoost = Math.max(0f, comebackBoost - dt);
        if (comebackUsed) {
            comeback = Math.max(0f, comeback - dt * 0.35f);
            return;
        }
        if (position < -0.22) {
            comeback += dt * (yours > them ? 0.14f : -0.1f);
            comeback = Math.max(0f, Math.min(1f, comeback));
            if (comeback >= 1f) {
                comebackUsed = true;
                comebackBoost = 8f;
                comebackFlash = 1f;
                comebackBurstPending = true;
                // Worth more when they have been mouthing off about it.
                league.award(tauntedThisPull ? 14f : 8f);
                taunt = null;
                tauntLife = 0f;
            }
        } else {
            comeback = Math.max(0f, comeback - dt * 0.15f);
        }
    }

    private void stepTaunts(float dt) {
        tauntLife = Math.max(0f, tauntLife - dt);
        if (tauntLife <= 0f) {
            taunt = null;
        }
        if (phase == Phase.PULLING && position < -0.3 && pullSeconds > nextTauntAt) {
            speak(tauntLine());
            nextTauntAt = pullSeconds + 14 + Math.random() * 8;
        }
    }

    private void speak(String line) {
        taunt = line;
        tauntLife = 3.8f;
        tauntedThisPull = true;
        tauntSeed++;
    }

    /** A line from this team's own book, or a gloat if they have your number. */
    private String tauntLine() {
        if (league.nemesis(matchTeam) && Math.random() < 0.5) {
            return NEMESIS_TAUNTS[(int) (Math.random() * NEMESIS_TAUNTS.length)];
        }
        String[] lines = TAUNTS[Math.max(0, Math.min(TAUNTS.length - 1, team().style))];
        return lines[(int) (Math.random() * lines.length)];
    }

    /* ---------- the cup ---------- */

    private void newCup() {
        // You against the easiest team; the strongest pair meet in the other half's quarter.
        int[] seed = {YOU, 0, 1, 6, 5, 2, 3, 4};
        System.arraycopy(seed, 0, cupQuarter, 0, 8);
        for (int i = 0; i < 4; i++) {
            cupSemi[i] = NONE;
        }
        cupFinal[0] = NONE;
        cupFinal[1] = NONE;
        cupChampion = NONE;
        cupRound = 0;
        cupOut = false;
        cupRng.setSeed(System.currentTimeMillis());
    }

    /** A match you are not in: the stronger team usually wins. */
    private int aiWinner(int a, int b) {
        float sa = TEAMS[a].share * (0.82f + 0.36f * cupRng.nextFloat());
        float sb = TEAMS[b].share * (0.82f + 0.36f * cupRng.nextFloat());
        return sa >= sb ? a : b;
    }

    private void cupWon() {
        if (cupRound == 0) {
            cupSemi[0] = YOU;
            cupSemi[1] = aiWinner(cupQuarter[2], cupQuarter[3]);
            cupSemi[2] = aiWinner(cupQuarter[4], cupQuarter[5]);
            cupSemi[3] = aiWinner(cupQuarter[6], cupQuarter[7]);
            cupRound = 1;
        } else if (cupRound == 1) {
            cupFinal[0] = YOU;
            cupFinal[1] = aiWinner(cupSemi[2], cupSemi[3]);
            cupRound = 2;
        } else {
            cupChampion = YOU;
            bests.putFloat(CUP_KEY, bests.get(CUP_KEY, 0) + 1);
        }
    }

    /** Knocked out: play the rest of the bracket through so it shows who went on to win. */
    private void cupLost() {
        cupOut = true;
        if (cupRound == 0) {
            cupSemi[0] = cupQuarter[1];
            cupSemi[1] = aiWinner(cupQuarter[2], cupQuarter[3]);
            cupSemi[2] = aiWinner(cupQuarter[4], cupQuarter[5]);
            cupSemi[3] = aiWinner(cupQuarter[6], cupQuarter[7]);
        }
        if (cupRound <= 1) {
            cupFinal[0] = cupRound == 1 ? cupSemi[1] : aiWinner(cupSemi[0], cupSemi[1]);
            cupFinal[1] = aiWinner(cupSemi[2], cupSemi[3]);
            cupChampion = aiWinner(cupFinal[0], cupFinal[1]);
        } else {
            cupChampion = cupFinal[1];
        }
    }

    /* ---------- frame ---------- */

    @Override
    protected void render(Canvas c, float dt) {
        float w = getWidth();
        float h = getHeight();
        if (w == 0 || h == 0) {
            return;
        }
        int watts = status == null ? 0 : status.watts;
        Team team = team();

        float target = 1f;
        if (phase == Phase.PULLING) {
            // The personality runs on the match clock, so the grinder really does tire by the
            // third pull and the slow starter is awake for it.
            target = teamMultiplier(team, matchSeconds);
        } else {
            teamCall = null;
        }
        // Eased so a heave builds over a few frames instead of teleporting the marker.
        themMul += (target - themMul) * Math.min(1f, dt * 4f);
        if (matchSeconds > themEventUntil) {
            themEventMul += (1f - themEventMul) * Math.min(1f, dt * 1.5f);
        }
        float them = opponentWatts();
        float yours = yourWatts(watts);

        if (phase == Phase.PULLING) {
            pullSeconds += dt;
            matchSeconds += dt;
            anchor = anchorSteady ? Math.min(1f, anchor + dt / 4f) : Math.max(0f, anchor - dt / 1.2f);
            stepAnchorRound(dt);
            stepComeback(dt, yours, them);
            stepTaunts(dt);
            // A point of strength for every ten seconds actually spent on the rope.
            ratingTick += dt;
            while (ratingTick >= 10f) {
                ratingTick -= 10f;
                league.award(1f);
            }
            // Marker velocity proportional to the power difference, scaled so a 40 W edge
            // covers the field in about 12 seconds. The anchor only slows your slipping.
            double diff = (yours - them) / 40.0;
            if (diff < 0) {
                diff *= 1.0 - 0.75 * anchor;
            }
            double vel = diff / 12.0;
            position += vel * dt;
            slide += ((float) vel - slide) * Math.min(1f, dt * 3f);
            position = Math.max(-1, Math.min(1, position));
            if (position >= 1) {
                endPull(true);
            } else if (position <= -1) {
                endPull(false);
            }
        } else if (phase == Phase.REST) {
            restSeconds += dt;
            matchSeconds += dt;
            // Both teams walk the rope back to the middle while they get their breath.
            position += (0 - position) * Math.min(1f, dt * 1.2f);
            slide += (0f - slide) * Math.min(1f, dt * 3f);
            anchor = Math.max(0f, anchor - dt);
            stepTaunts(dt);
            if (restSeconds >= REST_SECONDS) {
                startNextPull();
            }
        } else {
            slide += (0f - slide) * Math.min(1f, dt * 3f);
            anchor = Math.max(0f, anchor - dt);
            stepTaunts(dt);
        }
        roundFlash = Math.max(0f, roundFlash - dt * 0.55f);
        comebackFlash = Math.max(0f, comebackFlash - dt * 0.5f);
        // Between cup rounds and between league fixtures: a short breather, then the next opponent
        // steps up by itself, so the rower does not have to leave the handle to tap.
        boolean cupRolls = cup() && phase == Phase.WON && cupChampion == NONE;
        boolean leagueRolls = leagueMode() && (phase == Phase.WON || phase == Phase.LOST)
                && league.seasonMessage() == null;
        if ((cupRolls || leagueRolls) && sessionSeconds - endedAt > 8) {
            resetMatch();
            team = team();
        }

        // Rope across the middle, marker on it.
        float ropeY = h * 0.52f;
        float left = dp(40f);
        float right = w - dp(40f);
        float mid = (left + right) / 2f;
        // The rope only travels a fifth of the width each way, so both teams stay on screen.
        float travel = w * 0.2f;
        float mx = mid + travel * (float) position;
        drawField(c, w, h, ropeY, mid, mx, watts, them, team, dt);
        paint.setStyle(Paint.Style.FILL);
        // Win lines: stakes driven in where the painted lines cross the pullers' row.
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(4f));
        paint.setColor(0x55000000);
        c.drawLine(mid + travel + dp(2f), ropeY + dp(61f), mid + travel + dp(12f), ropeY + dp(70f), paint);
        c.drawLine(mid - travel - dp(2f), ropeY + dp(61f), mid - travel - dp(12f), ropeY + dp(70f), paint);
        paint.setStrokeWidth(dp(3.5f));
        paint.setColor(ACCENT);
        c.drawLine(mid + travel, ropeY - dp(30f), mid + travel, ropeY + dp(60f), paint);
        paint.setColor(BAD);
        c.drawLine(mid - travel, ropeY - dp(30f), mid - travel, ropeY + dp(60f), paint);
        paint.setColor(0xFF2A3648);
        c.drawLine(mid, ropeY - dp(16f), mid, ropeY + dp(16f), paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        // Marker: a flag tied to the middle of the rope.
        paint.setColor(0xFFE0E0E0);
        c.drawRect(mx - dp(1.5f), ropeY - dp(40f), mx + dp(1.5f), ropeY + dp(4f), paint);
        rope.rewind();
        float flap = (float) Math.sin(sessionSeconds * 7) * dp(4f);
        rope.moveTo(mx + dp(1.5f), ropeY - dp(40f));
        rope.lineTo(mx + dp(32f), ropeY - dp(32f) + flap);
        rope.lineTo(mx + dp(1.5f), ropeY - dp(22f));
        rope.close();
        paint.setColor(position >= 0 ? ACCENT : team.shirt);
        c.drawPath(rope, paint);
        fx.draw(c);

        // Backing panels for both readouts, sized from the widths measured last frame, so the
        // captions read against the pitch instead of vanishing into the grass.
        tmp.set(right - dp(10f) - rightBlockW - dp(14f), ropeY - dp(86f), right + dp(4f), ropeY - dp(22f));
        panel(c, tmp, dp(10f), 0xA8);
        boolean eventLine = themEventMul < 0.97f || themEventMul > 1.03f;
        tmp.set(left - dp(4f), ropeY - dp(86f), left + dp(10f) + leftBlockW + dp(14f),
                ropeY - dp(50f) + (eventLine ? dp(50f) : dp(37f)));
        panel(c, tmp, dp(10f), 0xA8);
        // Your side: what is actually on the rope, and the anchor gauge under it.
        String yw = Math.round(yours) + " W";
        bold(c, yw, right - dp(10f), ropeY - dp(50f), 30f, comebackBoost > 0 ? WARN : ACCENT, Paint.Align.RIGHT);
        float rw = textPaint.measureText(yw);
        String mine = "YOU";
        if (comebackBoost > 0) {
            mine = "COMEBACK +35%  ·  " + String.format(java.util.Locale.US, "%.1f s", comebackBoost);
        } else if (league.rankBonus() > 0) {
            mine = league.rank() + "  +" + Math.round(league.rankBonus() * 100) + "%  ·  " + watts + " W RAW";
        }
        label(c, mine, right - dp(10f), ropeY - dp(50f) + dp(16f), 9f,
                comebackBoost > 0 ? WARN : DIM, Paint.Align.RIGHT);
        rightBlockW = Math.max(rw, dimPaint.measureText(mine));
        drawAnchorGauge(c, right - dp(10f), ropeY - dp(170f));
        // Their side: name, how they pull, and what they are doing right now.
        String tw = Math.round(them) + " W";
        bold(c, tw, left + dp(10f), ropeY - dp(50f), 30f, team.shirt, Paint.Align.LEFT);
        float lw = textPaint.measureText(tw);
        String who = leagueMode() ? league.divisionName() + "  ·  MATCHDAY " + (league.matchday() + 1)
                + " / " + TugOfWarLeague.FIXTURES
                : cup() ? ROUND_NAMES[Math.min(2, cupRound)]
                : "LEVEL " + level + " " + LEVEL_NAMES[level - 1];
        float shown = team.share * (leagueMode() ? league.divisionStrength() : 1f);
        String whoLine = team.name + "  ·  " + who + "  ·  " + Math.round(shown * 100) + "% OF YOUR "
                + Math.round(profile.typicalWatts()) + " W";
        label(c, whoLine, left + dp(10f), ropeY - dp(50f) + dp(16f), 9f, DIM, Paint.Align.LEFT);
        lw = Math.max(lw, dimPaint.measureText(whoLine));
        String motto = team.motto;
        int h2hW = league.rivalWins(matchTeam);
        int h2hL = league.rivalLosses(matchTeam);
        if (h2hW + h2hL > 0) {
            motto = motto + "  ·  YOU " + h2hW + " - " + h2hL + (league.nemesis(matchTeam) ? "  ·  NEMESIS" : "");
        }
        label(c, motto, left + dp(10f), ropeY - dp(50f) + dp(29f), 9f,
                league.nemesis(matchTeam) ? BAD : DIM, Paint.Align.LEFT);
        leftBlockW = Math.max(lw, dimPaint.measureText(motto));
        if (themEventMul < 0.97f) {
            label(c, "THEY'RE BLOWING AFTER THAT HOLD", left + dp(10f), ropeY - dp(50f) + dp(42f), 9f,
                    ACCENT, Paint.Align.LEFT);
        } else if (themEventMul > 1.03f) {
            label(c, "THEY SMELL BLOOD", left + dp(10f), ropeY - dp(50f) + dp(42f), 9f, BAD, Paint.Align.LEFT);
        }
        if (teamCall != null) {
            float pulse = teamCallUrgent ? 0.6f + 0.4f * (float) Math.abs(Math.sin(sessionSeconds * 6)) : 1f;
            int a = (int) (255 * pulse);
            // A soft dark pool behind it: the call sits right on the sunlit far grass.
            float px0 = left + dp(10f) + callW * 0.5f;
            c.save();
            c.scale(Math.max(1f, callW / dp(90f)), 1f, px0, ropeY - dp(102f));
            Fx.glow(c, px0, ropeY - dp(102f), dp(56f), 0x90060B12);
            c.restore();
            bold(c, teamCall, left + dp(10f), ropeY - dp(96f), 17f,
                    (a << 24) | ((teamCallUrgent ? WARN : TEXT) & 0x00FFFFFF), Paint.Align.LEFT);
            callW = textPaint.measureText(teamCall);
        }

        boolean showBracket = cup() && phase != Phase.PULLING && phase != Phase.REST;
        boolean showTable = leagueMode() && phase != Phase.PULLING && phase != Phase.REST;
        float titleY = h * 0.22f;
        if (showBracket || showTable) {
            // Clear of the mode button (190 dp + margins) on the right, on any width.
            float bw = Math.max(dp(300f), Math.min(w - dp(430f), dp(820f)));
            float bh = dp(200f);
            float bx = (w - bw) / 2f;
            float by = dp(10f);
            if (showBracket) {
                drawBracket(c, bx, by, bw, bh);
            } else {
                drawLeagueTable(c, bx, by, bw, bh);
            }
            titleY = by + bh + dp(34f);
        }
        // A soft dark pool behind the title, its caption and the score, so they read over the sky.
        c.save();
        c.scale(4.6f, 1f, w / 2f, titleY + dp(12f));
        Fx.glow(c, w / 2f, titleY + dp(12f), dp(72f), 0x90060B12);
        c.restore();
        // Best of three, the anchor round's clock and the comeback meter stack under the title,
        // which moves down when the bracket or the table is up.
        if (!showBracket && !showTable) {
            drawScore(c, w, titleY + dp(52f), team);
        }
        drawAnchorRound(c, w, titleY + dp(118f), mid, travel, ropeY);
        drawComebackMeter(c, w, titleY + dp(186f), team);

        String big;
        String cap;
        int col;
        switch (phase) {
            case READY:
                if (leagueMode()) {
                    big = "MATCHDAY " + (league.matchday() + 1) + "  v  " + team.name;
                    cap = "best of three  ·  " + league.divisionName() + "  ·  take a stroke to start";
                    col = TEXT;
                } else if (cup()) {
                    big = ROUND_NAMES[cupRound] + " v " + team.name;
                    cap = "best of three  ·  take a stroke to start  ·  " + team.motto;
                    col = TEXT;
                } else {
                    big = "GRAB THE ROPE";
                    cap = "best of three  ·  take a stroke to start pulling";
                    col = DIM;
                }
                break;
            case REST: {
                int leftRest = (int) Math.ceil(REST_SECONDS - restSeconds);
                big = "BREATHER  " + Math.max(0, leftRest);
                cap = (pullResult > 0 ? "that pull is yours" : "they took that one")
                        + "  ·  " + (yourPulls + theirPulls + 1) + counted(yourPulls + theirPulls + 1)
                        + " pull next  ·  tap to go now";
                col = pullResult > 0 ? ACCENT : WARN;
                break;
            }
            case WON:
                if (cup() && cupChampion == YOU) {
                    big = "CHAMPIONS!";
                    cap = "final won " + yourPulls + "-" + theirPulls + " in " + clock(matchSeconds)
                            + "  ·  tap for a new cup";
                    if (Math.random() < dt * 20) {
                        fx.spawn((float) Math.random() * w, dp(4f), (float) (Math.random() - 0.5) * dp(60f),
                                dp(30f), 2.5f, dp(3.5f), CONFETTI[(int) (Math.random() * CONFETTI.length)], true);
                    }
                } else if (cup()) {
                    int left8 = (int) Math.ceil(8 - (sessionSeconds - endedAt));
                    big = "THROUGH TO THE " + ROUND_NAMES[cupRound];
                    cap = "won " + yourPulls + "-" + theirPulls + "  ·  next: " + TEAMS[opponentIndex()].name
                            + " in " + Math.max(0, left8) + "  ·  or tap";
                } else if (leagueMode()) {
                    big = "MATCH WON  " + yourPulls + " - " + theirPulls;
                    cap = leagueCaption();
                } else {
                    big = "YOU WON  " + yourPulls + " - " + theirPulls;
                    cap = "held them off for " + clock(matchSeconds) + "  ·  tap to go again";
                }
                col = ACCENT;
                break;
            case LOST:
                if (cup()) {
                    big = "KNOCKED OUT  " + yourPulls + " - " + theirPulls;
                    cap = "out in the " + ROUND_NAMES[cupRound].toLowerCase(java.util.Locale.US)
                            + " to " + team.name + "  ·  tap for a new cup";
                } else if (leagueMode()) {
                    big = "MATCH LOST  " + yourPulls + " - " + theirPulls;
                    cap = leagueCaption();
                } else {
                    big = "PULLED OVER  " + yourPulls + " - " + theirPulls;
                    cap = "lasted " + clock(matchSeconds) + "  ·  tap to go again";
                }
                col = BAD;
                break;
            default:
                big = clock(pullSeconds);
                if (roundActive) {
                    cap = "ANCHOR ROUND - DO NOT LET THE LINE GO";
                    col = WARN;
                } else if (comebackBoost > 0) {
                    cap = "COMEBACK - EVERYTHING YOU HAVE, NOW";
                    col = WARN;
                } else if (yours < them && anchor > 0.8f) {
                    cap = "ANCHORED - THEY CAN BARELY MOVE YOU";
                    col = BLUE;
                } else {
                    cap = yours > them ? "WINNING - KEEP IT UP" : "LOSING GROUND - PULL";
                    col = yours > them ? ACCENT : WARN;
                }
                cap = (yourPulls + theirPulls + 1) + counted(yourPulls + theirPulls + 1) + " PULL  ·  " + cap;
                if (cup()) {
                    cap = ROUND_NAMES[cupRound] + "  ·  " + cap;
                }
        }
        bold(c, big, w / 2f, titleY, phase == Phase.PULLING ? 48f : 30f, col, Paint.Align.CENTER);
        label(c, cap, w / 2f, titleY + dp(20f), 10f, DIM, Paint.Align.CENTER);

        if (phase != Phase.PULLING) {
            drawModeButton(c, w);
        } else {
            modeButton.setEmpty();
        }

        float fy = h - dp(14f);
        float col4 = w / 4f;
        stat(c, col4 * 0.5f, fy, status == null ? "0" : String.valueOf(status.strokeRate), "SPM");
        stat(c, col4 * 1.5f, fy, String.format(java.util.Locale.US, "%+d", Math.round(yours - them)),
                "MARGIN W");
        stat(c, col4 * 2.5f, fy, String.valueOf(Math.round(league.rating())),
                "STRENGTH  ·  " + league.rank());
        if (leagueMode()) {
            stat(c, col4 * 3.5f, fy, (league.yourPlace() + 1) + counted(league.yourPlace() + 1),
                    league.divisionName());
        } else if (cup()) {
            stat(c, col4 * 3.5f, fy, String.valueOf(Math.round(bests.get(CUP_KEY, 0))), "CUPS WON");
        } else {
            stat(c, col4 * 3.5f, fy, bests.has("tug." + level) ? clock(bests.get("tug." + level, 0)) : "--",
                    "BEST HOLD");
        }
        drawStrengthBar(c, w, fy - dp(44f));
    }

    /** "1st", "2nd", "3rd" - the suffix only, so it can be pasted onto a number. */
    private static String counted(int n) {
        switch (n) {
            case 1:
                return "st";
            case 2:
                return "nd";
            case 3:
                return "rd";
            default:
                return "th";
        }
    }

    /** What the league has to say after a match: the season verdict, or the next fixture. */
    private String leagueCaption() {
        String msg = league.seasonMessage();
        if (msg != null) {
            return msg + "  ·  tap to start season " + league.season();
        }
        int leftS = (int) Math.ceil(8 - (sessionSeconds - endedAt));
        return "you sit " + (league.yourPlace() + 1) + counted(league.yourPlace() + 1) + "  ·  next: "
                + TEAMS[opponentIndex()].name + " in " + Math.max(0, leftS) + "  ·  or tap";
    }

    private static final int[] CONFETTI = {0xFF35D0BA, 0xFFF0B132, 0xFF6F8CFF, 0xFFF0655D, 0xFFFFFFFF};

    /**
     * Sky, hills, crowd, a mown pitch in perspective with its markings, the mud pit, the rope with
     * its sag, both teams heaving, and the near-side crowd behind a pitch-side board.
     *
     * <p>3.25.0 graphics pass: the lower half of this screen was the largest flat area in the app -
     * one green with nothing in it. It is now a pitch that recedes: mown stripes converging on a
     * vanishing point above the horizon, painted touch lines, centre line and win lines, the
     * anchor round's HOLD and BREAK pegged on the grass, grass tufts, and a crowd in the foreground.
     * Every gradient and path that depends only on the view size is built once in
     * {@link #buildScene}.
     */
    private void drawField(Canvas c, float w, float h, float ropeY, float mid, float mx, int watts, float them,
                           Team team, float dt) {
        float ground = ropeY + dp(60f);
        float horizon = ropeY - dp(112f);
        float bankTop = horizon - dp(46f);
        float travel = w * 0.2f;
        if (w != builtW || h != builtH) {
            buildScene(w, h, horizon, ground, mid);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFFFFFF); // a shader draws at the paint's alpha
        paint.setShader(skyShader);
        c.drawRect(0, 0, w, horizon, paint);
        paint.setShader(null);
        drawSkyLife(c, w, horizon, bankTop);
        // The low sun, half behind the hills, and the hills themselves in two hazy layers.
        Fx.glow(c, w * 0.5f, bankTop, dp(230f), 0x55FFC080);
        paint.setColor(0xFFFFE2B0);
        c.drawCircle(w * 0.5f, bankTop - dp(2f), dp(26f), paint);
        paint.setColor(0xFF9C6F8E);
        c.drawPath(hillFar, paint);
        paint.setColor(0xFF5E4C70);
        c.drawPath(hillNear, paint);
        Fx.glow(c, w * 0.5f, horizon, dp(160f), 0x66FFB36B);

        // The crowd: each half follows its own team, and gets louder as that team's lead grows.
        float yourTarget = phase == Phase.WON ? 1f : phase == Phase.PULLING ? (float) Math.max(0, Math.min(1, position * 1.6)) : 0f;
        float theirTarget = phase == Phase.LOST ? 1f : phase == Phase.PULLING ? (float) Math.max(0, Math.min(1, -position * 1.6)) : 0f;
        yourLoud += (yourTarget - yourLoud) * Math.min(1f, dt * 2f);
        theirLoud += (theirTarget - theirLoud) * Math.min(1f, dt * 2f);
        float cheer = Math.max(0.2f, Math.max(yourLoud, theirLoud));
        scenery.drawBank(c, w, bankTop, horizon, sessionSeconds * 0.3, dp(20f), sessionSeconds, cheer);
        drawCrowdNoise(c, w, bankTop, horizon, team, dt);

        // The pitch: lit and hazy at the far end, dark and saturated near the viewer, mown in a
        // checker of stripes running away and bands across.
        paint.setColor(0xFFFFFFFF);
        paint.setShader(groundShader);
        c.drawRect(0, horizon, w, h, paint);
        paint.setShader(null);
        paint.setColor(0x1CF6FFD8);
        c.drawPath(stripePath, paint);
        paint.setColor(0x16000000);
        c.drawPath(bandPath, paint);
        // Low sunlight spilling across the far grass.
        c.save();
        c.scale(4.5f, 1f, w * 0.5f, horizon + dp(8f));
        Fx.glow(c, w * 0.5f, horizon + dp(8f), dp(110f), 0x44FFC080);
        c.restore();

        // Painted markings, under the mud.
        float yFar = horizon + dp(16f);
        float yNear = Math.min(ground + dp(118f), h - dp(160f));
        float side = travel + dp(300f);
        paint.setColor(0xB0F2EFE2);
        groundLine(c, mid - side, yFar, yNear, dp(2.4f));
        groundLine(c, mid + side, yFar, yNear, dp(2.4f));
        crossLine(c, mid - side, mid + side, yFar, dp(1.4f));
        crossLine(c, mid - side, mid + side, yNear, dp(2.2f));
        groundLine(c, mid, yFar, yNear, dp(3f));
        // The win lines, painted in the same colours as their stakes on the rope.
        paint.setColor((0xC8 << 24) | (ACCENT & 0x00FFFFFF));
        groundLine(c, mid + travel, yFar, yNear, dp(3.2f));
        paint.setColor((0xC8 << 24) | (BAD & 0x00FFFFFF));
        groundLine(c, mid - travel, yFar, yNear, dp(3.2f));
        if (roundActive) {
            // The anchor round pegged on the grass: a solid HOLD line, a dashed BREAK line.
            float hx = mid + travel * (float) roundLine;
            float kx = mid + travel * (float) (roundLine - ROUND_SLACK);
            paint.setColor((0xE0 << 24) | (BLUE & 0x00FFFFFF));
            groundLine(c, hx, yFar, yNear, dp(3.4f));
            paint.setColor((0xE0 << 24) | (BAD & 0x00FFFFFF));
            int dashes = 14;
            for (int i = 0; i < dashes; i += 2) {
                float a = yFar + (yNear - yFar) * i / dashes;
                float b = yFar + (yNear - yFar) * (i + 1) / dashes;
                groundLine(c, kx, a, b, dp(3.4f));
            }
        }
        drawGrass(c, w, h, mid, ground);

        // Mud pit under the centre line: churned rim, wet dark middle, the sky caught in it.
        float pitHalf = dp(110f);
        c.save();
        c.translate(mid, ground + dp(6f));
        c.scale(1f, 21f / 118f);
        paint.setColor(0xFFFFFFFF);
        paint.setShader(pitShader);
        c.drawCircle(0, 0, dp(118f), paint);
        paint.setShader(null);
        c.restore();
        float sheen = 0.5f + 0.5f * (float) Math.sin(sessionSeconds * 1.3);
        paint.setColor(((int) (40 + 40 * sheen) << 24) | 0xF0A060);
        c.drawOval(mid - dp(52f), ground + dp(1f), mid + dp(40f), ground + dp(10f), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(((int) (50 + 60 * sheen) << 24) | 0xFFE6C8);
        tmp.set(mid - dp(40f), ground + dp(2f), mid + dp(24f), ground + dp(9f));
        c.drawArc(tmp, 200f, 120f, false, paint);
        paint.setStyle(Paint.Style.FILL);
        drawSplats(c, dt);

        // A heave on every stroke, easing off between.
        if (status != null && status.strokes != lastStrokes) {
            if (lastStrokes >= 0 && phase == Phase.PULLING) {
                heave = 1f;
            }
            lastStrokes = status.strokes;
        }
        heave = Math.max(0f, heave - dt * 1.1f);
        // Snap profile: the pull lands hard and lets go slowly, so a stroke reads as a heave
        // rather than a nudge. A flat decay spent three quarters of every stroke at zero.
        float snap = heave * heave * (3f - 2f * heave);
        // Their heave follows their personality: a yank or a heave bends them back just as hard.
        float theirSnap = Math.max(0f, Math.min(1f, (themMul - 1.04f) / 0.3f));
        double typical = Math.max(1.0, profile.typicalWatts());
        float yourLean = 0.35f + 0.35f * (float) Math.min(1.2, watts / typical) + snap * 0.55f
                + 0.10f * (float) Math.abs(Math.sin(sessionSeconds * 2.2)) + anchor * 0.08f;
        float theirLean = 0.35f + 0.35f * (float) Math.min(1.2, them / typical) + theirSnap * 0.45f
                + 0.12f * (float) Math.abs(Math.sin(sessionSeconds * 2.6));

        if (phase != lastPhase) {
            if (phase == Phase.WON || phase == Phase.LOST) {
                endedAt = sessionSeconds;
                fx.burst(phase == Phase.WON ? mid - dp(60f) : mid + dp(60f), ground, 50, dp(220f), 1.2f, dp(4f), 0xFF6E4A2B, true);
            } else if (phase == Phase.REST) {
                // Whoever lost that pull goes down in the mud.
                fx.burst(pullResult > 0 ? mid - dp(60f) : mid + dp(60f), ground, 36, dp(190f), 1.1f,
                        dp(4f), 0xFF6E4A2B, true);
            }
            lastPhase = phase;
        }
        double sinceEnd = sessionSeconds - endedAt;

        // Who is on the floor: the match loser stays down, but between pulls the side that lost that
        // one picks itself up again before the next.
        float yourFall = phase == Phase.LOST ? (float) Math.min(1, sinceEnd * 2) : 0f;
        float theirFall = phase == Phase.WON ? (float) Math.min(1, sinceEnd * 2) : 0f;
        if (phase == Phase.REST) {
            float f = restSeconds < 2 ? (float) Math.min(1, restSeconds * 2)
                    : (float) Math.max(0, 1 - (restSeconds - 2) * 0.8);
            if (pullResult > 0) {
                theirFall = f;
            } else if (pullResult < 0) {
                yourFall = f;
            }
        }

        // Rope: through every fist on each side, sagging between the teams, the free ends lying on
        // the grass behind each anchor. It answers the pull: slack between strokes, snapping taut on
        // the heave, with a shudder running along it. A rope that never moves is what made this the
        // stillest screen measured - 0.92 motion against 4.13 for Collector.
        float yourHands = mx + dp(90f);
        float theirHands = mx - dp(90f);
        float taut = Math.max(snap, theirSnap);
        float slack = 1f - taut;
        float sag = dp(3f) + slack * dp(24f);
        float sagIn = dp(1f) + slack * dp(4.5f);
        float shudder = taut * dp(3.5f) * (float) Math.sin(sessionSeconds * 34);
        float markY = ropeY - taut * dp(2f);
        float yL = ropeY + theirFall * dp(30f);
        float yR = ropeY + yourFall * dp(30f);
        float l0 = theirHands + dp(3f);           // their front fist
        float r0 = yourHands - dp(3f);            // your front fist
        float grip = dp(78f);
        ropeSpan.rewind();
        ropeSpan.moveTo(l0 - 2 * grip, yL);
        ropeSpan.quadTo(l0 - 1.5f * grip, yL + sagIn, l0 - grip, yL);
        ropeSpan.quadTo(l0 - 0.5f * grip, yL + sagIn, l0, yL);
        ropeSpan.quadTo((l0 + mx) / 2f, (yL + markY) / 2f + sag + shudder, mx, markY);
        ropeSpan.quadTo((r0 + mx) / 2f, (yR + markY) / 2f + sag - shudder, r0, yR);
        ropeSpan.quadTo(r0 + 0.5f * grip, yR + sagIn, r0 + grip, yR);
        ropeSpan.quadTo(r0 + 1.5f * grip, yR + sagIn, r0 + 2 * grip, yR);
        // The tails drop from the last fist and lie on the grass.
        float lt = l0 - 2 * grip;
        float rt = r0 + 2 * grip;
        float swing = shudder * 0.6f;
        rope.rewind();
        rope.moveTo(lt, yL);
        rope.quadTo(lt - dp(46f) + swing, yL + dp(6f), lt - dp(70f), ground + dp(2f));
        rope.quadTo(lt - dp(95f), ground + dp(9f), lt - dp(140f), ground + dp(6f));
        rope.moveTo(rt, yR);
        rope.quadTo(rt + dp(46f) + swing, yR + dp(6f), rt + dp(70f), ground + dp(2f));
        rope.quadTo(rt + dp(95f), ground + dp(9f), rt + dp(140f), ground + dp(6f));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        // The span's shadow, thrown toward the viewer on the grass.
        c.save();
        c.translate(dp(6f), ground - ropeY + dp(10f));
        paint.setStrokeWidth(dp(6f));
        paint.setColor(0x33000000);
        c.drawPath(ropeSpan, paint);
        c.restore();
        // Under, body, twist, then a highlight along the top.
        for (int pass = 0; pass < 2; pass++) {
            android.graphics.Path p = pass == 0 ? rope : ropeSpan;
            paint.setStrokeWidth(dp(7.5f));
            paint.setColor(0xFF5E4728);
            c.drawPath(p, paint);
            paint.setStrokeWidth(dp(5.4f));
            paint.setColor(mix(0xFFC2A066, 0xFFDCC28A, taut));
            c.drawPath(p, paint);
            paint.setPathEffect(twist);
            paint.setStrokeCap(Paint.Cap.BUTT);
            paint.setStrokeWidth(dp(5.4f));
            paint.setColor(0x70745A32);
            c.drawPath(p, paint);
            paint.setPathEffect(null);
            paint.setStrokeCap(Paint.Cap.ROUND);
            c.save();
            c.translate(0, -dp(1.4f));
            paint.setStrokeWidth(dp(1.6f));
            paint.setColor(((int) (110 + 90 * taut) << 24) | 0xFFF4D6);
            c.drawPath(p, paint);
            c.restore();
        }
        // Whipped ends: a knot on each tail.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFF8E7244);
        c.drawCircle(lt - dp(140f), ground + dp(6f), dp(4.5f), paint);
        c.drawCircle(rt + dp(140f), ground + dp(6f), dp(4.5f), paint);
        paint.setStrokeCap(Paint.Cap.BUTT);

        // Back pullers first, so each one in front overlaps the one behind it.
        for (int k = 2; k >= 0; k--) {
            float yx = yourHands + dp(27f) + k * dp(78f);
            float tx = theirHands - dp(27f) - k * dp(78f);
            // The last of your three is the anchor: it braces when the rate is steady.
            float brace = k == 2 ? anchor : anchor * 0.4f;
            drawPuller(c, yx, ground, 1, yourLean * (1 - yourFall) - yourFall * 0.9f, ACCENT, k, yourFall,
                    HAT_HAIR, brace, k == 2);
            drawPuller(c, tx, ground, -1, theirLean * (1 - theirFall) - theirFall * 0.9f, team.shirt, k + 3,
                    theirFall, team.hat, 0f, false);
        }
        if (taunt != null && tauntLife > 0f) {
            drawTaunt(c, theirHands - dp(120f), ground - dp(150f), team);
        }
        if (roundBurstPending) {
            roundBurstPending = false;
            fx.burst(mx, ground - dp(12f), roundFlashGood ? 40 : 26, dp(200f), 1.0f, dp(4f),
                    roundFlashGood ? 0xFF6F8CFF : 0xFF4A2F18, true);
        }
        if (comebackBurstPending) {
            comebackBurstPending = false;
            fx.burst(yourHands + dp(80f), ground - dp(40f), 44, dp(240f), 1.4f, dp(3.5f), 0xFFF0B132, true);
        }
        // The anchor digging in: one puff of dirt when it first locks.
        if (anchor >= 1f && !anchorBurstDone) {
            anchorBurstDone = true;
            fx.burst(yourHands + dp(27f) + 2 * dp(78f) + dp(20f), ground, 18, dp(90f), 0.7f, dp(3.5f), 0xCC8A6A48, true);
        } else if (anchor < 0.5f) {
            anchorBurstDone = false;
        }
        // Dust kicked up by the heave itself, under your own team's feet.
        if (phase == Phase.PULLING && snap > 0.45f) {
            for (int k = 0; k < 3; k++) {
                float fx0 = yourHands + dp(27f) + k * dp(78f);
                fx.spawn(fx0 + (float) (Math.random() - 0.5) * dp(26f), ground,
                        (float) (Math.random() - 0.2) * dp(70f), -dp(20f) - (float) Math.random() * dp(40f),
                        0.5f, dp(4f), 0x99C8A878, false);
            }
        }
        // Whoever is being dragged: dust off the grass, and mud once their front feet reach the pit.
        if (phase == Phase.PULLING) {
            boolean youSlip = slide < 0;
            float amount = Math.min(1f, Math.abs(slide) * 10f);
            // Front foot of the sliding team (drawn at 1.5x: the braced foot sits ~24 dp toward the rope).
            float foot = youSlip ? yourHands + dp(27f) - dp(24f) : theirHands - dp(27f) + dp(24f);
            float dir = youSlip ? 1f : -1f;   // away from the pit, the way the mud flies back
            if (Math.random() < 0.3 + amount * 0.4) {
                float fxX = youSlip ? yourHands + dp(10f) : theirHands - dp(10f);
                fx.spawn(fxX + (float) (Math.random() * dp(120f)) * dir, ground,
                        (float) (Math.random() - 0.5) * dp(40f), -dp(30f) - (float) Math.random() * dp(30f),
                        0.8f, dp(5f), 0x88C8A878, false);
            }
            boolean inMud = Math.abs(foot - mid) < pitHalf;
            if (inMud && amount > 0.08f) {
                int n = Math.random() < amount * dt * 40 ? 1 + (int) (amount * 3) : 0;
                for (int i = 0; i < n; i++) {
                    fx.spawn(foot + (float) (Math.random() - 0.5) * dp(14f), ground - dp(2f),
                            dir * dp(40f + (float) Math.random() * 120f) * (0.4f + amount),
                            -dp(90f) - (float) Math.random() * dp(140f) * amount,
                            0.9f, dp(3f + (float) Math.random() * 3f), 0xFF4A2F18, true);
                }
                if (Math.random() < amount * dt * 5) {
                    addSplat(foot + dir * dp(10f + (float) Math.random() * 40f), ground + (float) (Math.random() - 0.3) * dp(12f),
                            dp(4f + (float) Math.random() * 6f));
                }
            }
        }
        fx.step(dt, dp(260f));

        drawMotes(c, w, horizon, ground);
        drawNearCrowd(c, w, h, team);
        // Pitch-side board along the bottom: the stats and the strength bar sit on it.
        tmp.set(-dp(12f), h - dp(88f), w + dp(12f), h + dp(12f));
        panel(c, tmp, 0f, 0xF0);
        paint.setColor(0x40000000);
        paint.setStrokeWidth(dp(1f));
        c.drawLine(w * 0.25f, h - dp(84f), w * 0.25f, h, paint);
        c.drawLine(w * 0.75f, h - dp(84f), w * 0.75f, h, paint);
        paint.setColor(0xFFFFFFFF);
        paint.setShader(vignetteShader);
        c.drawRect(0, 0, w, h, paint);
        paint.setShader(null);
    }

    /**
     * Everything on the field that depends only on the view size: sky, ground and pit gradients,
     * the vignette, the mown stripes and bands, and the two hill silhouettes.
     */
    private void buildScene(float w, float h, float horizon, float ground, float mid) {
        builtW = w;
        builtH = h;
        float bankTop = horizon - dp(46f);
        skyShader = new android.graphics.LinearGradient(0, 0, 0, horizon,
                new int[] {0xFF120A2A, 0xFF35204F, 0xFF9A4F66, 0xFFE58A5A, 0xFFF4B070},
                new float[] {0f, 0.32f, 0.66f, 0.88f, 1f}, android.graphics.Shader.TileMode.CLAMP);
        groundShader = new android.graphics.LinearGradient(0, horizon, 0, h,
                new int[] {0xFF8A9A55, 0xFF5A8A42, 0xFF3C6B33, 0xFF22421D},
                new float[] {0f, 0.12f, 0.45f, 1f}, android.graphics.Shader.TileMode.CLAMP);
        pitShader = new android.graphics.RadialGradient(0, 0, dp(118f),
                new int[] {0xFF2A190D, 0xFF3F2715, 0xFF5A3B22, 0xFF7A5634, 0x007A5634},
                new float[] {0f, 0.4f, 0.72f, 0.9f, 1f}, android.graphics.Shader.TileMode.CLAMP);
        vignetteShader = new android.graphics.RadialGradient(w * 0.5f, h * 0.45f, Math.max(w, h) * 0.72f,
                new int[] {0x00000000, 0x00000000, 0x66000000}, new float[] {0f, 0.6f, 1f},
                android.graphics.Shader.TileMode.CLAMP);
        twist = new android.graphics.DashPathEffect(new float[] {dp(3f), dp(4.5f)}, 0f);

        vpX = mid;
        vpY = horizon - dp(240f);
        groundRow = ground;
        // Stripes run away from the viewer: wedges through the vanishing point, every other one lit.
        float stripe = dp(92f);
        float sTop = persp(horizon);
        float sBot = persp(h);
        int n = (int) Math.ceil(w * 0.5f / (stripe * sTop)) + 1;
        stripePath.rewind();
        for (int i = -n; i < n; i += 2) {
            float a = i * stripe;
            float b = (i + 1) * stripe;
            stripePath.moveTo(vpX + a * sTop, horizon);
            stripePath.lineTo(vpX + b * sTop, horizon);
            stripePath.lineTo(vpX + b * sBot, h);
            stripePath.lineTo(vpX + a * sBot, h);
            stripePath.close();
        }
        // Bands across, closer together toward the horizon.
        bandPath.rewind();
        int bands = 10;
        for (int k = 0; k < bands; k += 2) {
            float y0 = horizon + (h - horizon) * (float) Math.pow(k / (double) bands, 1.6);
            float y1 = horizon + (h - horizon) * (float) Math.pow((k + 1) / (double) bands, 1.6);
            bandPath.addRect(0, y0, w, y1, android.graphics.Path.Direction.CW);
        }
        // Hills: the far range paler and pinker in the haze, the near one darker.
        hillFar.rewind();
        hillFar.moveTo(0, horizon);
        hillNear.rewind();
        hillNear.moveTo(0, horizon);
        float step = dp(24f);
        for (float x = 0; x <= w + step; x += step) {
            double u = x / dp(1f);
            float far = bankTop - dp(6f) - dp(26f) * (float) (0.5 + 0.5 * Math.sin(u * 0.0042 + 0.6))
                    - dp(9f) * (float) Math.sin(u * 0.013 + 1.7);
            float near = bankTop + dp(10f) - dp(16f) * (float) (0.5 + 0.5 * Math.sin(u * 0.0066 + 2.4))
                    - dp(5f) * (float) Math.sin(u * 0.021);
            // A valley in the middle for the sun to set into, clear of the score above it.
            double v = (x - w * 0.5f) / dp(170f);
            float valley = (float) Math.exp(-v * v);
            hillFar.lineTo(x, far + dp(16f) * valley);
            hillNear.lineTo(x, near + dp(13f) * valley);
        }
        hillFar.lineTo(w + step, horizon);
        hillFar.close();
        hillNear.lineTo(w + step, horizon);
        hillNear.close();
    }

    /** How much bigger the world is at screen height {@code y} than on the pullers' row. */
    private float persp(float y) {
        return (y - vpY) / (groundRow - vpY);
    }

    /** A line painted on the grass, running away from the viewer through {@code xAtRow} on the pullers' row. */
    private void groundLine(Canvas c, float xAtRow, float yFar, float yNear, float halfW) {
        float sF = persp(yFar);
        float sN = persp(yNear);
        float xF = vpX + (xAtRow - vpX) * sF;
        float xN = vpX + (xAtRow - vpX) * sN;
        shape.rewind();
        shape.moveTo(xF - halfW * sF, yFar);
        shape.lineTo(xF + halfW * sF, yFar);
        shape.lineTo(xN + halfW * sN, yNear);
        shape.lineTo(xN - halfW * sN, yNear);
        shape.close();
        c.drawPath(shape, paint);
    }

    /** A line painted across the grass at depth {@code y}, between two that run away from the viewer. */
    private void crossLine(Canvas c, float xL, float xR, float y, float halfH) {
        float s = persp(y);
        c.drawRect(vpX + (xL - vpX) * s, y - halfH * s, vpX + (xR - vpX) * s, y + halfH * s, paint);
    }

    /** Stars in the dusk, and long clouds lit from underneath drifting across. */
    private void drawSkyLife(Canvas c, float w, float horizon, float bankTop) {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 26; i++) {
            float sx = (float) ((i * 0.6180339887) % 1.0) * w;
            float sy = (float) ((i * 0.7548776662 + 0.1) % 1.0) * horizon * 0.42f;
            int a = (int) (70 + 60 * Math.sin(sessionSeconds * (1.1 + (i % 5) * 0.3) + i * 2.1));
            paint.setColor((a << 24) | 0xF4F0FF);
            c.drawCircle(sx, sy, dp(i % 4 == 0 ? 1.6f : 1.1f), paint);
        }
        float span = w + dp(500f);
        for (int i = 0; i < 6; i++) {
            float cx = (float) ((i * 0.41 * span + sessionSeconds * dp(4f + 1.5f * i)) % span) - dp(250f);
            float cy = horizon * (0.2f + 0.12f * i);
            if (cy > bankTop - dp(24f)) {
                cy = bankTop - dp(24f) - i * dp(6f);
            }
            float cw = dp(170f + 60f * (i % 3));
            float ch = dp(9f + 4f * (i % 2));
            float lit = (float) i / 5f;  // lower clouds catch more of the sun
            int under = mix(0xFF8A5C9A, 0xFFFFB27A, lit);
            paint.setColor((0x50 << 24) | (mix(0xFF4A3466, 0xFFB27090, lit) & 0x00FFFFFF));
            tmp.set(cx - cw * 0.5f, cy - ch, cx + cw * 0.5f, cy + ch * 0.4f);
            c.drawRoundRect(tmp, ch, ch, paint);
            tmp.set(cx - cw * 0.22f, cy - ch * 1.9f, cx + cw * 0.18f, cy);
            c.drawRoundRect(tmp, ch, ch, paint);
            paint.setColor((0x70 << 24) | (under & 0x00FFFFFF));
            tmp.set(cx - cw * 0.42f, cy + ch * 0.05f, cx + cw * 0.46f, cy + ch * 0.55f);
            c.drawRoundRect(tmp, ch * 0.3f, ch * 0.3f, paint);
        }
    }

    /** Tufts of uncut grass and the odd daisy across the near field, nodding in the breeze. */
    private void drawGrass(Canvas c, float w, float h, float mid, float ground) {
        float top = ground + dp(14f);
        float bottom = h - dp(118f);
        if (bottom <= top) {
            return;
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int i = 0; i < 40; i++) {
            int hs = hash(i * 131 + 17);
            float tx = (hs % 10007) / 10007f * w;
            float ty = top + ((hs >>> 14) % 1000) / 1000f * (bottom - top);
            if (Math.abs(tx - mid) < dp(140f) && ty < ground + dp(36f)) {
                continue;   // not in the pit
            }
            float s = 0.7f + 0.9f * (ty - top) / (bottom - top);
            float sway = (float) Math.sin(sessionSeconds * 1.6 + i * 0.7) * dp(2f) * s;
            paint.setStrokeWidth(dp(1.8f) * s);
            paint.setColor(0xFF2A5424);
            c.drawLine(tx, ty, tx - dp(4f) * s + sway, ty - dp(9f) * s, paint);
            c.drawLine(tx, ty, tx + dp(4.5f) * s + sway, ty - dp(8f) * s, paint);
            paint.setColor(0xFF6E9A48);
            c.drawLine(tx, ty, tx + sway, ty - dp(12f) * s, paint);
            if (hs % 5 == 0) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(0xE6F6F2E4);
                c.drawCircle(tx + sway, ty - dp(12.5f) * s, dp(2.2f) * s, paint);
                paint.setColor(0xFFF0B132);
                c.drawCircle(tx + sway, ty - dp(12.5f) * s, dp(0.9f) * s, paint);
                paint.setStyle(Paint.Style.STROKE);
            }
        }
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setStyle(Paint.Style.FILL);
    }

    /** Dust and pollen hanging in the low sunlight over the pitch. */
    private void drawMotes(Canvas c, float w, float horizon, float ground) {
        paint.setStyle(Paint.Style.FILL);
        float band = ground - horizon + dp(60f);
        for (int i = 0; i < 22; i++) {
            int hs = hash(i * 977 + 3);
            float speed = dp(6f + (hs % 9));
            float x = (float) (((hs % 10007) / 10007.0 * w + sessionSeconds * speed) % w);
            float y = horizon - dp(30f) + ((hs >>> 12) % 1000) / 1000f * band
                    + (float) Math.sin(sessionSeconds * 0.9 + i) * dp(6f);
            int a = (int) (60 + 50 * Math.sin(sessionSeconds * 2.0 + i * 1.3));
            paint.setColor((a << 24) | 0xFFE6B0);
            c.drawCircle(x, y, dp(1.7f), paint);
        }
    }

    /**
     * The near-side crowd, seen from behind against the pitch: backlit heads and shoulders behind
     * the board. Each half cheers its own team, like the far bank - jumping, arms up, scarves high.
     */
    private void drawNearCrowd(Canvas c, float w, float h, Team team) {
        float boardTop = h - dp(88f);
        float gap = dp(46f);
        int n = (int) (w / gap) + 2;
        for (int i = 0; i < n; i++) {
            int hs = hash(i * 31 + 7);
            if (hs % 11 == 3) {
                continue;   // a gap in the row
            }
            float x = i * gap + ((hs >>> 3) % 9 - 4) * dp(1.6f);
            boolean yours = x > w * 0.5f;
            float loud = yours ? yourLoud : theirLoud;
            int teamColour = yours ? ACCENT : team.shirt;
            int base = (hs >>> 9) % 3 == 0 ? mix(teamColour, 0xFF0A0E14, 0.5f)
                    : SPECTATORS[(hs >>> 5) % SPECTATORS.length];
            float bob = loud * (float) Math.abs(Math.sin(sessionSeconds * 6.5 + i * 1.7)) * dp(7f);
            float top = boardTop - dp(26f) - ((hs >>> 12) % 4) * dp(3f) - bob;
            if (loud > 0.4f && ((hs >>> 14) & 1) == 0) {
                float wave = (float) Math.sin(sessionSeconds * 8 + i) * dp(4f);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeWidth(dp(6.5f));
                paint.setColor(base);
                c.drawLine(x - dp(13f), top + dp(9f), x - dp(19f) + wave, top - dp(26f), paint);
                c.drawLine(x + dp(13f), top + dp(9f), x + dp(19f) + wave, top - dp(26f), paint);
                paint.setStrokeCap(Paint.Cap.BUTT);
                paint.setStyle(Paint.Style.FILL);
                if (loud > 0.6f && (hs >>> 16) % 3 == 0) {
                    // A scarf held high between the fists.
                    paint.setColor(teamColour);
                    c.drawRect(x - dp(21f) + wave, top - dp(35f), x + dp(21f) + wave, top - dp(25f), paint);
                    paint.setColor(0xDDFFFFFF);
                    c.drawRect(x - dp(5f) + wave, top - dp(35f), x + dp(5f) + wave, top - dp(25f), paint);
                }
                paint.setColor(0xFFB98A58);
                c.drawCircle(x - dp(19f) + wave, top - dp(28f), dp(4.2f), paint);
                c.drawCircle(x + dp(19f) + wave, top - dp(28f), dp(4.2f), paint);
            }
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(base);
            tmp.set(x - dp(20f), top, x + dp(20f), boardTop + dp(30f));
            c.drawRoundRect(tmp, dp(15f), dp(15f), paint);
            paint.setColor(HAIR[(hs >>> 18) % HAIR.length]);
            c.drawCircle(x, top - dp(9f), dp(11.5f), paint);
            if ((hs >>> 20) % 5 == 0) {
                // A cap in the team's colour.
                paint.setColor(mix(teamColour, 0xFF000000, 0.2f));
                tmp.set(x - dp(12f), top - dp(22f), x + dp(12f), top);
                c.drawArc(tmp, 180f, 180f, true, paint);
            }
            // Rim light from the low sun across the pitch.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2f));
            paint.setColor(0x70FFC488);
            tmp.set(x - dp(11.5f), top - dp(20.5f), x + dp(11.5f), top + dp(2.5f));
            c.drawArc(tmp, 210f, 120f, false, paint);
            tmp.set(x - dp(20f), top, x + dp(20f), top + dp(30f));
            c.drawArc(tmp, 205f, 130f, false, paint);
            paint.setStyle(Paint.Style.FILL);
        }
    }

    /**
     * The rival's speech bubble. It pops in, sits over their crew and fades, and it is the one
     * thing on screen that is aimed at the rower rather than at the rope.
     */
    private void drawTaunt(Canvas c, float xIn, float y, Team team) {
        float x = xIn + (tauntSeed % 3 - 1) * dp(20f);
        float f = Math.min(1f, tauntLife / 0.35f);
        float pop = Math.min(1f, (3.8f - tauntLife) / 0.18f);
        dimPaint.setTextSize(dp(15f));
        dimPaint.setFakeBoldText(true);
        float tw = dimPaint.measureText(taunt);
        dimPaint.setFakeBoldText(false);
        float bw = tw + dp(28f);
        float bh = dp(34f);
        float scale = 0.7f + 0.3f * pop;
        int a = (int) (235 * f);
        c.save();
        c.scale(scale, scale, x, y + bh);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor((a << 24) | 0x101826);
        tmp.set(x - bw / 2f, y, x + bw / 2f, y + bh);
        c.drawRoundRect(tmp, dp(10f), dp(10f), paint);
        rope.rewind();
        rope.moveTo(x - dp(9f), y + bh - dp(2f));
        rope.lineTo(x + dp(9f), y + bh - dp(2f));
        rope.lineTo(x - dp(3f), y + bh + dp(13f));
        rope.close();
        c.drawPath(rope, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor((a << 24) | (team.shirt & 0x00FFFFFF));
        c.drawRoundRect(tmp, dp(10f), dp(10f), paint);
        paint.setStyle(Paint.Style.FILL);
        bold(c, taunt, x, y + dp(23f), 15f, ((int) (255 * f) << 24) | (TEXT & 0x00FFFFFF),
                Paint.Align.CENTER);
        c.restore();
    }

    private void addSplat(float x, float y, float r) {
        int i = splatNext;
        splatNext = (splatNext + 1) % splatX.length;
        splatX[i] = x;
        splatY[i] = y;
        splatR[i] = r;
        splatLife[i] = 6f;
    }

    private void drawSplats(Canvas c, float dt) {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < splatX.length; i++) {
            if (splatLife[i] <= 0) {
                continue;
            }
            splatLife[i] -= dt;
            float f = Math.min(1f, splatLife[i] / 2f);
            paint.setColor(((int) (200 * f) << 24) | 0x3A2412);
            float r = splatR[i];
            c.drawOval(splatX[i] - r * 1.6f, splatY[i] - r * 0.5f, splatX[i] + r * 1.6f, splatY[i] + r * 0.5f, paint);
        }
    }

    /**
     * Each half of the crowd shouts for its own team: banners go up, rings of noise spread out and
     * chants float into the sky, all scaling with that team's lead.
     */
    private void drawCrowdNoise(Canvas c, float w, float top, float bottom, Team team, float dt) {
        for (int side = 0; side < 2; side++) {
            boolean yours = side == 1;
            float loud = yours ? yourLoud : theirLoud;
            if (loud < 0.02f) {
                continue;
            }
            int colour = yours ? ACCENT : team.shirt;
            float x0 = yours ? w * 0.5f : 0f;
            float half = w * 0.5f;
            // Banners: more go up the bigger the lead.
            int banners = Math.round(loud * 10);
            paint.setStyle(Paint.Style.FILL);
            for (int i = 0; i < banners; i++) {
                float bx = x0 + half * ((i * 0.37f + 0.05f) % 1f);
                float wave = (float) Math.sin(sessionSeconds * (5 + i % 3) + i) * dp(4f) * (0.5f + loud);
                float poleTop = top - dp(18f) - loud * dp(10f) - (float) Math.abs(Math.sin(sessionSeconds * 4 + i)) * dp(6f) * loud;
                paint.setColor(0xFF9AA5B1);
                c.drawRect(bx - dp(1f), poleTop, bx + dp(1f), bottom - dp(18f), paint);
                rope.rewind();
                rope.moveTo(bx + dp(1f), poleTop);
                rope.lineTo(bx + dp(26f), poleTop + dp(3f) + wave);
                rope.lineTo(bx + dp(24f), poleTop + dp(14f) + wave);
                rope.lineTo(bx + dp(1f), poleTop + dp(13f));
                rope.close();
                paint.setColor(i % 3 == 2 ? 0xFFFFFFFF : colour);
                c.drawPath(rope, paint);
            }
            // Rings of noise rising off the crowd.
            float cx = x0 + half * 0.5f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(2f + loud * 2f));
            for (int k = 0; k < 3; k++) {
                float p = (float) ((sessionSeconds * (0.8 + loud) + k / 3.0) % 1.0);
                float r = dp(60f) + p * (half * 0.45f) * (0.4f + loud);
                paint.setColor(((int) (loud * 120 * (1 - p)) << 24) | (colour & 0x00FFFFFF));
                tmp.set(cx - r * 1.8f, top - r * 0.5f, cx + r * 1.8f, top + r * 0.5f);
                c.drawArc(tmp, 200f, 140f, false, paint);
            }
            paint.setStyle(Paint.Style.FILL);
            // New chants, faster when louder.
            if (phase != Phase.READY && Math.random() < loud * dt * 3.0) {
                int i = chantNext;
                chantNext = (chantNext + 1) % chantX.length;
                chantX[i] = x0 + half * (0.1f + 0.8f * (float) Math.random());
                chantY[i] = top - dp(10f);
                chantAge[i] = 0f;
                chantYours[i] = yours;
                chantWord[i] = (int) (Math.random() * (yours ? YOUR_CHANTS.length : THEIR_CHANTS.length));
            }
            // Confetti over a crowd that is going wild.
            if (loud > 0.7f && Math.random() < (loud - 0.6f) * dt * 25) {
                fx.spawn(x0 + (float) Math.random() * half, top, (float) (Math.random() - 0.5) * dp(80f),
                        -dp(120f) - (float) Math.random() * dp(80f), 1.6f, dp(3f),
                        CONFETTI[(int) (Math.random() * CONFETTI.length)], true);
            }
        }
        for (int i = 0; i < chantX.length; i++) {
            if (chantAge[i] > 1.6f) {
                continue;
            }
            chantAge[i] += dt;
            float loud = chantYours[i] ? yourLoud : theirLoud;
            float f = Math.max(0f, 1f - chantAge[i] / 1.6f);
            int colour = chantYours[i] ? ACCENT : team.shirt;
            String word = chantYours[i] ? YOUR_CHANTS[chantWord[i]] : THEIR_CHANTS[chantWord[i]];
            bold(c, word, chantX[i], chantY[i] - chantAge[i] * dp(45f), 12f + 12f * loud,
                    ((int) (230 * f) << 24) | (colour & 0x00FFFFFF), Paint.Align.CENTER);
        }
    }

    /** An anchor glyph that fills as the rate holds steady, with the live rate spread under it. */
    private void drawAnchorGauge(Canvas c, float rightX, float y) {
        float cx = rightX - dp(22f);
        float s = dp(1f);
        if (anchor > 0.99f) {
            Fx.glow(c, cx, y, dp(44f), 0x886F8CFF);
        }
        int col = anchor > 0.99f ? BLUE : anchorSteady ? 0xFF9FB2FF : FAINT;
        float bx = rightX - dp(160f);
        tmp.set(bx - dp(12f), y - dp(26f), Math.max(rightX + dp(6f), bx + gaugeSubW + dp(12f)), y + dp(33f));
        panel(c, tmp, dp(10f), 0xB0);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(dp(3f));
        paint.setColor(col);
        c.drawCircle(cx, y - 16 * s, 4 * s, paint);
        c.drawLine(cx, y - 12 * s, cx, y + 16 * s, paint);
        c.drawLine(cx - 9 * s, y - 6 * s, cx + 9 * s, y - 6 * s, paint);
        tmp.set(cx - 15 * s, y - 2 * s, cx + 15 * s, y + 18 * s);
        c.drawArc(tmp, 20f, 140f, false, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        // Fill bar beside it.
        bar(c, bx, y + 2 * s, dp(110f), 8 * s, anchor, col);
        label(c, anchor > 0.99f ? "ANCHORED" : "ANCHOR", bx, y - 4 * s, 10f, col, Paint.Align.LEFT);
        String sub = rateSpread > 20 ? "hold a steady rate" : String.format(java.util.Locale.US,
                "rate ±%.1f spm  ·  steady ≤ 2.5", rateSpread / 2f);
        label(c, sub, bx, y + 24 * s, 8.5f, DIM, Paint.Align.LEFT);
        gaugeSubW = dimPaint.measureText(sub);
    }

    /* ---------- best of three, anchor rounds, comebacks, the table ---------- */

    /** The match score above the rope: their pulls to the left, yours to the right. */
    private void drawScore(Canvas c, float w, float y, Team team) {
        float cx = w / 2f;
        label(c, "BEST OF THREE", cx, y - dp(16f), 8.5f, FAINT, Paint.Align.CENTER);
        for (int i = 0; i < PULLS_TO_WIN; i++) {
            drawPip(c, cx + dp(42f) + i * dp(24f), y, yourPulls > i, ACCENT);
            drawPip(c, cx - dp(42f) - i * dp(24f), y, theirPulls > i, team.shirt);
        }
        bold(c, yourPulls + " - " + theirPulls, cx, y + dp(7f), 20f, TEXT, Paint.Align.CENTER);
    }

    private void drawPip(Canvas c, float x, float y, boolean won, int colour) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(won ? mix(colour, 0xFF000000, 0.35f) : 0x99060B12);
        c.drawCircle(x, y, dp(9f), paint);
        if (won) {
            Fx.glow(c, x, y, dp(20f), (0x60 << 24) | (colour & 0x00FFFFFF));
            paint.setColor(colour);
            c.drawCircle(x, y, dp(7.5f), paint);
            paint.setColor(0x60FFFFFF);
            c.drawCircle(x - dp(2.2f), y - dp(2.5f), dp(3f), paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2f));
        paint.setColor(won ? mix(colour, 0xFFFFFFFF, 0.3f) : 0xFF3A4A60);
        c.drawCircle(x, y, dp(8f), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /**
     * The anchor round: a countdown, how much slack is left before the line breaks, and the hold
     * and break lines drawn on the rope itself so the target is where the marker is.
     */
    private void drawAnchorRound(Canvas c, float w, float y, float mid, float travel, float ropeY) {
        float cx = w / 2f;
        if (roundActive) {
            double leftS = Math.max(0, roundEndsAt - pullSeconds);
            float gone = (float) (1.0 - leftS / ROUND_SECONDS);
            float margin = (float) Math.max(0, Math.min(1,
                    (position - (roundLine - ROUND_SLACK)) / ROUND_SLACK));
            int col = margin < 0.33f ? BAD : margin < 0.66f ? WARN : BLUE;
            tmp.set(cx - dp(215f), y - dp(28f), cx + dp(215f), y + dp(40f));
            panel(c, tmp, dp(10f), 0xE0);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(1.5f));
            paint.setColor((0x70 << 24) | (col & 0x00FFFFFF));
            c.drawRoundRect(tmp, dp(10f), dp(10f), paint);
            // Countdown ring on the left of the panel.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(4f));
            paint.setColor(0xFF2A3648);
            tmp.set(cx - dp(202f), y - dp(20f), cx - dp(162f), y + dp(20f));
            c.drawArc(tmp, -90f, 360f, false, paint);
            paint.setColor(col);
            c.drawArc(tmp, -90f, 360f * Math.max(0f, 1f - gone), false, paint);
            paint.setStyle(Paint.Style.FILL);
            bold(c, String.valueOf((int) Math.ceil(leftS)), cx - dp(182f), y + dp(7f), 18f, col,
                    Paint.Align.CENTER);
            bold(c, "ANCHOR ROUND " + roundsThisMatch + " - HOLD THE LINE", cx + dp(24f), y - dp(6f),
                    16f, col, Paint.Align.CENTER);
            float bx = cx - dp(126f);
            float bw = dp(300f);
            bar(c, bx, y + dp(6f), bw, dp(10f), margin, col);
            label(c, margin < 0.33f ? "THE LINE IS GOING"
                            : anchor < 0.5f ? "GET ANCHORED - HOLD A STEADY RATE"
                            : "slack left before the line breaks",
                    bx, y + dp(30f), 8.5f, col, Paint.Align.LEFT);
            // The two lines on the rope: where you were pegged, and where it breaks.
            float hx = mid + travel * (float) roundLine;
            float kx = mid + travel * (float) (roundLine - ROUND_SLACK);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(dp(3f));
            paint.setColor(BLUE);
            c.drawLine(hx, ropeY - dp(46f), hx, ropeY + dp(26f), paint);
            paint.setColor(BAD);
            c.drawLine(kx, ropeY - dp(38f), kx, ropeY + dp(22f), paint);
            paint.setStrokeCap(Paint.Cap.BUTT);
            paint.setStyle(Paint.Style.FILL);
            label(c, "HOLD", hx, ropeY - dp(50f), 8f, BLUE, Paint.Align.CENTER);
            label(c, "BREAK", kx, ropeY - dp(42f), 8f, BAD, Paint.Align.CENTER);
        } else if (roundFlash > 0f) {
            int a = (int) (255 * Math.min(1f, roundFlash));
            bold(c, roundFlashGood ? "LINE HELD - THEY'RE BLOWING" : "THE LINE WENT", cx, y, 26f,
                    (a << 24) | ((roundFlashGood ? ACCENT : BAD) & 0x00FFFFFF), Paint.Align.CENTER);
        } else if (phase == Phase.PULLING) {
            double until = nextRoundAt - pullSeconds;
            if (until > 0 && until < 8) {
                bold(c, "ANCHOR ROUND IN " + (int) Math.ceil(until), cx, y, 16f, WARN, Paint.Align.CENTER);
            }
        }
    }

    /** The comeback meter, which only exists while you are far enough behind to need one. */
    private void drawComebackMeter(Canvas c, float w, float y, Team team) {
        float cx = w / 2f;
        if (comebackBoost > 0f) {
            float f = Math.min(1f, comebackBoost / 8f);
            Fx.glow(c, cx, y, dp(130f), ((int) (110 * f) << 24) | (WARN & 0x00FFFFFF));
            bold(c, "COMEBACK!", cx, y + dp(8f), 32f, WARN, Paint.Align.CENTER);
            return;
        }
        if (comebackFlash > 0f) {
            int a = (int) (255 * Math.min(1f, comebackFlash));
            bold(c, "THAT SHUT THEM UP", cx, y, 20f, (a << 24) | (ACCENT & 0x00FFFFFF), Paint.Align.CENTER);
            return;
        }
        if (phase != Phase.PULLING || comeback <= 0.01f) {
            return;
        }
        float bw = dp(300f);
        float bx = cx - bw / 2f;
        Fx.glow(c, bx + bw * comeback, y + dp(7f), dp(26f), 0x70F0B132);
        bar(c, bx, y, bw, dp(14f), comeback, WARN);
        label(c, tauntedThisPull ? "COMEBACK - WIPE THAT GRIN OFF THEM"
                        : "COMEBACK - OUT-PULL THEM FROM BACK HERE",
                cx, y - dp(6f), 9.5f, WARN, Paint.Align.CENTER);
        label(c, "fills while your watts beat theirs  ·  pays 8 s at +35%", cx, y + dp(26f), 8.5f,
                FAINT, Paint.Align.CENTER);
    }

    /** Rank progress along the bottom: what the strength rating has bought and what is next. */
    private void drawStrengthBar(Canvas c, float w, float y) {
        float bw = dp(260f);
        float bx = w / 2f - bw / 2f;
        bar(c, bx, y, bw, dp(7f), league.rankProgress(), BLUE);
        String next = league.nextRank();
        label(c, next == null ? "TITAN  ·  nothing above this"
                        : league.rank() + "  ·  " + Math.round(league.ratingToNext()) + " to " + next
                        + "  ·  " + league.anchorsHeld() + " lines held",
                w / 2f, y - dp(5f), 8.5f, DIM, Paint.Align.CENTER);
    }

    /** The division table: four sides, points, and the up and down places marked. */
    private void drawLeagueTable(Canvas c, float x, float y, float bw, float bh) {
        tmp.set(x, y, x + bw, y + bh);
        panel(c, tmp, dp(10f), 0xE6);
        float pad = dp(12f);
        float head = y + pad + dp(14f);
        bold(c, "SEASON " + league.season() + "  ·  " + league.divisionName(), x + pad, head, 14f, WARN,
                Paint.Align.LEFT);
        String msg = league.seasonMessage();
        label(c, msg != null ? msg : "three points a win  ·  top goes up, bottom goes down",
                x + bw - pad, head, 9f, msg != null ? ACCENT : FAINT, Paint.Align.RIGHT);
        float top = y + pad + dp(34f);
        float rowH = (bh - (top - y) - pad) / 4f;
        float colP = x + bw - pad - dp(20f);
        float colD = colP - dp(52f);
        float colPl = colD - dp(52f);
        label(c, "P", colPl, top - dp(6f), 8f, FAINT, Paint.Align.CENTER);
        label(c, "+/-", colD, top - dp(6f), 8f, FAINT, Paint.Align.CENTER);
        label(c, "PTS", colP, top - dp(6f), 8f, FAINT, Paint.Align.CENTER);
        int[] order = league.standings();
        int todayTeam = league.opponentTeam();
        for (int i = 0; i < 4; i++) {
            int row = order[i];
            float ry = top + (i + 0.5f) * rowH;
            boolean you = row == 0;
            int colour = you ? ACCENT : TEAMS[league.rowTeam(row)].shirt;
            paint.setStyle(Paint.Style.FILL);
            if (you) {
                paint.setColor(0x2235D0BA);
                tmp.set(x + pad * 0.5f, ry - rowH * 0.42f, x + bw - pad * 0.5f, ry + rowH * 0.42f);
                c.drawRoundRect(tmp, dp(5f), dp(5f), paint);
            }
            if (league.promotionPlace(row) || league.relegationPlace(row)) {
                paint.setColor(league.promotionPlace(row) ? ACCENT : BAD);
                c.drawRect(x + pad * 0.5f, ry - rowH * 0.42f, x + pad * 0.5f + dp(4f),
                        ry + rowH * 0.42f, paint);
            }
            label(c, String.valueOf(i + 1), x + pad + dp(14f), ry + dp(4f), 10f, FAINT, Paint.Align.CENTER);
            paint.setColor(colour);
            c.drawCircle(x + pad + dp(32f), ry, dp(4f), paint);
            String name = league.rowName(row);
            if (!you && league.rowTeam(row) == todayTeam) {
                name = name + "  ·  TODAY";
            }
            if (you) {
                bold(c, name, x + pad + dp(44f), ry + dp(4f), 12f, colour, Paint.Align.LEFT);
            } else {
                label(c, name, x + pad + dp(44f), ry + dp(4f), 12f, colour, Paint.Align.LEFT);
            }
            label(c, String.valueOf(league.played(row)), colPl, ry + dp(4f), 11f, DIM, Paint.Align.CENTER);
            label(c, String.format(java.util.Locale.US, "%+d", league.diff(row)), colD, ry + dp(4f), 11f,
                    DIM, Paint.Align.CENTER);
            bold(c, String.valueOf(league.points(row)), colP, ry + dp(4f), 13f, TEXT, Paint.Align.CENTER);
        }
    }

    private void drawModeButton(Canvas c, float w) {
        float bw = dp(190f);
        float bh = dp(46f);
        modeButton.set(w - bw - dp(12f), dp(12f), w - dp(12f), dp(12f) + bh);
        int col = cup() ? WARN : leagueMode() ? BLUE : ACCENT;
        panel(c, modeButton, dp(10f), 0xE0);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(col);
        c.drawRoundRect(modeButton, dp(10f), dp(10f), paint);
        paint.setStyle(Paint.Style.FILL);
        bold(c, MODE_NAMES[mode], modeButton.centerX(), modeButton.centerY(), 13f, col, Paint.Align.CENTER);
        label(c, MODE_NEXT[mode], modeButton.centerX(), modeButton.centerY() + dp(14f), 8.5f, FAINT,
                Paint.Align.CENTER);
    }

    /** Eight teams, three rounds: quarter-finals, semis, final, champion. */
    private void drawBracket(Canvas c, float x, float y, float bw, float bh) {
        tmp.set(x, y, x + bw, y + bh);
        panel(c, tmp, dp(10f), 0xE6);
        float pad = dp(10f);
        float colW = (bw - pad * 2) / 4f;
        float rh = (bh - pad * 2) / 8f;
        float top = y + pad;
        float x0 = x + pad;
        for (int i = 0; i < 8; i++) {
            float sy = top + (i + 0.5f) * rh;
            boolean out = cupSemi[i / 2] != NONE && cupSemi[i / 2] != cupQuarter[i];
            drawSlot(c, cupQuarter[i], x0, sy, colW, out, cupRound == 0 && i < 2 && !cupOut);
        }
        for (int j = 0; j < 4; j++) {
            float sy = top + (2 * j + 1) * rh;
            joinLines(c, x0 + colW - dp(6f), top + (2 * j + 0.5f) * rh, top + (2 * j + 1.5f) * rh, x0 + colW, sy);
            boolean out = cupSemi[j] != NONE && cupFinal[j / 2] != NONE && cupFinal[j / 2] != cupSemi[j];
            drawSlot(c, cupSemi[j], x0 + colW, sy, colW, out, cupRound == 1 && j < 2 && !cupOut);
        }
        for (int k = 0; k < 2; k++) {
            float sy = top + (4 * k + 2) * rh;
            joinLines(c, x0 + 2 * colW - dp(6f), top + (4 * k + 1) * rh, top + (4 * k + 3) * rh, x0 + 2 * colW, sy);
            boolean out = cupFinal[k] != NONE && cupChampion != NONE && cupChampion != cupFinal[k];
            drawSlot(c, cupFinal[k], x0 + 2 * colW, sy, colW, out, cupRound == 2 && cupChampion == NONE && !cupOut);
        }
        float cy = top + 4 * rh;
        joinLines(c, x0 + 3 * colW - dp(6f), top + 2 * rh, top + 6 * rh, x0 + 3 * colW, cy);
        label(c, "CHAMPION", x0 + 3 * colW + dp(6f), cy - dp(16f), 8.5f, WARN, Paint.Align.LEFT);
        drawSlot(c, cupChampion, x0 + 3 * colW, cy, colW, false, false);
        label(c, "QUARTERS", x0 + dp(6f), y + bh - dp(3f), 7.5f, FAINT, Paint.Align.LEFT);
        label(c, "SEMIS", x0 + colW + dp(6f), y + bh - dp(3f), 7.5f, FAINT, Paint.Align.LEFT);
        label(c, "FINAL", x0 + 2 * colW + dp(6f), y + bh - dp(3f), 7.5f, FAINT, Paint.Align.LEFT);
    }

    private void joinLines(Canvas c, float fromX, float y1, float y2, float toX, float midY) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1.5f));
        paint.setColor(0xFF2A3648);
        c.drawLine(fromX, y1, fromX + dp(3f), y1, paint);
        c.drawLine(fromX, y2, fromX + dp(3f), y2, paint);
        c.drawLine(fromX + dp(3f), y1, fromX + dp(3f), y2, paint);
        c.drawLine(fromX + dp(3f), midY, toX + dp(4f), midY, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawSlot(Canvas c, int who, float x, float y, float colW, boolean out, boolean live) {
        if (live) {
            float pulse = 0.5f + 0.5f * (float) Math.abs(Math.sin(sessionSeconds * 3));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(((int) (60 + 60 * pulse) << 24) | (WARN & 0x00FFFFFF));
            tmp.set(x + dp(2f), y - dp(10f), x + colW - dp(10f), y + dp(8f));
            c.drawRoundRect(tmp, dp(4f), dp(4f), paint);
        }
        String name;
        int col;
        if (who == NONE) {
            name = "?";
            col = FAINT;
        } else if (who == YOU) {
            name = "YOU";
            col = out ? FAINT : ACCENT;
        } else {
            name = TEAMS[who].name;
            col = out ? FAINT : TEAMS[who].shirt;
        }
        if (who != NONE) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(col);
            c.drawCircle(x + dp(10f), y - dp(3f), dp(4f), paint);
        }
        if (who == YOU || who == cupChampion && who != NONE) {
            bold(c, name, x + dp(20f), y + dp(2f), 11f, col, Paint.Align.LEFT);
        } else {
            label(c, name, x + dp(20f), y + dp(2f), 11f, col, Paint.Align.LEFT);
        }
        if (out) {
            paint.setStrokeWidth(dp(1.2f));
            paint.setColor(FAINT);
            c.drawLine(x + dp(18f), y - dp(2f), x + colW - dp(20f), y - dp(2f), paint);
        }
    }

    /**
     * One puller with a body: legs that bend at the knee, boots with the front heel dug in, a torso
     * with weight that leans back with the heave, arms that reach the rope and fists on it.
     * {@code facing} +1 pulls right. The low sun sits behind the far crowd, so the chest side (toward
     * the rope and the centre) catches a rim of light and the back falls into shade.
     */
    private void drawPuller(Canvas c, float x, float ground, int facing, float lean, int color, int seed, float fall,
                            int hat, float brace, boolean anchorMan) {
        // Drawn at 1.5x around the feet: hands land at ground - 60 dp, which is rope height.
        c.save();
        c.scale(1.5f, 1.5f, x, ground);
        float f = facing;
        float up = 1f - fall;
        // How hard the heels are in: grows with the lean, so a heave visibly digs the boots.
        float dig = Math.max(0f, Math.min(1f, (lean - 0.3f) / 0.9f)) * up;
        float len = dp(36f);
        float hipX = x + f * dig * dp(2.5f);
        float hipY = ground - dp(30f) + fall * dp(22f) + brace * dp(4f) + dig * dp(6f);
        float sinL = (float) Math.sin(lean);
        float cosL = (float) Math.cos(lean);
        float dx = f * sinL;
        float dy = -cosL;
        float sx = hipX + dx * len;
        float sy = hipY + dy * len;
        float nx = -f * cosL;     // the chest side
        float ny = -sinL;
        float frontFoot = x - f * (dp(18f) + brace * dp(8f));
        float rearFoot = x - f * dp(4f) + f * brace * dp(6f);
        float handX = x - f * dp(20f);
        float handY = ground - dp(40f) + fall * dp(20f);
        int dark = mix(color, 0xFF000000, 0.4f);
        int light = mix(color, 0xFFFFFFFF, 0.45f);

        // Shadows: a dark contact patch under the boots and a long soft one thrown toward the viewer.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x2A000000);
        c.drawOval(x + f * dp(6f) - dp(22f), ground + dp(1f), x + f * dp(6f) + dp(22f), ground + dp(9f), paint);
        paint.setColor(0x55000000);
        c.drawOval(x - f * dp(8f) - dp(17f), ground - dp(2.5f), x - f * dp(8f) + dp(17f), ground + dp(3.5f), paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        // Far leg, in shade.
        float footY = ground - dp(2.5f);
        joint(hipX, hipY, rearFoot, footY, dp(17.5f), -f, -0.4f);
        paint.setStrokeWidth(dp(7.5f));
        paint.setColor(0xFF1B1F27);
        c.drawLine(hipX, hipY, jx, jy, paint);
        c.drawLine(jx, jy, rearFoot, footY, paint);
        drawBoot(c, rearFoot, ground, f, 4f, 0xFF2A1D14);
        // Far arm, behind the body, hand just behind the near one on the rope.
        float farHand = handX + f * dp(6f);
        joint(sx, sy, farHand, handY, dp(21f), 0f, 1f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(5.5f));
        paint.setColor(dark);
        c.drawLine(sx, sy, jx, jy, paint);
        paint.setStrokeWidth(dp(4.6f));
        paint.setColor(SKIN_SHADE);
        c.drawLine(jx, jy, farHand, handY, paint);
        // Torso: an outline, the shirt, the shaded back and the sunlit chest.
        paint.setStrokeWidth(dp(17.5f));
        paint.setColor(OUTLINE);
        c.drawLine(hipX, hipY, sx, sy, paint);
        paint.setStrokeWidth(dp(15f));
        paint.setColor(color);
        c.drawLine(hipX, hipY, sx, sy, paint);
        paint.setStrokeWidth(dp(5.5f));
        paint.setColor(dark);
        c.drawLine(hipX - nx * dp(3.8f), hipY - ny * dp(3.8f), sx - nx * dp(3.8f), sy - ny * dp(3.8f), paint);
        paint.setStrokeWidth(dp(2.2f));
        paint.setColor(light);
        c.drawLine(hipX + nx * dp(5f) + dx * dp(3f), hipY + ny * dp(5f) + dy * dp(3f),
                sx + nx * dp(5f), sy + ny * dp(5f), paint);
        // Shorts.
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(OUTLINE);
        c.drawCircle(hipX, hipY, dp(8.8f), paint);
        paint.setColor(TROUSERS);
        c.drawCircle(hipX, hipY, dp(7.6f), paint);
        // Near leg, braced toward the rope; an anchored puller plants it wider.
        joint(hipX, hipY, frontFoot, footY, dp(18f), -f, -0.6f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(8f));
        paint.setColor(TROUSERS);
        c.drawLine(hipX, hipY, jx, jy, paint);
        c.drawLine(jx, jy, frontFoot, footY, paint);
        paint.setStrokeWidth(dp(2f));
        paint.setColor(0xFF4A5262);
        c.drawLine(hipX + nx * dp(2.5f), hipY + ny * dp(2.5f), jx, jy - dp(2.5f), paint);
        // The front boot, toe up and heel in, with a ridge of turf shoved up ahead of it.
        drawBoot(c, frontFoot, ground, f, 18f + 14f * dig, 0xFF3B2A1E);
        if (dig > 0.05f) {
            float rx = frontFoot - f * dp(15f);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(((int) (190 * dig) << 24) | 0x5E4026);
            c.drawOval(rx - dp(6f), ground - dp(1.5f) - dp(4f) * dig, rx + dp(6f), ground + dp(1.5f), paint);
            paint.setColor(((int) (150 * dig) << 24) | 0x3E6B35);
            c.drawCircle(rx - f * dp(2f), ground - dp(2f) - dp(3.5f) * dig, dp(2f), paint);
        }
        if (anchorMan && brace > 0.3f) {
            // The rope wrapped round the anchor's waist, and the heel dug into a ridge of turf.
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3.5f));
            paint.setColor(0xFFB89A62);
            c.drawLine(handX, handY, hipX, hipY - dp(3f), paint);
            c.drawLine(hipX, hipY - dp(3f), hipX + f * dp(9f), hipY + dp(4f), paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(((int) (220 * brace) << 24) | 0x6B4A2C);
            c.drawOval(frontFoot - dp(9f), ground - dp(5f) * brace, frontFoot + dp(5f), ground + dp(2f), paint);
        }
        // Neck and head: the chin tucks toward the rope rather than following the spine flat.
        float headX = sx + f * dp(6f) * sinL;
        float headY = sy - dp(4f) - cosL * dp(5.5f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(5.5f));
        paint.setColor(SKIN_SHADE);
        c.drawLine(sx, sy, headX, headY, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(OUTLINE);
        c.drawCircle(headX, headY, dp(10.4f), paint);
        paint.setColor(0xFFF1C27D);
        c.drawCircle(headX, headY, dp(9f), paint);
        paint.setColor(0x40000000);
        c.drawCircle(headX + f * dp(2.5f), headY + dp(1.5f), dp(6.5f), paint);
        paint.setColor(0xFFF1C27D);
        c.drawCircle(headX - f * dp(1f), headY - dp(0.5f), dp(6.8f), paint);
        paint.setColor(0xFF1B1F27);
        c.drawCircle(headX - f * dp(5f), headY - dp(0.5f), dp(1.4f), paint);
        drawHat(c, hat, headX, headY, facing, color, seed);
        // Near arm over everything, fist closed on the rope.
        joint(sx, sy, handX, handY, dp(21f), 0f, 1f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(8f));
        paint.setColor(OUTLINE);
        c.drawLine(sx, sy, jx, jy, paint);
        c.drawLine(jx, jy, handX, handY, paint);
        paint.setStrokeWidth(dp(6.5f));
        paint.setColor(color);
        c.drawLine(sx, sy, jx, jy, paint);
        paint.setStrokeWidth(dp(5.2f));
        paint.setColor(0xFFF1C27D);
        c.drawLine(jx, jy, handX, handY, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(OUTLINE);
        c.drawCircle(handX, handY, dp(4.8f), paint);
        paint.setColor(0xFFF1C27D);
        c.drawCircle(handX, handY, dp(3.7f), paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
        c.restore();
    }

    /**
     * Solves a two-segment limb from (ax, ay) to (bx, by), each segment {@code seg} long, the joint
     * bending toward (px, py). The answer lands in {@link #jx}/{@link #jy} - no allocation. A reach
     * longer than the limb simply straightens it.
     */
    private void joint(float ax, float ay, float bx, float by, float seg, float px, float py) {
        float ddx = bx - ax;
        float ddy = by - ay;
        float d = (float) Math.sqrt(ddx * ddx + ddy * ddy);
        float cx = (ax + bx) * 0.5f;
        float cy = (ay + by) * 0.5f;
        if (d < 0.001f || d >= 2f * seg) {
            jx = cx;
            jy = cy;
            return;
        }
        float lift = (float) Math.sqrt(seg * seg - d * d * 0.25f);
        float qx = -ddy / d;
        float qy = ddx / d;
        if (qx * px + qy * py < 0f) {
            qx = -qx;
            qy = -qy;
        }
        jx = cx + qx * lift;
        jy = cy + qy * lift;
    }

    /** A boot with its heel at (x, ground), the toe toward the rope, tilted toe-up by {@code toeUp}. */
    private void drawBoot(Canvas c, float x, float ground, float f, float toeUp, int colour) {
        c.save();
        c.rotate(f * toeUp, x, ground - dp(1f));
        paint.setStyle(Paint.Style.FILL);
        float toe = x - f * dp(11f);
        float heel = x + f * dp(3f);
        tmp.set(Math.min(toe, heel), ground - dp(6f), Math.max(toe, heel), ground + dp(0.5f));
        paint.setColor(OUTLINE);
        c.drawRoundRect(tmp, dp(3.2f), dp(3.2f), paint);
        tmp.inset(dp(0.9f), dp(0.9f));
        paint.setColor(colour);
        c.drawRoundRect(tmp, dp(2.6f), dp(2.6f), paint);
        paint.setColor(0xFF120D09);
        c.drawRect(tmp.left, ground - dp(1.6f), tmp.right, ground + dp(0.5f), paint);
        c.restore();
    }

    /** Team headgear, so each opponent reads as a crew at a glance. */
    private void drawHat(Canvas c, int hat, float hx, float hy, int facing, int shirt, int seed) {
        float r = dp(9f);
        paint.setStyle(Paint.Style.FILL);
        switch (hat) {
            case HAT_FLOWER:
                paint.setColor(0xFFD8B25A);
                c.drawCircle(hx, hy - dp(4f), dp(6f), paint);
                paint.setColor(0xFFFFFFFF);
                for (int i = 0; i < 5; i++) {
                    double a = i * Math.PI * 2 / 5 + sessionSeconds;
                    c.drawCircle(hx + (float) Math.cos(a) * dp(3.5f), hy - dp(11f) + (float) Math.sin(a) * dp(3.5f), dp(2.3f), paint);
                }
                paint.setColor(0xFFF0B132);
                c.drawCircle(hx, hy - dp(11f), dp(2f), paint);
                break;
            case HAT_CAP: {
                paint.setColor(shirt);
                tmp.set(hx - r, hy - r - dp(1f), hx + r, hy + r - dp(1f));
                c.drawArc(tmp, 180f, 180f, true, paint);
                // Brim toward the rope (opposite the lean).
                float b0 = hx - facing * dp(4f);
                float b1 = hx - facing * dp(15f);
                c.drawRect(Math.min(b0, b1), hy - dp(2.5f), Math.max(b0, b1), hy, paint);
                break;
            }
            case HAT_BANDANA: {
                paint.setColor(0xFF222222);
                c.drawCircle(hx, hy - dp(4f), dp(6f), paint);
                paint.setColor(shirt);
                c.drawRect(hx - r, hy - dp(6f), hx + r, hy - dp(2f), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2f));
                float tail = (float) Math.sin(sessionSeconds * 9 + seed) * dp(2f);
                c.drawLine(hx + facing * r, hy - dp(4f), hx + facing * (r + dp(6f)), hy + tail, paint);
                paint.setStyle(Paint.Style.FILL);
                break;
            }
            case HAT_HORNS: {
                paint.setColor(0xFF6E7684);
                tmp.set(hx - r, hy - r - dp(1f), hx + r, hy + r - dp(1f));
                c.drawArc(tmp, 180f, 180f, true, paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(2.5f));
                paint.setColor(0xFFEDE3C8);
                c.drawLine(hx - dp(6f), hy - dp(6f), hx - dp(11f), hy - dp(15f), paint);
                c.drawLine(hx + dp(6f), hy - dp(6f), hx + dp(11f), hy - dp(15f), paint);
                paint.setStyle(Paint.Style.FILL);
                break;
            }
            case HAT_HEADBAND: {
                paint.setColor(0xFF222222);
                c.drawCircle(hx, hy - dp(4f), dp(6f), paint);
                paint.setColor(0xFFFFFFFF);
                c.drawRect(hx - r, hy - dp(5f), hx + r, hy - dp(2f), paint);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(dp(1.8f));
                paint.setColor(shirt);
                float tail = (float) Math.sin(sessionSeconds * 11 + seed) * dp(3f);
                c.drawLine(hx + facing * r, hy - dp(3.5f), hx + facing * (r + dp(9f)), hy - dp(1f) + tail, paint);
                c.drawLine(hx + facing * r, hy - dp(3.5f), hx + facing * (r + dp(7f)), hy + dp(3f) - tail, paint);
                paint.setStyle(Paint.Style.FILL);
                break;
            }
            case HAT_BEANIE: {
                paint.setColor(shirt);
                tmp.set(hx - r, hy - r - dp(2f), hx + r, hy + r - dp(2f));
                c.drawArc(tmp, 180f, 180f, true, paint);
                paint.setColor(0xFFFFFFFF);
                c.drawCircle(hx, hy - r - dp(3f), dp(3f), paint);
                break;
            }
            case HAT_HAIR:
            default:
                paint.setColor(seed % 2 == 0 ? 0xFF222222 : 0xFF6B3E1E);
                c.drawCircle(hx, hy - dp(4f), dp(6f), paint);
        }
    }

    /* ---------- drawing helpers (3.25.0) ---------- */

    private static int mix(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int r = (int) (ar + (((b >> 16) & 0xFF) - ar) * t);
        int g = (int) (ag + (((b >> 8) & 0xFF) - ag) * t);
        int bl = (int) (ab + ((b & 0xFF) - ab) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** A deterministic scatter, so crowd and grass sit still from frame to frame. */
    private static int hash(int k) {
        int h = k * 0x45d9f3b;
        h ^= h >>> 16;
        h *= 0x45d9f3b;
        h ^= h >>> 16;
        return h & 0x7fffffff;
    }

    private android.graphics.LinearGradient panelGradient(float top, float bottom) {
        for (int i = 0; i < panelShaders.length; i++) {
            if (panelShaders[i] != null && panelTop[i] == top && panelBottom[i] == bottom) {
                return panelShaders[i];
            }
        }
        int i = panelNext;
        panelNext = (panelNext + 1) % panelShaders.length;
        panelTop[i] = top;
        panelBottom[i] = bottom;
        panelShaders[i] = new android.graphics.LinearGradient(0, top, 0, bottom, 0xFF172236, 0xFF0A111C,
                android.graphics.Shader.TileMode.CLAMP);
        return panelShaders[i];
    }

    /** A HUD panel in the app's family: vertical gradient, a lighter top edge, a hairline border. */
    private void panel(Canvas c, RectF r, float radius, int alpha) {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor((alpha << 24) | 0x00FFFFFF);   // the shader draws at this alpha, on purpose
        paint.setShader(panelGradient(r.top, r.bottom));
        c.drawRoundRect(r, radius, radius, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(1f));
        paint.setColor(((alpha * 0xD0 / 255) << 24) | 0x1C2736);
        c.drawRoundRect(r, radius, radius, paint);
        paint.setColor(((alpha * 0x60 / 255) << 24) | 0xA8C4E8);
        c.drawLine(r.left + Math.max(radius, dp(2f)), r.top + dp(1f), r.right - Math.max(radius, dp(2f)),
                r.top + dp(1f), paint);
        paint.setStyle(Paint.Style.FILL);
    }

    /** A rounded track with a glossy fill. */
    private void bar(Canvas c, float x, float y, float bw, float bh, float frac, int colour) {
        float r = bh / 2f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x66000000);
        tmp.set(x, y, x + bw, y + bh);
        c.drawRoundRect(tmp, r, r, paint);
        float fw = bw * Math.max(0f, Math.min(1f, frac));
        if (fw < dp(1f)) {
            return;
        }
        float fr = Math.min(r, fw / 2f);
        paint.setColor(colour);
        tmp.set(x, y, x + fw, y + bh);
        c.drawRoundRect(tmp, fr, fr, paint);
        paint.setColor(0x48FFFFFF);
        tmp.set(x + fr * 0.5f, y + dp(1f), x + fw - fr * 0.5f, y + bh * 0.45f);
        if (tmp.width() > 0f) {
            c.drawRoundRect(tmp, fr * 0.5f, fr * 0.5f, paint);
        }
    }

    private void stat(Canvas c, float x, float y, String value, String caption) {
        bold(c, value, x, y - dp(12f), 16f, TEXT, Paint.Align.CENTER);
        label(c, caption, x, y + dp(2f), 8.5f, FAINT, Paint.Align.CENTER);
    }
}
