package io.panelassistant.android.sensors

/**
 * The clear-floor drift a calibration on this acquisition route must outlive, in the route's own raw
 * units. An infrared reflective sensor's clear floor moves with ambient light and temperature over
 * hours and days, while a calibration session sees five seconds of it, so the drift band cannot be
 * measured in-session and belongs here as a measured property of the route.
 *
 * Measured over four days of continuous two-second polling on six live units, as the
 * span from each unit's minimum to its 99.9th percentile. The raw16 sysfs route spanned 224 counts
 * on its one unit while its hourly median moved 82. Android HAL ranged counts spanned 47, 56, 62 and
 * 68 counts on four units; a fifth spanned 161, but nothing in four days ever moved that unit more
 * than 144 counts above its own floor, so it cannot hold any margined boundary and cannot set this
 * constant. There is no multi-day series for the VI530x time-of-flight route, whose millimetres are
 * neither this unit nor this physics, so it keeps the unmargined behaviour until one exists.
 */
internal fun proximityDriftMargin(acquisition: ProximityAcquisition): Float = when (acquisition) {
    ProximityAcquisition.STK_RAW -> 300f
    ProximityAcquisition.ANDROID_HAL -> 100f
    ProximityAcquisition.VI530X, ProximityAcquisition.GPIO, ProximityAcquisition.ANDROID_KEYS, ProximityAcquisition.ABSENT -> 0f
}

/**
 * Whether a calibration saved before the band, and sitting inside it, is kept as it was saved rather than set
 * aside for a new setup. A setup is only worth asking for where it can save something better. On the raw16 route
 * a person reads thousands of counts above the floor, so a new setup clears the band easily. Through an
 * Android HAL a person where they normally stand reads tens of counts above it (an NSPanel 86's saved
 * calibration separates by about 26, against a 100-count band), so the new setup would refuse them, and
 * setting their calibration aside would leave the owner with none. New setups on every route still hold the band.
 */
internal fun proximityKeepsStoredInsideDriftBand(acquisition: ProximityAcquisition): Boolean =
    acquisition == ProximityAcquisition.ANDROID_HAL

/**
 * The range above which this route's readings are counts, the band's unit, rather than centimetres. The raw16
 * sysfs route is counts by construction. An Android HAL is centimetres up to its declared maximum range by
 * contract, so only a reading beyond that range proves counts; the measured NSPanel 86 HAL declares 9 and
 * reads in the hundreds.
 */
internal fun proximityDriftDeclaredRange(acquisition: ProximityAcquisition, halMaximumRange: Float?): Float = when (acquisition) {
    ProximityAcquisition.ANDROID_HAL -> halMaximumRange?.takeIf { it.isFinite() && it >= 0f } ?: Float.POSITIVE_INFINITY
    else -> 0f
}
