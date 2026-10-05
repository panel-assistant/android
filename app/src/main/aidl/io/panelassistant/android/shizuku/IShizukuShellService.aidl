package io.panelassistant.android.shizuku;

import android.os.ParcelFileDescriptor;

/** Narrow shell-UID surface. There is deliberately no generic command or filesystem method. */
interface IShizukuShellService {
    int protocolVersion();
    int identityUid();
    ParcelFileDescriptor captureScreenshot();
    boolean inputKey(int keyCode);
    boolean inputTap(int x, int y);
    String readDensity();
    boolean setDensity(int dpi);
    boolean resetDensity();
    String readFontScale();
    boolean setFontScale(float scale);
    boolean resetFontScale();
    String readRoomClimate();
    String installApk(
        in ParcelFileDescriptor source,
        long length,
        boolean allowDowngrade,
        long timeoutMs
    );
}
