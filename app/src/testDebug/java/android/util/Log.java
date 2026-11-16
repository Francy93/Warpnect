package android.util;

/** Host-test framework shim only. Never packaged; protocol/AAR classes are not shadowed. */
public final class Log {
    private Log() {}
    public static int d(String tag, String message) { return 0; }
    public static int e(String tag, String message) { return 0; }
    public static int e(String tag, String message, Throwable failure) { return 0; }
    public static int w(String tag, String message) { return 0; }
    public static int w(String tag, String message, Throwable failure) { return 0; }
    public static int i(String tag, String message) { return 0; }
    public static int v(String tag, String message) { return 0; }
}
