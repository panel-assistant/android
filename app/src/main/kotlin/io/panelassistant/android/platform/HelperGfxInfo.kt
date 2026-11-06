package io.panelassistant.android.platform

/** Cumulative SELF HWUI counters. [since] is a canonical unsigned-64 nanosecond epoch token. */
data class HelperGfxInfo(val since: String, val total: Long, val janky: Long)
