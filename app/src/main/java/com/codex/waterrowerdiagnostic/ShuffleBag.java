package com.codex.waterrowerdiagnostic;

/**
 * Random order without repeats, for SHUFFLE: every game comes up once before any comes up again,
 * and the same game never plays twice in a row - not even across the boundary between two rounds.
 * Pure Java; tested by {@code ShuffleBagTest}.
 */
final class ShuffleBag {

    private final int size;
    private final java.util.Random random;
    private final java.util.ArrayList<Integer> bag = new java.util.ArrayList<>();
    private int last = -1;

    ShuffleBag(int size, java.util.Random random) {
        this.size = size;
        this.random = random;
    }

    int next() {
        if (bag.isEmpty()) {
            refill();
        }
        last = bag.remove(0);
        return last;
    }

    /** What comes after the current one, or -1 when the next round has not been dealt yet. */
    int peek() {
        return bag.isEmpty() ? -1 : bag.get(0);
    }

    private void refill() {
        for (int i = 0; i < size; i++) {
            bag.add(i);
        }
        java.util.Collections.shuffle(bag, random);
        if (size > 1 && bag.get(0) == last) {
            java.util.Collections.swap(bag, 0, bag.size() - 1);
        }
    }
}
