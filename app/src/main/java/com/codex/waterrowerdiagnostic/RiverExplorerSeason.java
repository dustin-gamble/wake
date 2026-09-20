package com.codex.waterrowerdiagnostic;

/**
 * The river's season, for RIVER EXPLORER (3.23).
 *
 * <p>The rower asked for "seasons: spring flood runs faster, summer slower". A season is two
 * things: how hard the river runs against you, and what the valley looks like. It is taken from the
 * real calendar month, so the river you row in April is the spring river - a reason to come back
 * across the year rather than a setting to fiddle with.
 *
 * <p>{@link #current} is the river's speed for a rower holding 3.85 m/s (this machine's measured
 * median). {@link RiverExplorerGame} scales it by the rower's own typical speed so a slower rower
 * gets a proportionally fair river rather than a wall.
 */
final class RiverExplorerSeason {

    /** Spring: snowmelt, the river in flood, blossom on the water. */
    static final int SPRING = 0;
    /** Summer: low slow water, dry banks, dragonflies. */
    static final int SUMMER = 1;
    /** Autumn: rising again after the rains, leaves coming down. */
    static final int AUTUMN = 2;
    /** Winter: bare trees, snow on the banks, cold grey water. */
    static final int WINTER = 3;

    /** What drifts in the air: blossom, shimmer, leaves, snow. */
    static final int AIR_BLOSSOM = 0;
    static final int AIR_SHIMMER = 1;
    static final int AIR_LEAVES = 2;
    static final int AIR_SNOW = 3;

    final int index;
    final String name;
    /** How the flow reads on the HUD: "IN FLOOD", "LOW AND SLOW"... */
    final String flow;
    /** Metres per second the river runs downhill, for a 3.85 m/s rower. */
    final float current;
    final int foliageA;
    final int foliageB;
    final int grassA;
    final int grassB;
    final int waterA;
    final int waterB;
    final int bank;
    final int airColour;
    final int airKind;
    /** 0 for a full canopy, 1 for bare winter branches. */
    final float bareness;

    private RiverExplorerSeason(int index, String name, String flow, float current,
                                int foliageA, int foliageB, int grassA, int grassB,
                                int waterA, int waterB, int bank,
                                int airColour, int airKind, float bareness) {
        this.index = index;
        this.name = name;
        this.flow = flow;
        this.current = current;
        this.foliageA = foliageA;
        this.foliageB = foliageB;
        this.grassA = grassA;
        this.grassB = grassB;
        this.waterA = waterA;
        this.waterB = waterB;
        this.bank = bank;
        this.airColour = airColour;
        this.airKind = airKind;
        this.bareness = bareness;
    }

    private static final RiverExplorerSeason[] ALL = {
            new RiverExplorerSeason(SPRING, "SPRING", "IN FLOOD", 1.05f,
                    0xFF3E8F3A, 0xFF57A845, 0xFF4F9A3C, 0xFF469038,
                    0xFF3F7A8C, 0xFF386E80, 0xFF8A7A55, 0xFFFFD6E8, AIR_BLOSSOM, 0f),
            new RiverExplorerSeason(SUMMER, "SUMMER", "LOW AND SLOW", 0.30f,
                    0xFF2E6E2A, 0xFF3C7A33, 0xFF6FA03C, 0xFF659535,
                    0xFF2F6E93, 0xFF2A6488, 0xFFA9926A, 0xFF9BE8FF, AIR_SHIMMER, 0f),
            new RiverExplorerSeason(AUTUMN, "AUTUMN", "RISING", 0.62f,
                    0xFFC2661F, 0xFFE0922B, 0xFF7E8A3A, 0xFF74803A,
                    0xFF2B5F7E, 0xFF27556F, 0xFF8C7648, 0xFFE0922B, AIR_LEAVES, 0.25f),
            new RiverExplorerSeason(WINTER, "WINTER", "COLD AND FULL", 0.80f,
                    0xFF4A4438, 0xFF5A5246, 0xFFD8E2EA, 0xFFC9D5DF,
                    0xFF2A4A63, 0xFF264358, 0xFFE4ECF2, 0xFFFFFFFF, AIR_SNOW, 1f),
    };

    static RiverExplorerSeason at(long millis) {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.setTimeInMillis(millis);
        return forMonth(cal.get(java.util.Calendar.MONTH));
    }

    /** {@code month} is 0-based, as {@link java.util.Calendar#MONTH} gives it. */
    static RiverExplorerSeason forMonth(int month) {
        int m = ((month % 12) + 12) % 12;
        if (m >= 2 && m <= 4) {
            return ALL[SPRING];
        }
        if (m >= 5 && m <= 7) {
            return ALL[SUMMER];
        }
        if (m >= 8 && m <= 10) {
            return ALL[AUTUMN];
        }
        return ALL[WINTER];
    }

    static RiverExplorerSeason byIndex(int index) {
        return ALL[((index % 4) + 4) % 4];
    }

    static int count() {
        return ALL.length;
    }
}
