package io.github.maxlyth.hapaneld

import android.graphics.Bitmap
import android.graphics.Color

/**
 * The panel's QR code, shared rather than duplicated.
 *
 * It lived as a private member of `MainActivity` while the standing screen was the only surface that
 * needed one. A second caller makes that the wrong home, for the same reason `statusBrandMark` is shared:
 * one edit to the encoding — its size, its colours, its error correction — must not be able to leave a
 * screen behind on the old one.
 *
 * A QR earns its place only where the useful next step is genuinely somewhere else. On a panel that is
 * merely showing an address somebody is already looking at, it is decoration; the entity-filter wait
 * screen says so in as many words and prints the address instead.
 */

/** Encode [text] as a square QR [sizePx] pixels on a side, or null if it cannot be encoded. */
internal fun qrBitmap(text: String, sizePx: Int): Bitmap? = try {
    val bits = com.google.zxing.qrcode.QRCodeWriter()
        .encode(text, com.google.zxing.BarcodeFormat.QR_CODE, sizePx, sizePx)
    Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888).also { qr ->
        for (x in 0 until sizePx) for (y in 0 until sizePx)
            qr.setPixel(x, y, if (bits.get(x, y)) Color.BLACK else Color.WHITE)
    }
} catch (e: Exception) {
    // A missing QR is a cosmetic degrade, never a failure: every screen that draws one also prints the
    // address in full, so the panel loses a convenience rather than the instruction.
    null
}

/**
 * The page a blocked screen should send somebody to on their phone, or null to send them nowhere.
 *
 * Exhaustive on purpose, in the same style as [providerRepairableAdmission] and [admissionRetryClass]: a
 * new outcome must be given an answer rather than inheriting `null` from an `else`, because inheriting
 * silently is how a screen quietly stops offering the one thing that would have helped.
 *
 * Only the two credential verdicts qualify, and the reason is specific rather than general. **A QR is not
 * a feature.** It is worth the space only where the repair genuinely means working through a page that is
 * awkward on a 480-pixel screen — here, finding the Home Assistant connection settings among everything
 * else on the Configure page and typing a password into a panel with no keyboard. Every other blocked
 * verdict needs automatic recovery, a Home Assistant server change, or a WebView provider change;
 * opening Configure on a phone does not resolve those conditions.
 */
internal fun configureQrPath(outcome: AdmissionOutcome?): String? = when (outcome) {
    // The sign-in row itself, not the top of the page: the anchor scrolls and flashes the exact control,
    // which is the whole reason a phone is easier than the panel here.
    AdmissionOutcome.CREDENTIAL_REFUSED,
    AdmissionOutcome.SIGN_IN_REQUIRED,
    -> "/configure#cfg-ha-oauth"

    AdmissionOutcome.BRIDGE_UNAVAILABLE,
    AdmissionOutcome.TRANSPORT_FAILED,
    AdmissionOutcome.DASHBOARD_LIST_UNREADABLE,
    AdmissionOutcome.SIGN_IN_PAGE_UNREACHABLE,
    AdmissionOutcome.BRIDGE_HANDSHAKE_MISSED,
    AdmissionOutcome.BRIDGE_ATTACH_FAILED,
    AdmissionOutcome.VERSION_UNVERIFIABLE,
    AdmissionOutcome.NO_LEGAL_DASHBOARD,
    AdmissionOutcome.UNSUPPORTED_HA,
    null,
    -> null
}
