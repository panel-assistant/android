package android.webkit;

/** Supplies an Android connection-refused callback on the plain JVM. */
public final class DashboardRefusedWebResourceError extends WebResourceError {
    @Override public CharSequence getDescription() {
        return "net::ERR_CONNECTION_REFUSED";
    }

    @Override public int getErrorCode() {
        return WebViewClient.ERROR_CONNECT;
    }
}
