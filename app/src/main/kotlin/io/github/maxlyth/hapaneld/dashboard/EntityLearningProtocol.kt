package io.github.maxlyth.hapaneld.dashboard

import io.github.maxlyth.hapaneld.util.DashboardPath
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Pure dashboard-analysis and document-start helpers for automatic entity learning. */
object EntityLearningProtocol {
    /**
     * A dashboard the authenticated Home Assistant user can choose as this panel's home.
     *
     * @param icon the dashboard's own `mdi:*` icon name, or blank when none is set — the picker renders
     *   Home Assistant's fallback (`mdi:view-dashboard`) client-side, mirroring HA's own picker.
     * @param group `"panel"` for Home Assistant's built-in panel dashboards (Home, Energy, …) or
     *   `"dashboard"` for user-created Lovelace dashboards — HA's picker presents them as separate groups.
     */
    data class HomeDashboardChoice(
        val path: String,
        val title: String,
        val icon: String = "",
        val group: String = "dashboard",
    )

    /**
     * What the signed-in account's default dashboard resolves to, read scan-independently in the same
     * WebSocket session as the dashboard list. `explicit` is true only when the user profile or the
     * system carries a real `default_panel` — when false, "follow the account default" falls to Home
     * Assistant's own fallback panel, and setup should recommend nominating a specific dashboard instead.
     */
    data class HomeDashboardDefault(val explicit: Boolean = false, val path: String = "")

    /** CACHED marks a provisional local replay of the persisted last-resolved path while the live
     *  resolution re-runs; [resolveHomeDashboard] itself never produces it. */
    enum class HomeDashboardSource { EXPLICIT, USER_DEFAULT, SYSTEM_DEFAULT, FIRST_LEGAL, CACHED, NONE }

    /** One authenticated, list-validated renderer decision. A null path means HA reported no legal dashboards. */
    data class HomeDashboardResolution(
        val path: String? = null,
        val source: HomeDashboardSource = HomeDashboardSource.NONE,
    )

    data class BrowserObserverCosts(
        val frames: Long,
        val entities: Long,
        val frameChars: Long,
        val parseMicros: Long,
        val stringifyMicros: Long,
        val dropped: Long,
        val coalesced: Long,
    )

    data class MetricEnvelope(
        val metrics: Map<String, Pair<Long, Long>>,
        val observer: BrowserObserverCosts?,
    )

    private val ENTITY_ID = Regex("(?<![a-z0-9_])[a-z0-9_]+\\.[a-z0-9_]+(?![a-z0-9_])")
    private val DIRECT_ENTITY_ID = Regex("^[a-z0-9_]+\\.[a-z0-9_]+$")
    private val TARGET_REGISTRY_ID = Regex("^[A-Za-z0-9_-]+$")
    private val TARGET_KEYS = setOf("entity_id", "device_id", "area_id", "floor_id", "label_id")
    private val EXPANDABLE_TARGET_KEYS = TARGET_KEYS - "entity_id"
    private val TEMPLATE_MARKERS = listOf("{{", "{%", "[[[", "hass.states", "states[")
    private val PSEUDO_DOMAINS = setOf("variables", "config", "hass", "state", "states", "entity")

    internal const val MAX_NATIVE_BRIDGE_PAYLOAD_CHARS = 1_000_000
    internal const val MAX_OBSERVER_WS_FRAME_CHARS = 786_432
    internal const val MAX_OBSERVER_ENTITY_ID_CHARS = 255
    internal const val MAX_OBSERVER_ACCESS_IDS = 1_024
    internal const val MAX_OBSERVER_MISSING_IDS = 1_024
    internal const val MAX_OBSERVER_METRIC_IDS = 2_048

    data class ScanResult(
        val entityIds: Set<String>,
        /** Canonical HA targets suitable for `extract_from_target`. */
        val targets: List<String>,
        val unresolved: List<String>,
        val dynamicExpressions: List<DynamicExpression> = emptyList(),
    )

    data class DynamicExpression(
        val sourceLocation: String,
        val literal: String,
        val truncated: Boolean,
        val fingerprint: String,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("source_location", sourceLocation)
            .put("literal", literal)
            .put("truncated", truncated)
            .put("fingerprint", fingerprint)
    }

