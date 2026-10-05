package io.panelassistant.android.dashboard

import org.json.JSONArray
import org.json.JSONObject

/**
 * Conservative dependency analysis for selector-driven dashboard cards.
 *
 * This deliberately computes a structural superset rather than reproducing a card's dynamic
 * filtering. A selector is safe to subscribe only when that superset is bounded. Dynamic state,
 * attribute, sorting, and display-limit clauses never make the superset smaller.
 */
object DashboardConfigurationLint {
    const val ANALYZER_POLICY_VERSION = 5
    const val SELECTOR_ENTITY_LIMIT = 64
    const val SELECTOR_TOTAL_LIMIT = 128

    enum class IssueType(
        val wireName: String,
        val severity: String = "error",
        val blocking: Boolean = true,
        val ignorable: Boolean = true,
    ) {
        UNBOUNDED_SELECTOR("unbounded_selector"),
        BROAD_SELECTOR("broad_selector"),
        SELECTOR_BUDGET("selector_budget"),
        DIAGNOSTIC_LIMIT("diagnostic_limit", ignorable = false),
        LIMITED_SUPPORT("limited_support", severity = "warning", blocking = false, ignorable = false),
        COMPATIBILITY_GAP("compatibility_gap", severity = "warning", blocking = false, ignorable = false),
        RUNTIME_COVERAGE("runtime_coverage", severity = "warning", blocking = false, ignorable = false),
    }

    /** Closed, prose-independent identities used only to select localized issue presentation. */
    enum class PresentationCode(val wireName: String) {
        DIAGNOSTIC_LIMIT("diagnostic-limit"),
        TEMPLATE_SELECTOR("template-selector"),
        AUTO_ENTITIES_SEED_ROW_DYNAMIC("auto-entities-seed-row-dynamic"),
        AUTO_ENTITIES_TYPED_ROW_DYNAMIC("auto-entities-typed-row-dynamic"),
        AUTO_ENTITIES_OPTIONS_DYNAMIC("auto-entities-options-dynamic"),
        AUTO_ENTITIES_OPTIONS_JAVASCRIPT("auto-entities-options-javascript"),
        SELECTOR_MISSING_INCLUDE("selector-missing-include"),
        SELECTOR_UNBOUNDED_OR_DYNAMIC("selector-unbounded-or-dynamic"),
        SELECTOR_REGISTRY_INCOMPLETE("selector-registry-incomplete"),
        SELECTOR_BROAD("selector-broad"),
        AREA_MISSING_ID("area-missing-id"),
        AREA_REGISTRY_INCOMPLETE("area-registry-incomplete"),
        AREA_BROAD("area-broad"),
        MAP_DYNAMIC_ENUMERATION("map-dynamic-enumeration"),
        MAP_ZONE_REGISTRY_INCOMPLETE("map-zone-registry-incomplete"),
        MAP_ZONES_BROAD("map-zones-broad"),
        STREAMLINE_RUNTIME_COVERAGE("streamline-runtime-coverage"),
        DECLUTTERING_TEMPLATE_EXPANSION("decluttering-template-expansion"),
        BUTTON_CARD_LIMITED_SUPPORT("button-card-limited-support"),
        BUTTON_CARD_DYNAMIC_FEATURES("button-card-dynamic-features"),
        BUBBLE_CARD_RUNTIME_COVERAGE("bubble-card-runtime-coverage"),
        KIOSK_MODE_LIMITED_SUPPORT("kiosk_mode-limited-support"),
        KIOSK_MODE_DYNAMIC_JAVASCRIPT("kiosk_mode-dynamic-javascript"),
        DASHBOARD_STRATEGY("dashboard-strategy"),
        SELECTOR_TOTAL_BUDGET("selector-total-budget");

        init {
            require(EntityCatalogIssuePersistence.isValidPresentationCode(wireName))
        }
    }

    data class Issue(
        val type: IssueType,
        val viewTitle: String,
        val viewPath: String,
        val cardTitle: String?,
        val sourceLocations: List<String>,
        val ruleSummary: String,
        val candidateCount: Int?,
        val limit: Int?,
        val reason: String,
        val recommendation: String,
        val fingerprint: String,
        val presentationCode: PresentationCode,
        val viewTitleIndex: Int? = null,
        val cardTitleHacsKiosk: Boolean = false,
    ) {
        val severity: String get() = type.severity
        val blocking: Boolean get() = type.blocking
        val ignorable: Boolean get() = type.ignorable

        fun toJson(): JSONObject = JSONObject()
            .put("severity", severity)
            .put("type", type.wireName)
            .put("blocking", blocking)
            .put("ignorable", ignorable)
            .put("view_title", viewTitle)
            .put("view_path", viewPath)
            .apply { viewTitleIndex?.let { put("view_title_index", it) } }
            .put("card_title", cardTitle ?: JSONObject.NULL)
            .apply { if (cardTitleHacsKiosk) put("card_title_hacs_kiosk", true) }
            .put("source_locations", JSONArray(sourceLocations))
            .put("rule_summary", ruleSummary)
            .put("candidate_count", candidateCount ?: JSONObject.NULL)
            .put("limit", limit ?: JSONObject.NULL)
            .put("reason", reason)
            .put("recommendation", recommendation)
            .put("fingerprint", fingerprint)
            .put("presentation_code", presentationCode.wireName)
    }

    data class Result(
        /** Concrete IDs contributed only by individually bounded selectors. */
        val safeEntityIds: Set<String>,
        val issues: List<Issue>,
        val dashboardRevision: String,
    ) {
        val blocking: Boolean get() = issues.any(Issue::blocking)
    }

    internal data class RegistryRequirements(
        val entities: Boolean = false,
        val areas: Boolean = false,
        val devices: Boolean = false,
        val floors: Boolean = false,
        val labels: Boolean = false,
    ) {
        init {
            require(!areas || entities && devices)
            require(!floors || entities && devices && areas)
            require(!labels || entities && devices)
        }

        val any: Boolean get() = entities || areas || devices || floors || labels
    }

    private data class Metadata(
        val areas: Set<String>,
        val floors: Set<String>,
        val labels: Set<String>,
        val friendlyName: String,
    )
    private data class View(val title: String, val path: String, val titleIndex: Int? = null)
    private data class Source(
        val location: String,
        val cardTitle: String?,
        val injectedEntityIds: Set<String> = emptySet(),
        val cardTitleHacsKiosk: Boolean = false,
    )
    private data class Rule(
        val domains: Set<String>,
        val entityPatterns: Set<String>,
        val areas: Set<String>,
        val floors: Set<String>,
        val labels: Set<String>,
        val namePattern: String?,
        val usable: Boolean,
        val dynamic: Boolean,
        val requiresRegistry: Boolean,
        val signature: String,
    )
    private data class Finding(
        val type: IssueType,
        val view: View,
        val source: Source,
        val signature: String,
        val summary: String,
        val candidateCount: Int?,
        val limit: Int?,
        val reason: String? = null,
        val recommendation: String? = null,
        val presentationCode: PresentationCode,
    )

