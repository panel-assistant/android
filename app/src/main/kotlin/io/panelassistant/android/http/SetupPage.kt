package io.panelassistant.android.http

import io.panelassistant.android.i18n.Strings as AppStrings

/**
 * The guided setup page — the surface the panel's QR code points at, and the primary way a new panel
 * is commissioned.
 *
 * A separate route rather than a mode of Configure. Configure is a schema-driven wall of every setting
 * with one save-everything bar, which is the right tool for an owner changing one thing and the wrong
 * one for somebody who has never seen this product: nothing there says which four fields matter, in
 * what order, or that a save is required at all. It is also pinned by contract tests that assert its
 * source text, so folding a wizard into it would put unrelated risk on the page every existing user
 * relies on.
 *
 * The markup here is only a frame. Steps are rendered by setup.js from GET /api/v1/setup, so the panel
 * and the browser read the same authority and cannot disagree about what comes next.
 */
internal fun setupBody(strings: AppStrings, preserveExplicitEnglish: Boolean, embedded: Boolean = false): String = """
<div class="wiz" id="wiz">
  <ol class="wiz-dots" id="wiz-dots" aria-label="${esc(strings.get("setup.frame.progress_label"))}"></ol>
  <div id="wiz-step" class="wiz-step" role="region" aria-live="polite" aria-atomic="false">
    <p class="muted">${esc(strings.get("setup.frame.loading"))}</p>
  </div>
  <p class="wiz-escape"><a href="${setupHref("configure", strings, preserveExplicitEnglish)}"${if (embedded) "" else """ onclick="document.cookie='wiz_escape=1;path=/;max-age=3600'""""}>${esc(strings.get("setup.frame.skip_exit"))}</a></p>
</div>
<script src="assets/setup.js"></script>"""
