package android.os;

/** Sensor test handlers run on the deterministic caller timeline, without a host background thread. */
public class HandlerThread extends Thread {
    public HandlerThread(String name) { super(name); }
    @Override public synchronized void start() { }
    public Looper getLooper() { return null; }
    public boolean quitSafely() { return false; }
}