    private data class FindingKey(val viewPath: String, val type: IssueType, val signature: String)
    private data class FindingGroup(
        val first: Finding,
        val sourceLocations: LinkedHashSet<String> = linkedSetOf(),
        val cardTitles: LinkedHashSet<String> = linkedSetOf(),
    )

    /** Retain only the diagnostic surface that can reach the bounded API payload. */
    private class FindingAccumulator {
        private val blocking = linkedMapOf<FindingKey, FindingGroup>()
        private val advisory = linkedMapOf<FindingKey, FindingGroup>()
        private var overflow: FindingGroup? = null

        operator fun plusAssign(finding: Finding) {
            val bucket = if (finding.type.blocking) blocking else advisory
            val key = FindingKey(finding.view.path, finding.type, finding.signature)
            val group = bucket[key] ?: run {
                if (bucket.size >= EntityCatalogIssuePersistence.MAX_ISSUE_GROUPS) {
                    if (finding.type.blocking && overflow == null) {
                        overflow = FindingGroup(
                            Finding(
                                type = IssueType.DIAGNOSTIC_LIMIT,
                                view = View("Dashboard", "dashboard"),
                                source = Source("dashboard", null),
                                signature = "blocking-diagnostic-limit",
                                summary = "Dashboard has more safety checks than can be reviewed",
                                candidateCount = null,
                                limit = null,
                                reason = "More than ${EntityCatalogIssuePersistence.MAX_ISSUE_GROUPS} distinct blocking checks were found.",
                                recommendation = "Simplify the dashboard or split it into smaller dashboards before enabling automatic entity filtering.",
                                presentationCode = PresentationCode.DIAGNOSTIC_LIMIT,
                            ),
                        ).also { it.sourceLocations += "dashboard" }
                    }
                    return
                }
                FindingGroup(finding).also { bucket[key] = it }
            }
            if (group.sourceLocations.size < EntityCatalogIssuePersistence.MAX_SOURCES_PER_GROUP) {
                group.sourceLocations += finding.source.location
            }
            finding.source.cardTitle?.let { title ->
                if (group.cardTitles.size < 2) group.cardTitles += title
            }
        }

        fun retainedGroups(): List<FindingGroup> = listOfNotNull(overflow) + blocking.values + advisory.values
    }

    /**
     * The durable identity of a dashboard configuration, written at commit and compared by the held
     * renderer's change probe. One definition, over canonical JSON, so semantic key ordering cannot
     * create a second revision or force needless re-approval, and a probe cannot disagree with a scan.
     */
    fun revision(configJson: String): String = revision(JSONObject(configJson))

    private fun revision(root: JSONObject): String =
        EntityLearningProtocol.hash(EntityLearningProtocol.canonical(root))

