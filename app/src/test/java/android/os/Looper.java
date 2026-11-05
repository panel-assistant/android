package android.os;

/** The default null loopers preserve the plain JVM SDK stub behavior outside opt-in tests. */
public final class Looper {
    public static Looper getMainLooper() { return null; }
    public static Looper myLooper() { return null; }
}
