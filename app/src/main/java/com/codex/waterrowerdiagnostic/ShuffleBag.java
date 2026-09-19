package com.codex.waterrowerdiagnostic;

/**
 * Random order without repeats, for SHUFFLE: every game comes up once before any comes up again,
 * and the same game never plays twice in a row - not even across the boundary between two rounds.
 *
 * <p>It deals from a pool: all the games, or a themed deck (races, chill, sprints), minus any the
 * rower has vetoed this shuffle. Changing the pool re-deals the round from the new pool.
 * Pure Java; tested by {@code ShuffleBagTest}.
 */
final class ShuffleBag {

    private final java.util.Random random;
    private final java.util.ArrayList<Integer> pool = new java.util.ArrayList<>();
    private final java.util.ArrayList<Integer> bag = new java.util.ArrayList<>();
    private int last = -1;

    ShuffleBag(int size, java.util.Random random) {
        this.random = random;
        for (int i = 0; i < size; i++) {
            pool.add(i);
        }
    }

    ShuffleBag(int[] items, java.util.Random random) {
        this.random = random;
        setPool(items);
    }

    /** Deal from these games from now on. The round in hand is thrown away and re-dealt. */
    void setPool(int[] items) {
        pool.clear();
        for (int item : items) {
            if (!pool.contains(item)) {
                pool.add(item);
            }
        }
        bag.clear();
    }

    /**
     * Takes one game out of the pool for good (a veto). Refused when it would leave nothing to
     * deal, so a shuffle can never run dry.
     *
     * @return whether it was removed
     */
    boolean remove(int item) {
        if (pool.size() <= 1 || !pool.contains(item)) {
            return false;
        }
        pool.remove(Integer.valueOf(item));
        bag.remove(Integer.valueOf(item));
        return true;
    }

    int poolSize() {
        return pool.size();
    }

    int next() {
        if (bag.isEmpty()) {
            refill();
        }
        last = bag.remove(0);
        return last;
    }

    /** What comes after the current one, dealing the next round early if this one is spent. */
    int peek() {
        if (bag.isEmpty()) {
            refill();
        }
        return bag.isEmpty() ? -1 : bag.get(0);
    }

    private void refill() {
        bag.addAll(pool);
        java.util.Collections.shuffle(bag, random);
        if (bag.size() > 1 && bag.get(0) == last) {
            java.util.Collections.swap(bag, 0, bag.size() - 1);
        }
    }
}