    fun analyze(
        configJson: String,
        catalog: Collection<String>,
        metadataJson: Map<String, String>,
        friendlyNames: Map<String, String> = emptyMap(),
        areaRegistryEntities: Map<String, Set<String>> = emptyMap(),
        registryMetadataComplete: Boolean = false,
    ): Result {
        val root = JSONObject(configJson)
        val dashboardRevision = revision(root)
        val catalogIds = catalog.asSequence().map(String::lowercase)
            .filter(ENTITY_ID::matches).distinct().sorted().toList()
        val registryIds = metadataJson.keys.asSequence().map(String::lowercase)
            .filter(ENTITY_ID::matches).distinct().sorted().toList()
        val knownIds = (catalogIds + registryIds).distinct().sorted()
        val metadata = knownIds.associateWith { id ->
            parseMetadata(metadataJson[id], friendlyNames[id].orEmpty())
        }
        val safe = sortedSetOf<String>()
        val findings = FindingAccumulator()
        val boundedSourceLocations = linkedSetOf<String>()

        fun addCandidates(
            view: View,
            source: Source,
            signature: String,
            summary: String,
            candidates: Collection<String>,
            presentationCode: PresentationCode,
        ): Set<String> {
            val normalized = candidates.asSequence().map(String::lowercase)
                .filter(ENTITY_ID::matches).toSortedSet()
            if (normalized.size > SELECTOR_ENTITY_LIMIT) {
                findings += Finding(
                    IssueType.BROAD_SELECTOR, view, source, signature,
                    summary, normalized.size, SELECTOR_ENTITY_LIMIT,
                    presentationCode = presentationCode,
                )
                return emptySet()
            } else {
                safe += normalized
                if (boundedSourceLocations.size < EntityCatalogIssuePersistence.MAX_SOURCES_PER_GROUP) {
                    boundedSourceLocations += source.location
                }
                return normalized
            }
        }

        fun analyzeAutoEntities(card: JSONObject, view: View, source: Source): Set<String> {
            val filter = card.optJSONObject("filter")
            val rawIncludes = filter?.let { rules(it.opt("include")) }.orEmpty()
            val directIncludes = rawIncludes.filter { it.has("type") }
            val includes = rawIncludes.filterNot { it.has("type") }
            val generated = sortedSetOf<String>().apply {
                addAll(source.injectedEntityIds)
                addAll(explicitEntityIds(card.opt("entities")))
                directIncludes.flatMapTo(this, ::explicitEntityIds)
                includes.mapNotNull { it.optJSONObject("options") }
                    .flatMapTo(this, ::explicitEntityIds)
            }
            val excludes = filter?.let { rules(it.opt("exclude")) }.orEmpty().map(::parseRule)
            val template = filter?.opt("template")
            val hasTemplate = when (template) {
                null, JSONObject.NULL -> false
                is String -> template.isNotBlank()
                else -> true
            }
            if (hasTemplate) {
                findings += Finding(
                    IssueType.UNBOUNDED_SELECTOR, view, source, "filter-template",
                    "Unbounded template entity selector", null, SELECTOR_ENTITY_LIMIT,
                    reason = TEMPLATE_SELECTOR_REASON,
                    recommendation = TEMPLATE_SELECTOR_RECOMMENDATION,
                    presentationCode = PresentationCode.TEMPLATE_SELECTOR,
                )
            }
            fun reportUnsafeGeneratedRows(
                rows: Collection<JSONObject>,
                location: String,
                summary: String,
                presentationCode: PresentationCode,
            ) {
                rows.filter { row ->
                    row.optString("type").startsWith("custom:") || containsDynamicValue(row)
                }.forEach { row ->
                    findings += Finding(
                        IssueType.UNBOUNDED_SELECTOR, view, source,
                        "$location:" + EntityLearningProtocol.hash(EntityLearningProtocol.canonical(row)),
                        summary,
                        null, SELECTOR_ENTITY_LIMIT,
                        presentationCode = presentationCode,
                    )
                }
            }
            reportUnsafeGeneratedRows(
                objectValues(card.opt("entities")), "seed-row",
                "Auto-entities seed row can generate entity dependencies at runtime",
                PresentationCode.AUTO_ENTITIES_SEED_ROW_DYNAMIC,
            )
            reportUnsafeGeneratedRows(
                directIncludes, "typed-row",
                "Auto-entities typed row can generate entity dependencies at runtime",
                PresentationCode.AUTO_ENTITIES_TYPED_ROW_DYNAMIC,
            )
            reportUnsafeGeneratedRows(
                includes.mapNotNull { it.optJSONObject("options") }.filterNot { it.optBoolean("eval_js") },
                "typed-options",
                "Auto-entities options can generate entity dependencies at runtime",
                PresentationCode.AUTO_ENTITIES_OPTIONS_DYNAMIC,
            )
            rawIncludes.filterNot { it.has("type") }.filter { rule ->
                rule.optJSONObject("options")?.optBoolean("eval_js") == true
            }.forEach { rule ->
                findings += Finding(
                    IssueType.UNBOUNDED_SELECTOR, view, source,
                    "options-eval-js:" + EntityLearningProtocol.hash(EntityLearningProtocol.canonical(rule)),
                    "Auto-entities options execute JavaScript that can replace entity dependencies",
                    null, SELECTOR_ENTITY_LIMIT,
                    presentationCode = PresentationCode.AUTO_ENTITIES_OPTIONS_JAVASCRIPT,
                )
            }
            // An exclude containing a runtime/dynamic refinement cannot safely remove its entire
            // structural match. Unsupported excludes are therefore ignored, not approximated.
            val supportedExcludes = excludes.filter {
                it.usable && !it.dynamic && (registryMetadataComplete || !it.requiresRegistry)
            }
            val excluded = supportedExcludes.flatMapTo(hashSetOf()) { rule ->
                match(rule, if (rule.requiresRegistry) knownIds else catalogIds, metadata)
            }

            if (includes.isEmpty()) {
                if (!hasTemplate && directIncludes.isEmpty()) {
                    findings += Finding(
                        IssueType.UNBOUNDED_SELECTOR, view, source, "missing-include", "Unbounded entity selector",
                        null, SELECTOR_ENTITY_LIMIT,
                        presentationCode = PresentationCode.SELECTOR_MISSING_INCLUDE,
                    )
                }
                return generated
            }
            for (raw in includes) {
                val rule = parseRule(raw)
                val effectiveSignature = rule.signature + "|exclude=" +
                    supportedExcludes.map(Rule::signature).sorted().joinToString(",")
                if (!rule.usable) {
                    findings += Finding(
                        IssueType.UNBOUNDED_SELECTOR, view, source, effectiveSignature,
                        "Unbounded or dynamic entity selector", null, SELECTOR_ENTITY_LIMIT,
                        presentationCode = PresentationCode.SELECTOR_UNBOUNDED_OR_DYNAMIC,
                    )
                    continue
                }
                if (rule.requiresRegistry && !registryMetadataComplete) {
                    findings += Finding(
                        IssueType.UNBOUNDED_SELECTOR, view, source, effectiveSignature,
                        "Entity selector requires incomplete registry metadata", null, SELECTOR_ENTITY_LIMIT,
                        presentationCode = PresentationCode.SELECTOR_REGISTRY_INCOMPLETE,
                    )
                    continue
                }
                val universe = if (rule.requiresRegistry) knownIds else catalogIds
                val candidates = match(rule, universe, metadata).filterNotTo(sortedSetOf(), excluded::contains)
                generated += addCandidates(
                    view, source, effectiveSignature, summary(rule), candidates,
                    PresentationCode.SELECTOR_BROAD,
                )
            }
            return generated
        }

        fun analyzeArea(card: JSONObject, view: View, source: Source) {
            val rawArea = card.opt("area")
            val area = (rawArea as? String)?.lowercase()?.takeIf { IDENTIFIER.matches(it) && !containsTemplate(it) }
            if (area == null || !registryMetadataComplete) {
                findings += Finding(
                    IssueType.UNBOUNDED_SELECTOR, view, source,
                    if (area == null) "area-card-missing-area" else "area-card-incomplete-registry",
                    if (area == null) "Area card has no static area ID" else "Area card registry metadata is incomplete",
                    null, SELECTOR_ENTITY_LIMIT,
                    presentationCode = if (area == null) {
                        PresentationCode.AREA_MISSING_ID
                    } else {
                        PresentationCode.AREA_REGISTRY_INCOMPLETE
                    },
                )
                return
            }
            val candidates = metadata.asSequence().filter { (_, entry) -> area in entry.areas }.map { it.key }.toMutableSet()
            candidates += areaRegistryEntities[area].orEmpty()
            addCandidates(
                view, source, "area-card:$area", "Area card $area", candidates,
                PresentationCode.AREA_BROAD,
            )
        }

        fun analyzeMap(card: JSONObject, view: View, source: Source) {
            val showAll = card.opt("show_all")
            val dynamicShowAll = showAll == true
            val invalidShowAll = card.has("show_all") && showAll !in setOf(true, false, JSONObject.NULL)
            val geoSources = card.opt("geo_location_sources")
            val hasGeoSources = when {
                geoSources == null || geoSources == JSONObject.NULL -> false
                geoSources is JSONArray -> geoSources.length() > 0
                geoSources is String -> geoSources.isNotBlank()
                else -> true
            }
            if (dynamicShowAll || invalidShowAll || hasGeoSources) {
                val mode = when {
                    dynamicShowAll || invalidShowAll -> "show_all"
                    else -> "geo_location_sources"
                }
                findings += Finding(
                    IssueType.UNBOUNDED_SELECTOR, view, source, "map-card:$mode",
                    "Map card dynamically enumerates $mode entities", null, SELECTOR_ENTITY_LIMIT,
                    presentationCode = PresentationCode.MAP_DYNAMIC_ENUMERATION,
                )
            }

            val people = (explicitEntityIds(card.opt("entities")) + source.injectedEntityIds)
                .filter { it.startsWith("person.") }
            if (people.isNotEmpty()) {
                if (!registryMetadataComplete) {
                    findings += Finding(
                        IssueType.UNBOUNDED_SELECTOR, view, source, "map-card-incomplete-registry",
                        "Map person locations require complete zone registry metadata",
                        null, SELECTOR_ENTITY_LIMIT,
                        presentationCode = PresentationCode.MAP_ZONE_REGISTRY_INCOMPLETE,
                    )
                    return
                }
                addCandidates(
                    view, source, "map-person-zones",
                    "Map person locations may depend on zones",
                    knownIds.filter { it.startsWith("zone.") },
                    PresentationCode.MAP_ZONES_BROAD,
                )
            }
        }

        fun addLimitedSupport(
            view: View,
            source: Source,
            signature: String,
            summary: String,
            reason: String,
            recommendation: String,
            presentationCode: PresentationCode,
        ) {
            findings += Finding(
                IssueType.LIMITED_SUPPORT,
                view,
                source,
                signature,
                summary,
                candidateCount = null,
                limit = null,
                reason = reason,
                recommendation = recommendation,
                presentationCode = presentationCode,
            )
        }

        fun addDynamicCardCompatibilityGap(
            view: View,
            source: Source,
            signature: String,
            summary: String,
            reason: String,
            recommendation: String,
            presentationCode: PresentationCode,
        ) {
            findings += Finding(
                IssueType.COMPATIBILITY_GAP,
                view,
                source,
                // Keep the fingerprint revision-specific so a changed opaque template is reported as a
                // new compatibility note without pausing an otherwise useful entity subscription.
                "$signature:dashboard=$dashboardRevision",
                summary,
                candidateCount = null,
                limit = null,
                reason = reason,
                recommendation = recommendation,
                presentationCode = presentationCode,
            )
        }

        fun addRuntimeCoverageNotice(
            source: Source,
            signature: String,
            summary: String,
            reason: String,
            presentationCode: PresentationCode,
        ) {
            findings += Finding(
                IssueType.RUNTIME_COVERAGE,
                View("Dashboard", "dashboard"),
                source,
                "$signature:dashboard=$dashboardRevision",
                summary,
                candidateCount = null,
                limit = null,
                reason = reason,
                recommendation = "",
                presentationCode = presentationCode,
            )
        }

        fun analyzeLimitedSupportCard(card: JSONObject, view: View, source: Source) {
            when (card.optString("type")) {
                "custom:streamline-card" -> {
                    addRuntimeCoverageNotice(
                        source, "streamline-runtime-coverage",
                        "Streamline entity discovery depends on dashboard coverage",
                        "Static entity IDs are detected immediately. Other direct hass.states dependencies are learned automatically as Streamline templates run, so the detected entity list may remain incomplete until every relevant view, conditional state, popup, and interaction has been exercised.",
                        PresentationCode.STREAMLINE_RUNTIME_COVERAGE,
                    )
                }
                "custom:decluttering-card" -> {
                    addDynamicCardCompatibilityGap(
                        view, source, "decluttering-template-expansion", "Decluttering Card has limited entity discovery support",
                        "Static entity IDs and targets are scanned, but the referenced template, defaults, and variables can introduce dependencies absent from the invocation.",
                        "Keep dependencies explicit and pin every entity produced by the template.",
                        PresentationCode.DECLUTTERING_TEMPLATE_EXPANSION,
                    )
                }
                "custom:button-card" -> {
                    addLimitedSupport(
                        view, source, "limited-support:button-card", "Button Card has limited entity discovery support",
                        "Static entity IDs and targets are scanned, but config-template inheritance, JavaScript, group expansion, and broad update triggers are not evaluated.",
                        "Keep dependencies explicit and pin any entities introduced through Button Card templates or JavaScript.",
                        PresentationCode.BUTTON_CARD_LIMITED_SUPPORT,
                    )
                    val risks = buildList {
                        if (card.has("template") && !card.isNull("template")) add("config template inheritance")
                        if (containsJavaScriptTemplate(card)) add("JavaScript templates")
                        if (card.optBoolean("group_expand", false)) add("group expansion")
                        if (containsAllTrigger(card.opt("triggers_update"))) add("triggers_update: all")
                    }
                    if (risks.isNotEmpty()) {
                        addDynamicCardCompatibilityGap(
                            view,
                            source,
                            "button-card-dynamic:" + risks.sorted().joinToString(","),
                            "Button Card uses " + risks.joinToString(", "),
                            "These Button Card features can introduce dependencies that are not present as static entity IDs in the dashboard configuration.",
                            "Replace them with explicit entity IDs and update triggers, or pin every generated dependency.",
                            PresentationCode.BUTTON_CARD_DYNAMIC_FEATURES,
                        )
                    }
                }
                "custom:bubble-card" -> {
                    addRuntimeCoverageNotice(
                        source, "bubble-card-runtime-coverage",
                        "Bubble Card entity discovery depends on dashboard coverage",
                        "Static entity IDs are detected immediately. Other direct hass.states dependencies from Bubble Card popups, styles, sub-buttons, and modules are learned automatically as they run, so the detected entity list may remain incomplete until every relevant popup, conditional state, module path, and interaction has been exercised.",
                        PresentationCode.BUBBLE_CARD_RUNTIME_COVERAGE,
                    )
                }
            }
        }

        fun analyzeKioskMode(value: Any?) {
            if (!containsKioskJavaScriptTemplate(value)) return
            val view = View("Dashboard", "dashboard")
            val source = Source(
                "dashboard.kiosk_mode",
                "HACS Kiosk Mode configuration",
                cardTitleHacsKiosk = true,
            )
            addLimitedSupport(
                view, source, "limited-support:kiosk-mode", "HACS Kiosk Mode JavaScript has limited entity-discovery support",
                "Literal entity IDs in client-side templates are scanned, but computed references and entity, domain, or registry enumeration cannot be evaluated safely.",
                "For automatic entity filtering, use literal entity IDs in HACS Kiosk Mode JavaScript and avoid enumeration; server-rendered Jinja settings do not require main-stream dependencies.",
                PresentationCode.KIOSK_MODE_LIMITED_SUPPORT,
            )
            addDynamicCardCompatibilityGap(
                view,
                source,
                "kiosk-mode-dynamic:arbitrary-client-javascript",
                "HACS Kiosk Mode uses client-side JavaScript with unknown entity dependencies",
                "These client-side expressions can depend on entities that are absent from the statically discovered subscription set.",
                "For automatic entity filtering, remove client-side JavaScript or pin every dependency; this does not affect HACS presentation or Home Assistant's native kiosk command.",
                PresentationCode.KIOSK_MODE_DYNAMIC_JAVASCRIPT,
            )
        }

        fun analyzeSource(card: JSONObject, view: View, source: Source): Set<String> {
            if (card.optJSONObject("strategy") != null) {
                val strategySource = source.copy(location = "${source.location}.strategy")
                findings += Finding(
                    IssueType.UNBOUNDED_SELECTOR, view, strategySource, "dashboard-strategy",
                    "Dashboard strategy generates entity dependencies at runtime", null, SELECTOR_ENTITY_LIMIT,
                    presentationCode = PresentationCode.DASHBOARD_STRATEGY,
                )
            }
            return when (card.optString("type")) {
                "custom:auto-entities" -> analyzeAutoEntities(card, view, source)
                "entity-filter" -> explicitEntityIds(card.opt("entities")) + source.injectedEntityIds
                "area" -> { analyzeArea(card, view, source); emptySet() }
                "map" -> { analyzeMap(card, view, source); emptySet() }
                "custom:streamline-card", "custom:decluttering-card", "custom:button-card", "custom:bubble-card" -> {
                    analyzeLimitedSupportCard(card, view, source)
                    emptySet()
                }
                else -> emptySet()
            }
        }

        analyzeKioskMode(root.opt("kiosk_mode"))

        if (root.optJSONObject("strategy") != null) {
            analyzeSource(root, View("Dashboard", "dashboard"), Source("dashboard", safeText(root.optString("title"))))
        }

        val views = root.optJSONArray("views")
        if (views != null) for (viewIndex in 0 until views.length()) {
            val viewObject = views.optJSONObject(viewIndex) ?: continue
            val authoredTitle = safeText(viewObject.optString("title"))
            val view = View(
                title = authoredTitle ?: "View ${viewIndex + 1}",
                path = safeIdentifier(viewObject.optString("path")) ?: "views[$viewIndex]",
                titleIndex = if (authoredTitle == null) {
                    (viewIndex + 1).takeIf { it <= EntityCatalogIssuePersistence.MAX_VIEW_TITLE_INDEX }
                } else {
                    null
                },
            )
            walkCards(viewObject, "dashboard.views[$viewIndex]", view) { card, source ->
                analyzeSource(card, view, source)
            }
        }

        if (safe.size > SELECTOR_TOTAL_LIMIT) {
            findings += Finding(
                type = IssueType.SELECTOR_BUDGET,
                view = View("Dashboard", "dashboard"),
                source = Source("dashboard", null),
                signature = "selector-total-budget",
                summary = "Bounded selectors collectively match ${safe.size} entities",
                candidateCount = safe.size,
                limit = SELECTOR_TOTAL_LIMIT,
                presentationCode = PresentationCode.SELECTOR_TOTAL_BUDGET,
            )
        }

        return Result(safe, group(findings, boundedSourceLocations), dashboardRevision)
    }

