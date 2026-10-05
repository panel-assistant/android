package io.panelassistant.android.device.profile

import kotlin.math.hypot

/** Physical size of the live panel, in the orientation of the pixels it was resolved for. */
data class PhysicalDisplayGeometry(
    val widthPx: Int,
    val heightPx: Int,
    val diagonalInches: Double,
    val widthMm: Double,
    val heightMm: Double,
    val ppi: Double,
    val evidence: DisplayGeometryEvidence,
    val evidenceNote: String? = null,
    val variant: String? = null,
)

/** The profile's display facts for one live panel: the physical size when trustworthy evidence selects it,
 *  and the variant's declared factory-base logical DPI. */
data class ProfiledDisplayGeometry(
    val physical: PhysicalDisplayGeometry?,
    val factoryBaseDpi: Int?,
)

/**
 * Selects a profile's physical geometry for a panel. Its inputs are product identity and the physical
 * display mode only: no logical DPI enters, so a rendering-density change cannot move a physical value.
 * No match, or more than one, leaves the geometry absent rather than borrowing another variant's.
 */
object DisplayGeometryResolver {
    private const val MM_PER_INCH = 25.4

    fun resolve(
        display: ProfileDisplay,
        productVersion: String,
        physicalWidthPx: Int,
        physicalHeightPx: Int,
    ): ProfiledDisplayGeometry? {
        if (physicalWidthPx <= 0 || physicalHeightPx <= 0) return null
        if (display.geometry.isEmpty()) {
            val ppi = display.physicalPpi?.takeIf { it > 0 } ?: return null
            return ProfiledDisplayGeometry(fromPpi(physicalWidthPx, physicalHeightPx, ppi.toDouble()), null)
        }
        val entry = display.geometry.filter { it.selects(productVersion, physicalWidthPx, physicalHeightPx) }
            .singleOrNull() ?: return null
        return ProfiledDisplayGeometry(derive(entry, physicalWidthPx, physicalHeightPx), entry.factoryBaseDpi)
    }

    /** Legacy `physical_ppi` migration: the size follows from the live pixels and is always approximate. */
    internal fun fromPpi(widthPx: Int, heightPx: Int, ppi: Double) = PhysicalDisplayGeometry(
        widthPx = widthPx,
        heightPx = heightPx,
        diagonalInches = hypot(widthPx.toDouble(), heightPx.toDouble()) / ppi,
        widthMm = widthPx * MM_PER_INCH / ppi,
        heightMm = heightPx * MM_PER_INCH / ppi,
        ppi = ppi,
        evidence = DisplayGeometryEvidence.APPROXIMATE,
        evidenceNote = "Migrated from the profile's legacy physical_ppi value.",
    )

    /** The entry's size, turned to match [widthPx]×[heightPx] when the entry is declared the other way round. */
    internal fun derive(entry: ProfileDisplayGeometry, widthPx: Int, heightPx: Int): PhysicalDisplayGeometry? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val pixelDiagonal = hypot(widthPx.toDouble(), heightPx.toDouble())
        val turned = entry.widthPx != widthPx
        val diagonalMm: Double
        val widthMm: Double
        val heightMm: Double
        val declaredWidthMm = entry.activeWidthMm
        val declaredHeightMm = entry.activeHeightMm
        val declaredDiagonal = entry.activeDiagonalIn
        when {
            declaredDiagonal != null && declaredWidthMm == null && declaredHeightMm == null -> {
                diagonalMm = declaredDiagonal * MM_PER_INCH
                widthMm = diagonalMm * widthPx / pixelDiagonal
                heightMm = diagonalMm * heightPx / pixelDiagonal
            }
            declaredDiagonal == null && declaredWidthMm != null && declaredHeightMm != null -> {
                widthMm = (if (turned) declaredHeightMm else declaredWidthMm).toDouble()
                heightMm = (if (turned) declaredWidthMm else declaredHeightMm).toDouble()
                diagonalMm = hypot(widthMm, heightMm)
            }
            else -> return null
        }
        if (diagonalMm <= 0.0) return null
        val diagonalInches = diagonalMm / MM_PER_INCH
        return PhysicalDisplayGeometry(
            widthPx = widthPx,
            heightPx = heightPx,
            diagonalInches = diagonalInches,
            widthMm = widthMm,
            heightMm = heightMm,
            ppi = pixelDiagonal / diagonalInches,
            evidence = entry.evidence,
            evidenceNote = entry.evidenceNote,
            variant = entry.variant,
        )
    }

    private fun ProfileDisplayGeometry.selects(productVersion: String, widthPx: Int, heightPx: Int): Boolean {
        val pixels = (this.widthPx == widthPx && this.heightPx == heightPx) ||
            (this.widthPx == heightPx && this.heightPx == widthPx)
        val product = productVersionPrefixes.isEmpty() ||
            productVersionPrefixes.any { productVersion.startsWith(it, ignoreCase = true) }
        return pixels && product
    }
}
