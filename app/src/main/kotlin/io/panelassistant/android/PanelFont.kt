package io.panelassistant.android

import android.content.Context
import android.graphics.Typeface
import java.io.File

/**
 * The faces the app draws its own text in. Panels draw Roboto as their system sans-serif, and the layouts and
 * translations are checked at its width; a vendor that replaces it (the Meta Portal's wider Optimistic) gets the
 * bundled Roboto instead, so its text fits the same space. Everywhere else the system faces stay untouched.
 */
internal object PanelFont {
    /** The bundled variable Roboto, under `assets/`; the pages load it from `/assets/` by the same path. */
    const val ASSET = "fonts/Roboto.ttf"

    /** Whether this panel draws the bundled face: its `/system/etc/fonts.xml` maps sans-serif to another family. */
    val bundled: Boolean by lazy { replacesRoboto(runCatching { File("/system/etc/fonts.xml").readText() }.getOrNull()) }

    /**
     * True when [fontsXml] maps the `sans-serif` family's first font to a file that is not Roboto. A file that is
     * absent or unreadable leaves the system faces in place.
     */
    fun replacesRoboto(fontsXml: String?): Boolean {
        val family = fontsXml?.let { SANS_SERIF.find(it) } ?: return false
        val first = FIRST_FONT.find(family.groupValues[1]) ?: return false
        return !first.groupValues[1].startsWith("Roboto")
    }

    fun regular(context: Context): Typeface = faces(context).regular
    fun medium(context: Context): Typeface = faces(context).medium
    fun light(context: Context): Typeface = faces(context).light

    private class Faces(val regular: Typeface, val medium: Typeface, val light: Typeface)

    @Volatile private var faces: Faces? = null

    private fun faces(context: Context): Faces = faces ?: synchronized(this) {
        faces ?: (if (bundled) bundledFaces(context.applicationContext.assets) else systemFaces()).also {
            if (bundled) android.util.Log.i("PanelFont", "system sans-serif is not Roboto; drawing the bundled Roboto")
            faces = it
        }
    }

    private fun systemFaces() = Faces(
        Typeface.DEFAULT,
        Typeface.create("sans-serif-medium", Typeface.NORMAL),
        Typeface.create("sans-serif-light", Typeface.NORMAL),
    )

    private fun bundledFaces(assets: android.content.res.AssetManager): Faces {
        fun weight(w: Int): Typeface =
            Typeface.Builder(assets, ASSET).setFontVariationSettings("'wght' $w").setWeight(w).build() ?: Typeface.DEFAULT
        return Faces(weight(400), weight(500), weight(300))
    }

    private val SANS_SERIF = Regex("""<family\b[^>]*\bname="sans-serif"[^>]*>(.*?)</family>""", RegexOption.DOT_MATCHES_ALL)
    private val FIRST_FONT = Regex("""<font\b[^>]*>\s*([^<\s]+)""")
}
