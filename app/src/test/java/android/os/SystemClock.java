package android.os;

import java.util.PriorityQueue;

/** Opt-in virtual time for caller tests; otherwise matches the SDK stub's zero clock. */
public final class SystemClock {
    static final ThreadLocal<Timeline> active = new ThreadLocal<>();

    public static long elapsedRealtime() {
        Timeline timeline = active.get();
        return timeline == null ? 0 : timeline.now + System.currentTimeMillis() - timeline.wallAnchor;
    }
    public static long uptimeMillis() { return elapsedRealtime(); }
    public static long elapsedRealtimeNanos() { return elapsedRealtime() * 1_000_000L; }

    public static void beginSensorTest() { active.set(new Timeline()); }
    public static void endSensorTest() { active.remove(); }
    public static void advanceSensorTest(long millis) {
        Timeline timeline = active.get();
        if (timeline == null || millis < 0) throw new IllegalStateException("No active sensor timeline");
        long target = elapsedRealtime() + millis;
        int deliveries = 0;
        while (!timeline.pending.isEmpty() && timeline.pending.peek().at <= target) {
            if (++deliveries > 100_000) throw new IllegalStateException("Runaway handler loop");
            Scheduled task = timeline.pending.remove();
            advanceTo(timeline, task.at);
            task.runnable.run();
        }
        advanceTo(timeline, target);
    }

    private static void advanceTo(Timeline timeline, long target) {
        // Real host pauses advance both production clocks equally. Virtual advancement only adds
        // the remaining distance, and nested callback delivery can never roll elapsed time back.
        long remaining = target - elapsedRealtime();
        if (remaining > 0) timeline.now += remaining;
    }

    static final class Timeline {
        long now = 10_000;
        final long wallAnchor = System.currentTimeMillis();
        long sequence;
        final PriorityQueue<Scheduled> pending = new PriorityQueue<>();
    }
    static final class Scheduled implements Comparable<Scheduled> {
        final Handler handler;
        final Runnable runnable;
        final long at;
        final long sequence;
        Scheduled(Handler handler, Runnable runnable, long at, long sequence) {
            this.handler = handler; this.runnable = runnable; this.at = at; this.sequence = sequence;
        }
        public int compareTo(Scheduled other) {
            int time = Long.compare(at, other.at);
            return time != 0 ? time : Long.compare(sequence, other.sequence);
        }
    }
}