    /** Scan every nested dashboard value. False-positive entity-like strings are removed against the catalog later. */
    fun scanDashboard(configJson: String): ScanResult {
        val root = JSONObject(configJson)
        val ids = linkedSetOf<String>()
        val targets = linkedSetOf<String>()
        val unresolved = linkedSetOf<String>()
        val dynamicExpressions = linkedMapOf<String, DynamicExpression>()

        fun recordDynamicExpression(value: String, path: String) {
            if (TEMPLATE_MARKERS.none(value::contains)) return
            val key = "$path\u0000$value"
            dynamicExpressions.putIfAbsent(key, DynamicExpression(
                sourceLocation = path,
                literal = value.take(MAX_DYNAMIC_EXPRESSION_LENGTH),
                truncated = value.length > MAX_DYNAMIC_EXPRESSION_LENGTH,
                fingerprint = hash(key),
            ))
        }

        fun inspectDynamicExpressions(value: Any?, path: String) {
            when (value) {
                is JSONObject -> value.keys().asSequence().toList().sorted().forEach { key ->
                    inspectDynamicExpressions(value.opt(key), "$path.$key")
                }
                is JSONArray -> for (index in 0 until value.length()) {
                    inspectDynamicExpressions(value.opt(index), "$path[$index]")
                }
                is String -> recordDynamicExpression(value, path)
            }
        }

        fun scalarIds(value: String) {
            ENTITY_ID.findAll(value.lowercase()).map { it.value }
                .filterNot { it.substringBefore('.') in PSEUDO_DOMAINS }
                .forEach(ids::add)
        }

        fun targetValues(key: String, value: Any?): Pair<Any?, Boolean> {
            fun valid(candidate: String): Boolean = when (key) {
                "entity_id" -> DIRECT_ENTITY_ID.matches(candidate.lowercase())
                else -> TARGET_REGISTRY_ID.matches(candidate)
            }
            return when (value) {
                is String -> if (valid(value)) value to false else null to true
                is JSONArray -> {
                    val accepted = JSONArray()
                    var rejected = false
                    for (i in 0 until value.length()) {
                        val candidate = value.optString(i)
                        if (candidate.isNotBlank() && valid(candidate)) accepted.put(candidate) else rejected = true
                    }
                    accepted.takeIf { it.length() > 0 } to rejected
                }
                else -> null to true
            }
        }

        fun walk(value: Any?, path: String) {
            when (value) {
                is JSONObject -> {
                    val type = value.optString("type")
                    if (type == "custom:auto-entities") {
                        // Selector filters are not walked for ordinary literal IDs, but their dynamic
                        // expressions are still valuable configuration evidence. Selector admission
                        // and diagnostics belong exclusively to DashboardConfigurationLint.
                        value.opt("filter")?.let { filterValue ->
                            inspectDynamicExpressions(filterValue, "$path.filter")
                            // Include criteria remain selector-owned so an entity pattern is not
                            // mistaken for a concrete dependency. Per-entity options are ordinary
                            // child configuration, though, and can contain action targets or helper
                            // entities which must remain available when the generated row is used.
                            when (val include = (filterValue as? JSONObject)?.opt("include")) {
                                is JSONObject -> when {
                                    include.has("type") -> walk(include, "$path.filter.include")
                                    include.has("options") -> walk(include.opt("options"), "$path.filter.include.options")
                                }
                                is JSONArray -> for (index in 0 until include.length()) {
                                    include.optJSONObject(index)?.let { rule ->
                                        when {
                                            rule.has("type") -> walk(rule, "$path.filter.include[$index]")
                                            rule.has("options") ->
                                                walk(rule.opt("options"), "$path.filter.include[$index].options")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val before = ids.size + targets.size
                    val target = JSONObject()
                    for (key in TARGET_KEYS) if (value.has(key)) {
                        val (accepted, rejected) = targetValues(key, value.opt(key))
                        if (accepted != null) target.put(key, accepted)
                        if (rejected) unresolved += "$path.$key:dynamic-target"
                    }
                    value.optJSONObject("target")?.let { nested ->
                        for (key in TARGET_KEYS) if (nested.has(key)) {
                            val (accepted, rejected) = targetValues(key, nested.opt(key))
                            if (accepted != null) target.put(key, accepted)
                            if (rejected) unresolved += "$path.target.$key:dynamic-target"
                        }
                    }
                    // Literal entity IDs are collected by scalarIds while walking the same object. HA's
                    // extract_from_target is only needed for registry selectors; sending every card's
                    // entity ID turns a normal dashboard into hundreds of serial WebSocket round trips.
                    val expandableTarget = JSONObject()
                    for (key in EXPANDABLE_TARGET_KEYS) if (target.has(key)) expandableTarget.put(key, target.opt(key))
                    if (expandableTarget.length() > 0) targets += canonical(expandableTarget)
                    // The selector linter owns auto-entities filter semantics. Walking those rules as
                    // ordinary strings would incorrectly promote literal IDs from an exclude rule.
                    val keys = value.keys().asSequence().toList().sorted().filterNot {
                        type == "custom:auto-entities" && it == "filter"
                    }
                    for (key in keys) walk(value.opt(key), "$path.$key")
                    if (type.startsWith("custom:") && type != "custom:auto-entities" &&
                        before == ids.size + targets.size
                    ) {
                        unresolved += "$path:$type"
                    }
                }
                is JSONArray -> for (i in 0 until value.length()) walk(value.opt(i), "$path[$i]")
                is String -> {
                    val before = ids.size
                    scalarIds(value)
                    recordDynamicExpression(value, path)
                    if (TEMPLATE_MARKERS.any(value::contains) && ids.size == before) {
                        unresolved += "$path:dynamic-template"
                    }
                }
            }
        }
        walk(root, "dashboard")
        return ScanResult(
            entityIds = ids,
            targets = targets.toList(),
            unresolved = unresolved.toList(),
            dynamicExpressions = dynamicExpressions.values.sortedWith(compareBy({ it.sourceLocation }, { it.fingerprint })),
        )
    }

    internal const val MAX_DYNAMIC_EXPRESSION_LENGTH = 2048

    /**
     * A blank/root renderer route means "use this HA user's default dashboard". Explicit routes,
     * including `/lovelace`, stay authoritative and must not be replaced by frontend user data.
     */
    fun usesFrontendDefaultPanel(homeDashboard: String): Boolean = homeDashboard.trim()
        .substringBefore('?')
        .substringBefore('#')
        .trim('/')
        .isBlank()

    /**
     * Return the Lovelace WebSocket dashboard URL path for an already list-validated renderer route.
     * Empty selects ordinary Lovelace; non-Lovelace dashboards name their URL-path segment.
     */
    fun dashboardUrlPath(homeDashboard: String): String {
        val first = homeDashboard.trim().substringBefore('?').substringBefore('#')
            .trim('/').substringBefore('/')
        return first.takeUnless { it.isBlank() || it == "lovelace" }.orEmpty()
    }

    /**
     * Preserve Home Assistant's dashboard ordering while reducing its WebSocket response to safe local paths.
     * `lovelace/dashboards/list` does not itself hide administrator-only dashboards, so that policy is applied
     * here using the authenticated user's role.
     */
    fun homeDashboardChoices(dashboards: JSONArray, isAdmin: Boolean): List<HomeDashboardChoice> {
        val choices = mutableListOf<HomeDashboardChoice>()
        val seenPaths = mutableSetOf<String>()
        for (index in 0 until dashboards.length()) {
            val dashboard = dashboards.optJSONObject(index) ?: continue
            if (dashboard.optBoolean("require_admin") && !isAdmin) continue
            val urlPath = dashboard.optString("url_path").trim().trim('/')
            if (urlPath.isNotBlank() && !DashboardPath.isRootSegment(urlPath)) continue
            val path = if (urlPath.isBlank() || urlPath == "lovelace") "/lovelace" else "/$urlPath"
            if (!seenPaths.add(path)) continue
            val title = dashboard.optString("title").trim().ifBlank {
                if (path == "/lovelace") "Home" else path.removePrefix("/")
            }
            choices += HomeDashboardChoice(path, title, sanitizedIconName(dashboard.optString("icon")))
        }
        return choices
    }

    /**
     * Home Assistant's built-in panel dashboards (Home, Light, Security, …) in the frontend's own fixed
     * presentation order, reduced from the `get_panels` result. HA's dashboard picker lists these as their
     * own group ahead of user-created dashboards; they are panels, not Lovelace dashboards, so
     * `lovelace/dashboards/list` never returns them. Icons and titles are data-driven from the panel
     * registration — only the client-side fallback icon is hardcoded, mirroring HA.
     */
    fun panelDashboardChoices(panels: JSONObject?, isAdmin: Boolean): List<HomeDashboardChoice> {
        if (panels == null) return emptyList()
        return PANEL_DASHBOARD_ORDER.mapNotNull { key ->
            val panel = panels.optJSONObject(key) ?: return@mapNotNull null
            if (panel.optBoolean("require_admin") && !isAdmin) return@mapNotNull null
            val title = panel.optString("title").trim().ifBlank {
                key.replaceFirstChar { it.uppercase() }
            }
            HomeDashboardChoice("/$key", title, sanitizedIconName(panel.optString("icon")), group = "panel")
        }
    }

    /**
     * The configuration a built-in panel dashboard stands for, or null for a stored Lovelace dashboard.
     * Built-in panels (`get_panels` registers them with no config) are frontend strategies, so
     * `lovelace/config` rejects their key ("Unknown config specified"); a root strategy document is the
     * honest equivalent. Stored Lovelace url_paths must contain a hyphen, so these keys cannot collide.
     */
    fun panelDashboardConfig(urlPath: String): JSONObject? =
        if (urlPath in PANEL_DASHBOARD_ORDER) JSONObject().put("strategy", JSONObject().put("type", urlPath)) else null

    /**
     * Reduce the account's server-side default-panel reads (`frontend/get_user_data` then
     * `frontend/get_system_data`, both key `core`, field `default_panel`) to what setup needs: whether a
     * real default EXISTS, and the legal path it names. A malformed value is treated as absent rather than
     * passed through — the picker must only ever preselect a value it could itself have offered.
     */
    fun homeDashboardDefault(
        userDefault: String?,
        systemDefault: String?,
        legalChoices: List<HomeDashboardChoice>,
    ): HomeDashboardDefault {
        val resolution = resolveHomeDashboard(
            homeDashboard = "",
            userDefault = userDefault,
            systemDefault = systemDefault,
            legalChoices = legalChoices,
            allowFirstLegalFallback = false,
        )
        return resolution.path?.let { HomeDashboardDefault(explicit = true, path = it) }
            ?: HomeDashboardDefault()
    }

    /**
     * Resolve the renderer target strictly against the dashboards visible to the authenticated user.
     * Explicit routes retain their view/query/fragment, but membership is checked by dashboard root
     * (`/lovelace/0` belongs to `/lovelace`; `/alpha/view` belongs to `/alpha`). Defaults are untrusted
     * profile data and are independently checked so a stale user value cannot suppress a legal system
     * value. The final fallback is the first legal choice in Home Assistant's supplied order.
     */
    fun resolveHomeDashboard(
        homeDashboard: String,
        userDefault: String?,
        systemDefault: String?,
        legalChoices: List<HomeDashboardChoice>,
        allowFirstLegalFallback: Boolean = true,
    ): HomeDashboardResolution {
        val legal = legalChoices.mapNotNullTo(linkedSetOf()) { dashboardRoot(it.path) }
        fun admitted(raw: String?, preserveRoute: Boolean): String? {
            val candidate = normalizedDashboardCandidate(raw, preserveRoute) ?: return null
            return candidate.takeIf { dashboardRoot(it) in legal }
        }

        if (!usesFrontendDefaultPanel(homeDashboard)) {
            admitted(homeDashboard, preserveRoute = true)?.let {
                return HomeDashboardResolution(it, HomeDashboardSource.EXPLICIT)
            }
        }
        admitted(userDefault, preserveRoute = false)?.let {
            return HomeDashboardResolution(it, HomeDashboardSource.USER_DEFAULT)
        }
        admitted(systemDefault, preserveRoute = false)?.let {
            return HomeDashboardResolution(it, HomeDashboardSource.SYSTEM_DEFAULT)
        }
        if (allowFirstLegalFallback) legalChoices.firstOrNull { dashboardRoot(it.path) in legal }?.let {
            return HomeDashboardResolution(it.path, HomeDashboardSource.FIRST_LEGAL)
        }
        return HomeDashboardResolution()
    }

    /**
     * The canonical form of a dashboard route, admitted by exactly the rules the renderer resolution
     * uses: a legal root segment, and suffix segments free of dot segments, separators, control
     * characters and percent-encoded equivalents. Exposed so a route READ BACK from storage can be
     * admitted by the same semantics that produced it, rather than by a weaker local copy.
     */
    fun canonicalDashboardRoute(raw: String?): String? = normalizedDashboardCandidate(raw, preserveRoute = true)

    /** Accept only well-formed `mdi:` icon names; anything else renders as the client-side fallback. */
    private fun sanitizedIconName(icon: String?): String {
        val value = icon?.trim().orEmpty()
        return if (MDI_ICON_NAME.matches(value)) value else ""
    }

    /** HA frontend `PANEL_DASHBOARDS` order (ha-config-lovelace-dashboards): fixed, not sidebar order. */
    private val PANEL_DASHBOARD_ORDER = listOf("home", "light", "security", "climate", "energy", "maintenance")
    private val MDI_ICON_NAME = Regex("^mdi:[a-z0-9-]+$")

    // Route well-formedness lives in util/DashboardPath so the renderer and the `home_dashboard`
    // setting's validator cannot drift apart. Membership of the authenticated list stays here.
    private fun normalizedDashboardCandidate(raw: String?, preserveRoute: Boolean): String? =
        DashboardPath.canonical(raw, preserveRoute)

    private fun dashboardRoot(value: String): String? = DashboardPath.root(value)

    fun canonical(value: Any): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { key ->
            JSONObject.quote(key) + ":" + canonical(value.opt(key) ?: JSONObject.NULL)
        }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it) ?: JSONObject.NULL) }
        JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        is Boolean, is Number -> value.toString()
        else -> JSONObject.quote(value.toString())
    }

    fun hash(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    /**
     * Observe the root HA state map without altering values. Direct reads and missing direct reads are
     * batched separately; enumeration is deliberately ignored because it is not dependency evidence.
     */
    fun documentStartScript(
        haUrl: String,
        documentOrigins: Collection<String> = setOf(EntityFilterProtocol.origin(haUrl)),
        featureCostsEnabled: Boolean = true,
    ): String {
        val (targetWsOrigins, targetWsPath) = InjectionScript.wsTargets(haUrl, documentOrigins)
        val observerSetup = if (featureCostsEnabled) """
          const now=()=>window.performance&&typeof window.performance.now==='function'?window.performance.now():0;
          let observerFrames=0,observerEntities=0,observerFrameChars=0,observerParseMs=0,observerStringifyMs=0,
            observerDropped=0,observerCoalesced=0;
          function measuredParse(value){const started=now();try{return JSON.parse(value)}finally{observerParseMs+=Math.max(0,now()-started)}}
          function measuredStringify(value){const started=now(),encoded=JSON.stringify(value);observerStringifyMs+=Math.max(0,now()-started);return typeof encoded==='string'?encoded:''}
        """.trimIndent() else """
          function measuredParse(value){return JSON.parse(value)}
          function measuredStringify(value){const encoded=JSON.stringify(value);return typeof encoded==='string'?encoded:''}
        """.trimIndent()
        val accessCoalesced = if (featureCostsEnabled) "observerCoalesced=sat(observerCoalesced,1);" else ""
        val accessDropped = if (featureCostsEnabled) "observerDropped=sat(observerDropped,1);" else ""
        val observerMetricGuard = if (featureCostsEnabled) "&&!observerFrames&&!observerDropped&&!observerCoalesced" else ""
        val observerEnvelope = if (featureCostsEnabled) """
            out.__ha_paneld_observer={frames:observerFrames,entities:observerEntities,frame_chars:observerFrameChars,
              parse_us:Math.round(observerParseMs*1000),stringify_us:Math.round(observerStringifyMs*1000),
              dropped:observerDropped,coalesced:observerCoalesced};
        """.trimIndent() else ""
        val observerReset = if (featureCostsEnabled) """
            observerFrames=observerEntities=observerFrameChars=observerParseMs=observerStringifyMs=
              observerDropped=observerCoalesced=0;
        """.trimIndent() else ""
        val recordFrame = if (featureCostsEnabled) {
            "observerFrames=sat(observerFrames,1);observerFrameChars=sat(observerFrameChars,ev.data.length);"
        } else ""
        val recordEntity = if (featureCostsEnabled) "observerEntities=sat(observerEntities,1);" else ""
        val oversizedFrameDrop = if (featureCostsEnabled) "observerDropped=sat(observerDropped,1);" else ""
        val oversizedPayloadDrop = if (featureCostsEnabled) {
            "else observerDropped=sat(observerDropped,Math.max(1,discarded));"
        } else ""
        return """
        (()=>{
          ${InjectionScript.TOP_FRAME_GUARD}
          if(window.__haPaneldEntityLearning)return;
          window.__haPaneldEntityLearning=true;
          const wrapped=new WeakSet(),id=/^[a-z0-9_]+\.[a-z0-9_]+$/;
          const seen=new Map(),missing=new Set(),metrics=new Map(); let enumerating=false;
          const maxBridgeChars=$MAX_NATIVE_BRIDGE_PAYLOAD_CHARS,maxFrameChars=$MAX_OBSERVER_WS_FRAME_CHARS,
            maxIdChars=$MAX_OBSERVER_ENTITY_ID_CHARS,maxAccessIds=$MAX_OBSERVER_ACCESS_IDS,
            maxMissingIds=$MAX_OBSERVER_MISSING_IDS,maxMetricIds=$MAX_OBSERVER_METRIC_IDS,maxCount=$MAX_OBSERVER_COUNT;
          const sat=(left,right)=>Math.min(maxCount,left+Math.max(0,right));
          $observerSetup
          function validId(value){return typeof value==='string'&&value.length<=maxIdChars&&id.test(value)}
          function recordAccess(entityId,present){
            if(present){const count=seen.get(entityId);if(count!==undefined){seen.set(entityId,sat(count,1));$accessCoalesced return}
              if(seen.size>=maxAccessIds){$accessDropped return}seen.set(entityId,1);return}
            if(missing.has(entityId)){$accessCoalesced return}
            if(missing.size>=maxMissingIds){$accessDropped return}missing.add(entityId)
          }
          function recordMetric(entityId,value){
            if(!validId(entityId))return;
            const old=metrics.get(entityId);if(old){old[0]=sat(old[0],1);old[1]=sat(old[1],measuredStringify(value).length);$accessCoalesced return}
            if(metrics.size>=maxMetricIds){$accessDropped return}
            metrics.set(entityId,[1,measuredStringify(value).length]);
          }
          function flush(){
            if(!seen.size&&!missing.size)return;
            const accessed={};seen.forEach((count,entityId)=>accessed[entityId]=count);
            const payload=JSON.stringify({accessed:accessed,missing:Array.from(missing)}),discarded=seen.size+missing.size;
            seen.clear();missing.clear();
            if(payload.length<maxBridgeChars){try{window.haPaneldV2&&window.haPaneldV2.postMessage(JSON.stringify({type:'entityLearningAccesses',payload:JSON.parse(payload)}))}catch(e){}}
            ${if (featureCostsEnabled) "else observerDropped=sat(observerDropped,discarded);" else "// Cost tracking disabled."}
          }
          setInterval(flush,2000);
          setInterval(()=>{
            if(!metrics.size$observerMetricGuard)return;
            const out={},discarded=metrics.size;metrics.forEach((v,k)=>out[k]=v);metrics.clear();
            ${observerEnvelope.ifEmpty { "// Cost envelope disabled." }}
            const payload=JSON.stringify(out);
            ${observerReset.ifEmpty { "// Cost counters disabled." }}
            if(payload.length<maxBridgeChars){try{window.haPaneldV2&&window.haPaneldV2.postMessage(JSON.stringify({type:'entityLearningMetrics',payload:JSON.parse(payload)}))}catch(e){}}
            ${oversizedPayloadDrop.ifEmpty { "// Cost drop tracking disabled." }}
          },5000);
          const Parent=window.WebSocket,targetWsOrigins=$targetWsOrigins,targetWsPath=$targetWsPath;
          function LearningWebSocket(url,protocols){
            const socket=protocols===undefined?new Parent(url):new Parent(url,protocols);
            try{const u=new URL(String(url),location.href);if(targetWsOrigins.includes(u.origin)&&u.pathname===targetWsPath){
              let hydrated=false,entitySubscriptionId=null;const send=socket.send;
              Object.defineProperty(socket,'send',{configurable:true,writable:true,value:function(data){
                if(typeof data==='string'){if(data.length>maxFrameChars){$oversizedFrameDrop}else try{const message=measuredParse(data);if(message&&message.type==='subscribe_entities')entitySubscriptionId=message.id}catch(e){}}
                return send.call(this,data)
              }});
              socket.addEventListener('message',ev=>{if(typeof ev.data!=='string')return;$recordFrame
                if(ev.data.length>maxFrameChars){$oversizedFrameDrop return}try{
                const decoded=measuredParse(ev.data);
                const messages=Array.isArray(decoded)?decoded:[decoded];messages.forEach(m=>{
                if(m.id!==entitySubscriptionId)return;const event=m&&m.type==='event'&&m.event;if(!event)return;
                if(event.a&&!hydrated){hydrated=true;for(const k in event.a)if(Object.prototype.hasOwnProperty.call(event.a,k)){$recordEntity recordMetric(k,event.a[k])}return}const changed=event.c||{};
                for(const k in changed)if(Object.prototype.hasOwnProperty.call(changed,k)){$recordEntity recordMetric(k,changed[k])}})
              }catch(e){}})
            }}catch(e){}return socket
          }
          Object.setPrototypeOf(LearningWebSocket,Parent);LearningWebSocket.prototype=Parent.prototype;
          for(const k of ['CONNECTING','OPEN','CLOSING','CLOSED'])Object.defineProperty(LearningWebSocket,k,{value:Parent[k]});
          window.WebSocket=LearningWebSocket;
          setInterval(()=>{
            try{
              const root=document.querySelector('home-assistant'),h=root&&root.hass,s=h&&h.states;
              if(!root||!h||!s||wrapped.has(s))return;
              const proxy=new Proxy(s,{
                ownKeys(t){enumerating=true;queueMicrotask(()=>enumerating=false);return Reflect.ownKeys(t)},
                get(t,p,r){
                  if(validId(p)&&!enumerating){
                    recordAccess(p,Reflect.has(t,p));
                  }
                  return Reflect.get(t,p,r)
                },
                has(t,p){
                  if(validId(p)&&!enumerating){
                    recordAccess(p,Reflect.has(t,p));
                  }
                  return Reflect.has(t,p)
                }
              });
              wrapped.add(proxy); root.hass=Object.assign({},h,{states:proxy});
            }catch(e){}
          },1000);
        })();
        """.trimIndent()
    }

    fun parseAccessBatch(text: String): Pair<Map<String, Long>, Set<String>> {
        val obj = JSONObject(text)
        val accessed = when (val value = obj.opt("accessed")) {
            is JSONObject -> value.keys().asSequence().mapNotNull { id ->
                id.takeIf(DIRECT_ENTITY_ID::matches)?.let { it to value.optLong(id, 0).coerceIn(1, 1_000_000) }
            }.toMap()
            // Accept the legacy prototype format across an in-place WebView rebuild.
            is JSONArray -> (0 until value.length()).mapNotNull { value.optString(it).takeIf(DIRECT_ENTITY_ID::matches) }
                .associateWith { 1L }
            else -> emptyMap()
        }
        val missingArray = obj.optJSONArray("missing") ?: JSONArray()
        val missing = (0 until missingArray.length()).mapNotNull {
            missingArray.optString(it).takeIf(DIRECT_ENTITY_ID::matches)
        }.toSet()
        return accessed to missing
    }

    fun parseMetricEnvelope(text: String): MetricEnvelope {
        val obj = JSONObject(text)
        val metrics = obj.keys().asSequence().mapNotNull { id ->
            if (!DIRECT_ENTITY_ID.matches(id)) return@mapNotNull null
            val pair = obj.optJSONArray(id) ?: return@mapNotNull null
            id to (pair.optLong(0).coerceAtLeast(0) to pair.optLong(1).coerceAtLeast(0))
        }.toMap()
        val observer = obj.optJSONObject(OBSERVER_COST_KEY)?.let { cost ->
            BrowserObserverCosts(
                frames = cost.optLong("frames").coerceIn(0L, MAX_OBSERVER_COUNT),
                entities = cost.optLong("entities").coerceIn(0L, MAX_OBSERVER_COUNT),
                frameChars = cost.optLong("frame_chars").coerceIn(0L, MAX_OBSERVER_CHARS),
                parseMicros = cost.optLong("parse_us").coerceIn(0L, MAX_OBSERVER_MICROS),
                stringifyMicros = cost.optLong("stringify_us").coerceIn(0L, MAX_OBSERVER_MICROS),
                dropped = cost.optLong("dropped").coerceIn(0L, MAX_OBSERVER_COUNT),
                coalesced = cost.optLong("coalesced").coerceIn(0L, MAX_OBSERVER_COUNT),
            )
        }
        return MetricEnvelope(metrics, observer)
    }

    private const val OBSERVER_COST_KEY = "__ha_paneld_observer"
    private const val MAX_OBSERVER_COUNT = 50_000_000L
    private const val MAX_OBSERVER_CHARS = 1_000_000_000L
    private const val MAX_OBSERVER_MICROS = 300_000_000L
}
