package io.github.maxlyth.hapaneld

/**
 * The one rule for reading durable configuration as evidence that a panel predates setup tracking.
 *
 * Two independent consumers ask this question, for different reasons and — deliberately — over
 * different evidence:
 *
 *  - [Config.migrateSetupQuestionsForExistingInstall] decides, once per install, whether to stamp the
 *    setup questions answered so an upgraded panel is never held on a question it predates;
 *  - the HTTP layer's `setupJourneyInputs` decides, per request, whether to infer identity confirmed
 *    and force-satisfy the later questions for the same reason.
 *
 * They diverge on their third term (the migration counts `dashboard_entity_learning`, the journey
 * counts `dashboard_package`), and that divergence is older than this function and out of its scope —
 * so each caller still supplies its own [otherEvidence] rather than having one silently imposed on it.
 *
 * What they must NOT diverge on is the Home Assistant URL, which is why that term lives here alone.
 * A handed-over URL is written by the Panel Assistant integration on a panel whose wizard has not been
 * opened yet, by a release that *does* track setup — so it is not evidence of an older install, and
 * counting it makes a brand-new panel look pre-existing.
 *
 * The consequence of getting this wrong is durable and one-way, which is why it is a shared function
 * rather than a repeated expression. The migration `commit()`s its findings: on a freshly handed-over
 * panel, any service restart before the operator presses Continue on the Name step would permanently
 * stamp the entity-filter and home-dashboard questions answered, leaving the renderer unfiltered and
 * the wizard framing a genuine first run as a repair. No later read of the provenance marker can
 * retract a committed preference. The migration's own comment records this class of defect reaching
 * hardware on 2026-07-26 by a different route, which is why its `setupIdentityConfirmed` gate exists;
 * a handed-over URL is written before that gate can ever be true, so it walks straight past it.
 */
internal fun panelConfiguredBeforeSetupTracking(
    haUrl: String,
    haSetupHandover: Boolean,
    otherEvidence: Boolean,
): Boolean = otherEvidence || (haUrl.isNotBlank() && !haSetupHandover)
