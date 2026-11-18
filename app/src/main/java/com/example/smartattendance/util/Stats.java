package com.example.smartattendance.util;

/** Attendance arithmetic, built around the usual 75% minimum. */
public final class Stats {
    public static final int MINIMUM_PERCENT = 75;

    private Stats() {}

    /** Whole-number percentage, or -1 when no classes have been held yet. */
    public static int percent(int attended, int total) {
        if (total <= 0) return -1;
        return (int) Math.floor(attended * 100.0 / total);
    }

    public static boolean isSafe(int attended, int total) {
        return total <= 0 || attended * 100 >= MINIMUM_PERCENT * total;
    }

    /** Consecutive classes needed to get back to 75%: smallest x with (a+x)/(t+x) >= 3/4, i.e. x >= 3t - 4a. */
    public static int classesNeeded(int attended, int total) {
        return Math.max(0, 3 * total - 4 * attended);
    }

    /** Classes that can be missed while staying at 75%: largest y with a/(t+y) >= 3/4, i.e. y <= (4a - 3t) / 3. */
    public static int classesSpare(int attended, int total) {
        return Math.max(0, (4 * attended - 3 * total) / 3);
    }
}
