package android.os;

/** Posts execute only when a caller test explicitly advances its thread's sensor timeline. */
public class Handler {
    private final Looper looper;
    public Handler() { this(null); }
    public Handler(Looper looper) { this.looper = looper; }
    public Looper getLooper() { return looper; }
    public boolean post(Runnable runnable) { return postDelayed(runnable, 0); }
    public boolean postDelayed(Runnable runnable, long delay) {
        SystemClock.Timeline timeline = SystemClock.active.get();
        if (timeline == null) return false;
        timeline.pending.add(new SystemClock.Scheduled(this, runnable,
            SystemClock.elapsedRealtime() + Math.max(0, delay), timeline.sequence++));
        return true;
    }
    public void removeCallbacks(Runnable runnable) {
        SystemClock.Timeline timeline = SystemClock.active.get();
        if (timeline != null) timeline.pending.removeIf(task -> task.handler == this && task.runnable == runnable);
    }
    public void removeCallbacksAndMessages(Object token) {
        SystemClock.Timeline timeline = SystemClock.active.get();
        if (timeline != null) timeline.pending.removeIf(task -> task.handler == this);
    }
}