    /** Determine the minimum registry projection required by the stored dashboard before opening
     * registry streams. Dashboard configuration is fetched first so ordinary HA writes establish
     * the referenced objects before this later, still-eventual registry snapshot. */
    internal fun registryRequirements(configJson: String): RegistryRequirements {
        var entities = false
        var areas = false
        var devices = false
        var floors = false
        var labels = false

        fun requireArea() {
            entities = true
            areas = true
            devices = true
        }

        fun inspectRule(rule: JSONObject) {
            if (rule.has("type")) return
            if (rule.has("area") || rule.has("area_id")) requireArea()
            if (rule.has("floor") || rule.has("floor_id")) {
                requireArea()
                floors = true
            }
            if (rule.has("label") || rule.has("label_id")) {
                entities = true
                devices = true
                labels = true
            }
        }

        fun walk(value: Any?) {
            when (value) {
                is JSONObject -> {
                    when (value.optString("type")) {
                        "area" -> requireArea()
                        // A person injected through a wrapper can need any registered zone later.
                        "map" -> entities = true
                        "custom:auto-entities" -> value.optJSONObject("filter")?.let { filter ->
                            rules(filter.opt("include")).forEach(::inspectRule)
                            rules(filter.opt("exclude")).forEach(::inspectRule)
                        }
                    }
                    value.keys().asSequence()
                        .filterNot { key -> isStructuredNameConfiguration(key, value.opt(key)) }
                        .forEach { walk(value.opt(it)) }
                }
                is JSONArray -> for (index in 0 until value.length()) walk(value.opt(index))
            }
        }

        walk(JSONObject(configJson))
        return RegistryRequirements(entities, areas, devices, floors, labels)
    }

