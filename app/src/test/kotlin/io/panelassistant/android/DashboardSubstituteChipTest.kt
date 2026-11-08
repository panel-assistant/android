package io.panelassistant.android

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.panelassistant.android.dashboard.EntityLearningProtocol.HomeDashboardResolution
import io.panelassistant.android.dashboard.EntityLearningProtocol.HomeDashboardSource
import io.panelassistant.android.i18n.CatalogueLoader
import io.panelassistant.android.util.DashboardPath
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class DashboardSubstituteChipTest {
    @Test fun `substitute notice keeps configured path literal and clears when no substitute is shown`() {
        // The mockable Android jar cannot construct views. Only framework leaves are stand-ins;
        // DashboardSubstituteChip.update and CompactNotice.show and hide all execute unchanged.
        val view = DashboardRendererRig.allocate(RecordingNoticeView::class.java)
        val notice = DashboardRendererRig.allocate(CompactNotice::class.java)
        val mark = DashboardRendererRig.allocate(RecordingMark::class.java).apply {
            params = DashboardRendererRig.allocate(LinearLayout.LayoutParams::class.java)
        }
        fun fill(name: String, value: Any) = CompactNotice::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(notice, value)
        fill("view", view)
        fill("title", DashboardRendererRig.allocate(TextView::class.java))
        fill("detail", DashboardRendererRig.allocate(TextView::class.java))
        fill("mark", mark)
        fill("scale", 1f)
        // Source-text reason: shipped catalogue assets are runtime input data, not source assertions.
        val strings = CatalogueLoader { File("src/main/assets", it).readText() }.strings("en")
        val chip = DashboardSubstituteChip::class.java.getDeclaredConstructor(
            CompactNotice::class.java, String::class.java, String::class.java,
        ).apply { isAccessible = true }.newInstance(
            notice, strings.get("shell.dashboard_substitute.title"), strings.get("shell.dashboard_substitute.detail"),
        )
        val substitute = HomeDashboardResolution("/lovelace", HomeDashboardSource.SYSTEM_DEFAULT)

        assertEquals("/missing?x={shown}", DashboardPath.canonical("/missing?x={shown}", preserveRoute = true))
        chip.update("  /missing?x={shown}  ", substitute)

        assertEquals(View.VISIBLE, view.visibility)
        assertEquals(
            "Dashboard not found. /missing?x={shown} is not on this Home Assistant, so the panel shows /lovelace.",
            view.contentDescription,
        )

        chip.update("/lovelace", HomeDashboardResolution("/lovelace", HomeDashboardSource.EXPLICIT))
        assertEquals("an explicitly resolved dashboard needs no notice", View.GONE, view.visibility)
        chip.update("/missing", substitute)
        assertEquals(View.VISIBLE, view.visibility)
        chip.update("/missing", null)
        assertEquals("a missing resolution hides the previous notice", View.GONE, view.visibility)
    }

    private class RecordingNoticeView : LinearLayout(null) {
        private var shownVisibility = View.GONE
        private var description: CharSequence? = null
        override fun setVisibility(visibility: Int) { shownVisibility = visibility }
        override fun getVisibility(): Int = shownVisibility
        override fun setContentDescription(contentDescription: CharSequence?) { description = contentDescription }
        override fun getContentDescription(): CharSequence? = description
    }

    private class RecordingMark : ImageView(null) {
        lateinit var params: LinearLayout.LayoutParams
        override fun getLayoutParams(): ViewGroup.LayoutParams = params
    }
}
