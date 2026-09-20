package com.codex.waterrowerdiagnostic;

/**
 * Rocket Launch's hangar: the credits a mission pays out, the parts they buy, and the rank the
 * campaign promotes you through.
 *
 * <p>Kept out of {@link RocketLaunchGame} because it is plain state and arithmetic - no canvas, no
 * frame loop - and because the game file is already long. Everything persists through
 * {@link PersonalBests} so the hangar survives with the laptop off.
 *
 * <p>Part effects are multipliers the game applies where the constant used to be, so an upgrade
 * genuinely changes the flight: a better engine lowers the hover the rower has to hold, a bigger
 * tank adds strokes of fuel, fins bleed the shear stress more slowly, and legs survive a harder
 * arrival.
 */
final class RocketLaunchParts {

    static final int ENGINE = 0;
    static final int TANK = 1;
    static final int FINS = 2;
    static final int LEGS = 3;
    static final int PARTS = 4;
    static final int MAX_LEVEL = 3;

    static final String[] NAME = {"ENGINE", "TANK", "FINS", "LEGS"};
    static final String[] EFFECT = {
            "hover -5% a level",
            "fuel +10% a level",
            "shear stress -18% a level",
            "land 3 m/s harder a level"};
    /** Cost of level 1, 2, 3. */
    private static final int[] COST = {70, 140, 240};

    static final String[] RANK = {
            "CADET", "PILOT", "ASTRONAUT", "FLIGHT ENGINEER", "CAPTAIN", "COMMANDER"};
    private static final int[] RANK_XP = {0, 60, 160, 320, 560, 900};
    /** Credits handed over with a promotion, so a rank is worth something and not just a word. */
    static final int PROMOTION_BONUS = 50;

    private static final String CREDITS_KEY = "rocket.credits";
    private static final String XP_KEY = "rocket.xp";
    private static final String[] PART_KEY = {
            "rocket.part.engine", "rocket.part.tank", "rocket.part.fins", "rocket.part.legs"};

    private final PersonalBests bests;
    private final int[] level = new int[PARTS];
    private int credits;
    private int xp;

    RocketLaunchParts(PersonalBests bests) {
        this.bests = bests;
        credits = Math.max(0, Math.round(bests.get(CREDITS_KEY, 0f)));
        xp = Math.max(0, Math.round(bests.get(XP_KEY, 0f)));
        for (int i = 0; i < PARTS; i++) {
            level[i] = Math.max(0, Math.min(MAX_LEVEL, Math.round(bests.get(PART_KEY[i], 0f))));
        }
    }

    int level(int part) {
        return level[part];
    }

    int credits() {
        return credits;
    }

    int xp() {
        return xp;
    }

    /** Cost of the next level of this part, or -1 when it is already maxed. */
    int cost(int part) {
        int l = level[part];
        return l >= MAX_LEVEL ? -1 : COST[l];
    }

    boolean canAfford(int part) {
        int c = cost(part);
        return c > 0 && credits >= c;
    }

    /** Spends the credits and fits the part. False when maxed or too expensive. */
    boolean buy(int part) {
        int c = cost(part);
        if (c < 0 || credits < c) {
            return false;
        }
        credits -= c;
        level[part]++;
        bests.putFloat(CREDITS_KEY, credits);
        bests.putFloat(PART_KEY[part], level[part]);
        return true;
    }

    /**
     * Pays out a finished mission.
     *
     * @return true when the XP crossed into a new rank, which also pays {@link #PROMOTION_BONUS}
     */
    boolean award(int payout, int earnedXp) {
        int before = rankIndex();
        credits += Math.max(0, payout);
        xp += Math.max(0, earnedXp);
        boolean promoted = rankIndex() > before;
        if (promoted) {
            credits += PROMOTION_BONUS;
        }
        bests.putFloat(CREDITS_KEY, credits);
        bests.putFloat(XP_KEY, xp);
        return promoted;
    }

    int rankIndex() {
        int r = 0;
        for (int i = 0; i < RANK_XP.length; i++) {
            if (xp >= RANK_XP[i]) {
                r = i;
            }
        }
        return r;
    }

    String rankName() {
        return RANK[rankIndex()];
    }

    /** XP still needed for the next rank, or 0 at the top. */
    int xpToNext() {
        int r = rankIndex();
        return r >= RANK.length - 1 ? 0 : RANK_XP[r + 1] - xp;
    }

    /** 0..1 through the current rank. 1 at the top rank. */
    float rankProgress() {
        int r = rankIndex();
        if (r >= RANK.length - 1) {
            return 1f;
        }
        int lo = RANK_XP[r];
        int hi = RANK_XP[r + 1];
        return Math.max(0f, Math.min(1f, (float) (xp - lo) / (hi - lo)));
    }

    /* ---------- what the flight asks for ---------- */

    /** Multiplies every stage's hover share: a stronger engine needs less to hold you up. */
    float hoverMul() {
        return 1f - 0.05f * level[ENGINE];
    }

    /** Multiplies the fuel budget. */
    float fuelMul() {
        return 1f + 0.10f * level[TANK];
    }

    /** Multiplies how fast airframe stress builds in a shear layer. */
    float stressMul() {
        return 1f - 0.18f * level[FINS];
    }

    /** Extra touchdown speed the legs absorb, m/s (full value for the booster, a third for the moon). */
    float landingBonusMps() {
        return 3f * level[LEGS];
    }
}