    private fun group(findings: FindingAccumulator, boundedSourceLocations: Collection<String>): List<Issue> =
        findings.retainedGroups().map { grouped ->
            val first = grouped.first
            val locations = if (first.type == IssueType.SELECTOR_BUDGET) {
                boundedSourceLocations.sorted()
            } else grouped.sourceLocations.sorted()
            Issue(
                type = first.type,
                viewTitle = first.view.title,
                viewPath = first.view.path,
                cardTitle = grouped.cardTitles.singleOrNull(),
                sourceLocations = locations,
                ruleSummary = first.summary,
                candidateCount = first.candidateCount,
                limit = first.limit,
                reason = first.reason ?: when (first.type) {
                    IssueType.UNBOUNDED_SELECTOR ->
                        "The selector has no structural bound that can be converted into a safe subscription set."
                    IssueType.BROAD_SELECTOR ->
                        "The structural candidate set exceeds the per-selector subscription safety limit. Dynamic filters, sorting, and display limits cannot safely reduce it."
                    IssueType.SELECTOR_BUDGET ->
                        "The combined bounded selectors exceed the dashboard subscription safety limit."
                    IssueType.DIAGNOSTIC_LIMIT ->
                        "The bounded diagnostics surface cannot represent every blocking check."
                    IssueType.LIMITED_SUPPORT ->
                        "Static entity IDs are scanned, but this card can contain dependencies that are not yet expanded."
                    IssueType.COMPATIBILITY_GAP ->
                        "Static entity IDs are scanned, but this integration can introduce dependencies outside the dashboard configuration."
                    IssueType.RUNTIME_COVERAGE ->
                        "Runtime entity discovery may remain incomplete until every relevant dashboard path has been exercised."
                },
                recommendation = first.recommendation ?: when (first.type) {
                    IssueType.UNBOUNDED_SELECTOR ->
                        "Add an explicit entity ID, entity pattern, domain, area, floor, or label constraint."
                    IssueType.BROAD_SELECTOR ->
                        "Narrow the selector with an area, floor, label, entity pattern, or explicit entity list."
                    IssueType.SELECTOR_BUDGET ->
                        "Narrow the dashboard selectors so their combined entity set stays within the limit."
                    IssueType.DIAGNOSTIC_LIMIT ->
                        "Simplify or split the dashboard before enabling automatic entity filtering."
                    IssueType.LIMITED_SUPPORT ->
                        "Keep dependencies explicit and pin any entities introduced by unsupported card features."
                    IssueType.COMPATIBILITY_GAP ->
                        "Keep dependencies explicit and pin any entities introduced by external templates or scripts."
                    IssueType.RUNTIME_COVERAGE -> ""
                },
                fingerprint = EntityLearningProtocol.hash(
                    "${first.view.path}|${first.type.wireName}|${first.signature}",
                ),
                presentationCode = first.presentationCode,
                viewTitleIndex = first.view.titleIndex,
                cardTitleHacsKiosk = first.source.cardTitleHacsKiosk,
            )
        }.sortedWith(compareBy<Issue>({ it.viewPath }, { it.type.wireName }, { it.fingerprint }))

    private fun walkCards(
        value: Any?,
        path: String,
        view: View,
        injectedEntityIds: Set<String> = emptySet(),
        consume: (JSONObject, Source) -> Set<String>,
    ) {
        when (value) {
            is JSONObject -> {
                val type = value.optString("type")
                var childEntityIds = emptySet<String>()
                if (value.optString("type") in OWNED_CARD_TYPES || value.optJSONObject("strategy") != null) {
                    childEntityIds = consume(
                        value,
                        Source(
                            path,
                            safeText(value.optString("title")) ?: safeText(value.optString("name")),
                            injectedEntityIds,
                        ),
                    )
                }
                value.keys().asSequence().toList().sorted()
                    .filterNot { key -> isStructuredNameConfiguration(key, value.opt(key)) }
                    .forEach { key ->
                    val injectsEntities = key == "card" && when (type) {
                        "custom:auto-entities" -> value.optString("card_param").ifBlank { "entities" } == "entities"
                        "entity-filter" -> true
                        else -> false
                    }
                    walkCards(
                        value.opt(key),
                        "$path.$key",
                        view,
                        if (injectsEntities) childEntityIds else emptySet(),
                        consume,
                    )
                }
            }
            is JSONArray -> for (index in 0 until value.length()) {
                walkCards(value.opt(index), "$path[$index]", view, injectedEntityIds, consume)
            }
        }
    }

    private fun rules(value: Any?): List<JSONObject> = when (value) {
        is JSONObject -> listOf(value)
        is JSONArray -> (0 until value.length()).mapNotNull { index ->
            when (val item = value.opt(index)) {
                is JSONObject -> item
                is String -> JSONObject().put("entity_id", item)
                else -> null
            }
        }
        is String -> listOf(JSONObject().put("entity_id", value))
        else -> emptyList()
    }

    private fun objectValues(value: Any?): List<JSONObject> = when (value) {
        is JSONObject -> listOf(value)
        is JSONArray -> (0 until value.length()).mapNotNull(value::optJSONObject)
        else -> emptyList()
    }

    private fun explicitEntityIds(value: Any?): Set<String> = when (value) {
        is String -> setOf(value.lowercase()).filterTo(sortedSetOf(), ENTITY_ID::matches)
        is JSONObject -> explicitEntityIds(value.opt("entity")) + explicitEntityIds(value.opt("entity_id"))
        is JSONArray -> (0 until value.length()).flatMapTo(sortedSetOf()) { explicitEntityIds(value.opt(it)) }
        else -> emptySet()
    }

    private fun containsDynamicValue(value: Any?): Boolean = when (value) {
        is String -> containsTemplate(value)
        is JSONObject -> value.keys().asSequence().any { containsDynamicValue(value.opt(it)) }
        is JSONArray -> (0 until value.length()).any { containsDynamicValue(value.opt(it)) }
        else -> false
    }

    private fun containsJavaScriptTemplate(value: Any?, nested: Boolean = false): Boolean = when (value) {
        is JSONObject -> if (nested && value.optString("type") == "custom:button-card") {
            false
        } else {
            value.keys().asSequence().any { containsJavaScriptTemplate(value.opt(it), nested = true) }
        }
        is JSONArray -> (0 until value.length()).any { containsJavaScriptTemplate(value.opt(it), nested) }
        is String -> "[[[" in value
        else -> false
    }

    private fun containsKioskJavaScriptTemplate(value: Any?): Boolean = when (value) {
        is JSONObject -> value.keys().asSequence().any { containsKioskJavaScriptTemplate(value.opt(it)) }
        is JSONArray -> (0 until value.length()).any { containsKioskJavaScriptTemplate(value.opt(it)) }
        is String -> "[[[" in value
        else -> false
    }

    private fun containsAllTrigger(value: Any?): Boolean = when (value) {
        is String -> value == "all"
        is JSONArray -> (0 until value.length()).any { containsAllTrigger(value.opt(it)) }
        else -> false
    }

    /** Linear wildcard matching for the JavaScript-card subset we admit. Upstream turns `*` into
     * `.*` and, when a wildcard is present, leaves dots regex-active. */
    private fun wildcardMatcher(pattern: String, dotMatchesAny: Boolean): (String) -> Boolean = matcher@{ value ->
        var patternIndex = 0
        var valueIndex = 0
        var starIndex = -1
        var retryValueIndex = -1
        while (valueIndex < value.length) {
            when {
                patternIndex < pattern.length && pattern[patternIndex] != '*' &&
                    (pattern[patternIndex] == value[valueIndex] || dotMatchesAny && pattern[patternIndex] == '.') -> {
                    patternIndex++
                    valueIndex++
                }
                patternIndex < pattern.length && pattern[patternIndex] == '*' -> {
                    starIndex = patternIndex++
                    retryValueIndex = valueIndex
                }
                starIndex >= 0 -> {
                    patternIndex = starIndex + 1
                    valueIndex = ++retryValueIndex
                }
                else -> return@matcher false
            }
        }
        while (patternIndex < pattern.length && pattern[patternIndex] == '*') patternIndex++
        patternIndex == pattern.length
    }

    private fun parseRule(raw: JSONObject): Rule {
        val domains = structuralValues(raw.opt("domain"), DOMAIN)
        val rawEntityPatterns = values(raw.opt("entity_id"))
        val invalidEntityPattern = rawEntityPatterns.any { !isEntityPattern(it.lowercase()) }
        // Entity patterns in one include rule are alternatives. If any pattern is unsafe, dropping only
        // that alternative would under-count the selector; discard the complete pattern constraint and
        // retain only independent domain/area/floor/label constraints as a conservative superset.
        val patterns = if (invalidEntityPattern) emptySet() else structuralValues(raw.opt("entity_id"), ENTITY_PATTERN)
        val areas = registryValues(raw.opt("area")) + structuralValues(raw.opt("area_id"), IDENTIFIER)
        val floors = registryValues(raw.opt("floor")) + structuralValues(raw.opt("floor_id"), IDENTIFIER)
        val labels = registryValues(raw.opt("label")) + structuralValues(raw.opt("label_id"), IDENTIFIER)
        val rawName = (raw.opt("name") as? String)?.takeIf(String::isNotBlank)
        val invalidNamePattern = raw.has("name") && (rawName == null || !isFriendlyNamePattern(rawName))
        val namePattern = rawName?.takeIf(::isFriendlyNamePattern)
        val structuralKeys = setOf("domain", "entity_id", "area", "area_id", "floor", "floor_id", "label", "label_id", "name")
        val presentationKeys = setOf("options", "sort")
        val keys = raw.keys().asSequence().toSet()
        val suppliedStructural = keys.intersect(structuralKeys)
        val invalidStructural = invalidEntityPattern || invalidNamePattern || suppliedStructural.any { key ->
            values(raw.opt(key)).any { value ->
                containsTemplate(value) || when (key) {
                    "domain" -> !DOMAIN.matches(value.lowercase())
                    "entity_id" -> !isEntityPattern(value.lowercase())
                    "name" -> !isFriendlyNamePattern(value)
                    "area", "floor", "label" -> !isRegistryValue(value)
                    else -> !IDENTIFIER.matches(value.lowercase())
                }
            }
        }
        val dynamic = (keys - structuralKeys - presentationKeys).isNotEmpty() || invalidStructural
        val usable = domains.isNotEmpty() || patterns.isNotEmpty() || areas.isNotEmpty() || floors.isNotEmpty() ||
            labels.isNotEmpty() || namePattern != null
        val signature = listOf(
            "d=" + domains.sorted().joinToString(","),
            "e=" + patterns.sorted().joinToString(","),
            "a=" + areas.sorted().joinToString(","),
            "f=" + floors.sorted().joinToString(","),
            "l=" + labels.sorted().joinToString(","),
            "n=" + (namePattern?.let(EntityLearningProtocol::hash) ?: ""),
            "dynamic=$dynamic",
            // Preserve exact-rule grouping without placing raw template or state expressions in an
            // issue record. The short hash is deterministic but not reversible in the UI payload.
            "rule=" + EntityLearningProtocol.hash(EntityLearningProtocol.canonical(raw)),
        ).joinToString("|")
        return Rule(
            domains, patterns, areas, floors, labels, namePattern, usable, dynamic,
            suppliedStructural.any { it in REGISTRY_SELECTOR_KEYS }, signature,
        )
    }

    private fun match(rule: Rule, catalog: List<String>, metadata: Map<String, Metadata>): Set<String> {
        val patterns = rule.entityPatterns.mapNotNull(::compilePattern)
        val nameMatcher = rule.namePattern?.let(::compileFriendlyNamePattern)
        return catalog.filterTo(sortedSetOf()) { id ->
            val meta = metadata.getValue(id)
            (rule.domains.isEmpty() || id.substringBefore('.') in rule.domains) &&
                (patterns.isEmpty() || patterns.any { it(id) }) &&
                (rule.areas.isEmpty() || meta.areas.any(rule.areas::contains)) &&
                (rule.floors.isEmpty() || meta.floors.any(rule.floors::contains)) &&
                (rule.labels.isEmpty() || meta.labels.any(rule.labels::contains)) &&
                (nameMatcher == null || meta.friendlyName.isNotEmpty() && nameMatcher(meta.friendlyName))
        }
    }

    private fun compileFriendlyNamePattern(pattern: String): ((String) -> Boolean)? = when {
        pattern.length >= 2 && pattern.startsWith('/') && pattern.endsWith('/') -> {
            val source = pattern.substring(1, pattern.length - 1)
            if (!isSafeEntitySelectorRegex(source)) null else runCatching { Regex(source) }.getOrNull()
                ?.let { regex -> { value: String -> regex.containsMatchIn(value) } }
        }
        '*' in pattern -> wildcardMatcher(pattern, dotMatchesAny = true)
        else -> { value: String -> value == pattern }
    }

    private fun compilePattern(pattern: String): ((String) -> Boolean)? = when {
        pattern.length >= 2 && pattern.startsWith('/') && pattern.endsWith('/') -> {
            val source = pattern.substring(1, pattern.length - 1)
            if (!isSafeEntitySelectorRegex(source)) null else runCatching { Regex(source) }.getOrNull()
                ?.let { regex -> { value: String -> regex.containsMatchIn(value) } }
        }
        '*' in pattern -> wildcardMatcher(pattern, dotMatchesAny = true)
        else -> { value -> value == pattern }
    }

    /** Auto-entities slash patterns originate in dashboard configuration and run against the complete
     *  catalogue. Keep the supported subset deliberately regular: no groups, alternation, counted
     *  repetition or backreferences means quantifiers cannot nest. Permit only one unbounded quantifier:
     *  adjacent or separated `.*`/`+` fragments can still produce catastrophic backtracking even without
     *  those richer constructs. Rejected patterns are
     *  treated as an unconstrained selector by [match], producing a blocking broad-selector diagnostic. */
    internal fun isSafeEntitySelectorRegex(source: String): Boolean {
        if (source.isEmpty() || source.length > MAX_PATTERN_LENGTH) return false
        var escaped = false
        var inClass = false
        var previousQuantifier = false
        var unboundedQuantifiers = 0
        source.forEach { ch ->
            if (escaped) {
                if (ch.isDigit()) return false
                escaped = false
                previousQuantifier = false
                return@forEach
            }
            if (ch == '\\') {
                escaped = true
                previousQuantifier = false
                return@forEach
            }
            if (inClass) {
                if (ch == ']') inClass = false
                return@forEach
            }
            when (ch) {
                '[' -> { inClass = true; previousQuantifier = false }
                '(', ')', '{', '}', '|' -> return false
                '*', '+' -> {
                    if (previousQuantifier || ++unboundedQuantifiers > 1) return false
                    previousQuantifier = true
                }
                '?' -> {
                    if (previousQuantifier) return false
                    previousQuantifier = true
                }
                else -> previousQuantifier = false
            }
        }
        return !escaped && !inClass
    }

    private fun parseMetadata(json: String?, friendlyName: String): Metadata {
        val objectValue = json?.let { runCatching { JSONObject(it) }.getOrNull() }
        fun first(vararg keys: String): String = keys.firstNotNullOfOrNull { key ->
            objectValue?.optString(key)?.lowercase()?.takeIf(String::isNotBlank)
        }.orEmpty()
        fun strings(vararg keys: String): Set<String> = keys.flatMapTo(hashSetOf()) { key ->
            when (val value = objectValue?.opt(key)) {
                is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).lowercase().takeIf(String::isNotBlank) }
                is String -> listOf(value.lowercase())
                else -> emptyList()
            }
        }
        return Metadata(
            setOf(first("ai", "area_id", "area"), first("an", "area_name")).filterTo(hashSetOf(), String::isNotBlank),
            setOf(first("fi", "floor_id", "floor"), first("fn", "floor_name")).filterTo(hashSetOf(), String::isNotBlank),
            strings("lb", "labels", "label_ids", "ln", "label_names"),
            friendlyName,
        )
    }

    private fun summary(rule: Rule): String {
        val parts = mutableListOf<String>()
        if (rule.domains.isNotEmpty()) parts += "domain ${safeList(rule.domains)}"
        if (rule.entityPatterns.isNotEmpty()) parts += "entity pattern ${safeList(rule.entityPatterns)}"
        if (rule.areas.isNotEmpty()) parts += "area ${safeList(rule.areas)}"
        if (rule.floors.isNotEmpty()) parts += "floor ${safeList(rule.floors)}"
        if (rule.labels.isNotEmpty()) parts += "label ${safeList(rule.labels)}"
        if (rule.namePattern != null) parts += "friendly name ${safeNamePattern(rule.namePattern)}"
        return parts.joinToString("; ").take(MAX_SUMMARY_LENGTH)
    }

    private fun safeNamePattern(pattern: String): String = pattern.replace(Regex("[\\p{Cntrl}]"), " ").take(80)

    private fun safeList(values: Set<String>): String = values.sorted().take(3).joinToString(", ") { it.take(40) } +
        if (values.size > 3) " (+${values.size - 3})" else ""

    private fun structuralValues(value: Any?, allowed: Regex): Set<String> = values(value).map(String::lowercase)
        .filterNot(::containsTemplate).filter { candidate ->
            if (allowed === ENTITY_PATTERN) isEntityPattern(candidate) else allowed.matches(candidate)
        }.toSortedSet()

    private fun registryValues(value: Any?): Set<String> = values(value).filter(::isRegistryValue)
        .map { it.lowercase() }.toSortedSet()

    private fun isRegistryValue(value: String): Boolean = value.isNotBlank() && value.length <= MAX_PATTERN_LENGTH &&
        !containsTemplate(value) && '*' !in value && !(value.startsWith('/') && value.endsWith('/')) &&
        value.none(Char::isISOControl)

    private fun values(value: Any?): List<String> = when (value) {
        is String -> listOf(value)
        is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).takeIf(String::isNotBlank) }
        else -> emptyList()
    }

    private fun isEntityPattern(value: String): Boolean = when {
        value.length > MAX_PATTERN_LENGTH || containsTemplate(value) -> false
        ENTITY_ID.matches(value) -> true
        GLOB_ENTITY_ID.matches(value) -> true
        value.length >= 2 && value.startsWith('/') && value.endsWith('/') ->
            value.substring(1, value.length - 1).let { source ->
                isSafeEntitySelectorRegex(source) && runCatching { Regex(source) }.isSuccess
            }
        else -> false
    }

    private fun isFriendlyNamePattern(value: String): Boolean = when {
        value.isEmpty() || value.length > MAX_PATTERN_LENGTH || containsTemplate(value) ||
            value.any(Char::isISOControl) -> false
        value.length >= 2 && value.startsWith('/') && value.endsWith('/') ->
            value.substring(1, value.length - 1).let { source ->
                isSafeEntitySelectorRegex(source) && runCatching { Regex(source) }.isSuccess
            }
        '*' in value -> value.none { it in UNSAFE_GLOB_META }
        else -> true
    }

    private fun safeText(value: String): String? = value.trim().takeIf {
        it.isNotBlank() && !containsTemplate(it)
    }?.replace(Regex("[\\p{Cntrl}]"), " ")?.take(80)

    private fun safeIdentifier(value: String): String? = value.lowercase().takeIf(IDENTIFIER::matches)
    private fun containsTemplate(value: String): Boolean = TEMPLATE_MARKERS.any(value::contains)

    private val ENTITY_ID = Regex("^[a-z0-9_]+\\.[a-z0-9_]+$")
    private val GLOB_ENTITY_ID = Regex("^[a-z0-9_*]+\\.[a-z0-9_*]+$")
    private val DOMAIN = Regex("^[a-z0-9_]+$")
    private val IDENTIFIER = Regex("^[a-z0-9_-]+$")
    private val ENTITY_PATTERN = Regex(".*") // Identity marker; validated by isEntityPattern.
    private val TEMPLATE_MARKERS = listOf("{{", "{%", "[[[", "\${", "hass.states", "states[")
    private val OWNED_CARD_TYPES = setOf(
        "custom:auto-entities",
        "entity-filter",
        "area",
        "map",
        "custom:streamline-card",
        "custom:decluttering-card",
        "custom:button-card",
        "custom:bubble-card",
    )
    /** Official structured names label the parent card's selected entity. Restrict this to the
     * documented descriptor shapes so a custom-card field merely named `name` remains traversable. */
    private fun isStructuredNameConfiguration(key: String, value: Any?): Boolean {
        if (key != "name") return false
        fun isDescriptor(candidate: Any?): Boolean {
            val descriptor = candidate as? JSONObject ?: return false
            val keys = descriptor.keys().asSequence().toSet()
            return when (descriptor.optString("type")) {
                "entity", "device", "area", "floor" -> keys == setOf("type")
                "text" -> keys == setOf("type", "text") && descriptor.opt("text") is String
                else -> false
            }
        }
        return when (value) {
            is JSONObject -> isDescriptor(value)
            is JSONArray -> value.length() > 0 && (0 until value.length()).all { isDescriptor(value.opt(it)) }
            else -> false
        }
    }
    private val REGISTRY_SELECTOR_KEYS = setOf("area", "area_id", "floor", "floor_id", "label", "label_id")
    private val UNSAFE_GLOB_META = setOf('\\', '+', '?', '^', '$', '{', '}', '(', ')', '|', '[', ']', '/')
    /**
     * A template filter is refused without being read, so this text can neither quote the template nor
     * name anything inside it. It replaces the generic unbounded-selector wording because the recurring
     * report is the opposite of a bug: an entity the template only *tests* never appears, and the
     * generic copy gives the reader no way to tell that apart from a discovery failure.
     *
     * The two halves are deliberately not symmetric, and getting that wrong is the failure this text
     * exists to prevent. Home Assistant renders the template itself and delivers the result over a
     * `render_template` subscription, which [EntityFilterProtocol] never touches: it mutates only
     * `subscribe_entities`. So an entity the template merely reads needs nothing, and telling anyone to
     * pin it would inflate the very list the filter exists to shrink. Only what the template *returns*
     * becomes a card dependency read back through `hass.states`, and only that can need a pin.
     *
     * Deliberately terse. Every issue carries its own copy into a payload bounded at
     * [EntityCatalogIssuePersistence.MAX_PAYLOAD_BYTES], where overflow drops whole issues rather than
     * truncating a string. The route to catalogue search and manual pinning is presentation, so it
     * lives in the Entities page instead of in every record.
     */
    private const val TEMPLATE_SELECTOR_REASON =
        "ha-paneld does not evaluate templates, so it cannot tell which entities this filter returns. " +
            "Entities it only reads are Home Assistant's own, delivered outside this filter, so their " +
            "absence here is correct and they need nothing."

    private const val TEMPLATE_SELECTOR_RECOMMENDATION =
        "Bound the filter structurally, or pin only the entities the template returns."

    private const val MAX_PATTERN_LENGTH = 160
    private const val MAX_SUMMARY_LENGTH = 240
}
