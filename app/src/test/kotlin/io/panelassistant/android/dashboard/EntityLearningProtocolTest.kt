package io.panelassistant.android.dashboard

import io.panelassistant.android.DashboardEntityDefaultResolverMigration
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EntityLearningProtocolTest {
    @Test fun manualActivationCannotBypassPendingDefaultResolverReplacement() {
        assertFalse(canActivateLearnedEntitySet(defaultResolverRebootstrapPending = true))
        assertTrue(canActivateLearnedEntitySet(defaultResolverRebootstrapPending = false))
    }

    @Test fun resolverUpgradeForcesStartupScanDespiteExistingCatalogTimestamp() {
        assertTrue(shouldSyncEntityLearningOnStartup(
            learningEnabled = true,
            lastSyncAt = 1234L,
            resolverMigration = DashboardEntityDefaultResolverMigration.REBOOTSTRAP,
        ))
        assertFalse(shouldSyncEntityLearningOnStartup(
            learningEnabled = true,
            lastSyncAt = 1234L,
            resolverMigration = DashboardEntityDefaultResolverMigration.NOT_NEEDED,
        ))
        assertTrue(shouldSyncEntityLearningOnStartup(
            learningEnabled = true,
            lastSyncAt = 1234L,
            resolverMigration = DashboardEntityDefaultResolverMigration.NOT_NEEDED,
            analyzerPolicyStale = true,
        ))
        assertFalse(shouldSyncEntityLearningOnStartup(
            learningEnabled = false,
            lastSyncAt = 1234L,
            resolverMigration = DashboardEntityDefaultResolverMigration.NOT_NEEDED,
            analyzerPolicyStale = true,
        ))
        assertTrue(shouldSyncEntityLearningOnStartup(
            learningEnabled = true,
            lastSyncAt = 1234L,
            resolverMigration = DashboardEntityDefaultResolverMigration.NOT_NEEDED,
            forceBootstrap = true,
        ))
        assertFalse(shouldSyncEntityLearningOnStartup(
            learningEnabled = true,
            lastSyncAt = 0L,
            resolverMigration = DashboardEntityDefaultResolverMigration.PERSIST_FAILED,
            analyzerPolicyStale = true,
        ))
    }

    @Test fun emptyInstallBootstrapsButExistingManualSetIsPreserved() {
        assertTrue(shouldBootstrapEntityLearning(learningEnabled = true, applied = false, configuredIds = emptyList()))
        assertFalse(shouldBootstrapEntityLearning(learningEnabled = true, applied = false, configuredIds = listOf("light.kitchen")))
        assertFalse(shouldBootstrapEntityLearning(learningEnabled = true, applied = true, configuredIds = emptyList()))
        assertFalse(shouldBootstrapEntityLearning(learningEnabled = false, applied = false, configuredIds = emptyList()))
    }

    @Test fun firstBootstrapDefaultsOnlyIgnorableCompatibilityFindingsToIgnored() {
        val ordinary = org.json.JSONObject()
            .put("blocking", true)
            .put("ignorable", true)
            .put("fingerprint", "0123456789abcdef")
        val hardFence = org.json.JSONObject()
            .put("blocking", true)
            .put("ignorable", false)
            .put("fingerprint", "fedcba9876543210")
        assertEquals(
            setOf("0123456789abcdef"),
            defaultIgnoredIssueFingerprints(initialActivationPending = true, bootstrap = true, issues = listOf(ordinary, hardFence)),
        )
        assertTrue(defaultIgnoredIssueFingerprints(false, true, listOf(ordinary)).isEmpty())
        assertTrue(defaultIgnoredIssueFingerprints(true, false, listOf(ordinary)).isEmpty())

        val firstRunEffective = org.json.JSONArray(EntityCatalogIssuePersistence.applyIgnores(
            org.json.JSONArray(listOf(ordinary, hardFence)),
            setOf("0123456789abcdef"),
        ))
        val effectiveByFingerprint = (0 until firstRunEffective.length())
            .map(firstRunEffective::getJSONObject)
            .associateBy { it.getString("fingerprint") }
        assertFalse(effectiveByFingerprint.getValue("0123456789abcdef").getBoolean("blocking"))
        assertTrue(effectiveByFingerprint.getValue("0123456789abcdef").getBoolean("ignored"))
        assertTrue(effectiveByFingerprint.getValue("fedcba9876543210").getBoolean("blocking"))
        assertEquals(
            AutomaticSyncDecision.BLOCKED,
            automaticSyncDecision(true, applied = false, configuredIds = emptyList(), blockingIssues = true),
        )

        val ordinaryOnly = org.json.JSONArray(EntityCatalogIssuePersistence.applyIgnores(
            org.json.JSONArray(listOf(ordinary)),
            setOf("0123456789abcdef"),
        ))
        assertFalse(ordinaryOnly.getJSONObject(0).getBoolean("blocking"))
        assertEquals(
            AutomaticSyncDecision.BOOTSTRAP,
            automaticSyncDecision(true, applied = false, configuredIds = emptyList(), blockingIssues = false),
        )
    }

    @Test fun defaultIgnoredSelectorBudgetDropsOnlyTheLinterProjection() {
        val fingerprint = "0123456789abcdef"
        val selectorBudget = DashboardConfigurationLint.Issue(
            type = DashboardConfigurationLint.IssueType.SELECTOR_BUDGET,
            viewTitle = "Overview",
            viewPath = "overview",
            cardTitle = null,
            sourceLocations = listOf("cards[0]"),
            ruleSummary = "selector budget",
            candidateCount = 200,
            limit = DashboardConfigurationLint.SELECTOR_TOTAL_LIMIT,
            reason = "too many candidates",
            recommendation = "narrow the selector",
            fingerprint = fingerprint,
            presentationCode = DashboardConfigurationLint.PresentationCode.SELECTOR_TOTAL_BUDGET,
        )
        val lint = DashboardConfigurationLint.Result(
            safeEntityIds = setOf("light.partial_selector_result"),
            issues = listOf(selectorBudget),
            dashboardRevision = "revision",
        )

        val lintIds = effectiveLintEntityIds(lint, setOf(fingerprint))
        assertTrue(lintIds.isEmpty())
        assertEquals(
            sortedSetOf("light.direct", "sensor.expanded"),
            deriveStaticEntityIds(
                scannedEntityIds = listOf("light.direct", "sensor.not_in_catalog"),
                catalogIds = setOf("light.direct"),
                expandedTargets = setOf("sensor.expanded"),
                lintEntityIds = lintIds,
            ),
        )
        assertEquals(lint.safeEntityIds, effectiveLintEntityIds(lint, emptySet()))
    }

    @Test fun blockingDashboardRejectsWholeAutomaticSetAndDisabledScanOnlyObserves() {
        assertEquals(
            AutomaticSyncDecision.BLOCKED,
            automaticSyncDecision(true, applied = true, configuredIds = listOf("light.known_good"), blockingIssues = true),
        )
        assertEquals(
            AutomaticSyncDecision.BLOCKED,
            automaticSyncDecision(true, applied = false, configuredIds = emptyList(), blockingIssues = true),
        )
        assertEquals(
            AutomaticSyncDecision.OBSERVE,
            automaticSyncDecision(false, applied = false, configuredIds = emptyList(), blockingIssues = false),
        )
        assertEquals(
            AutomaticSyncDecision.BOOTSTRAP,
            automaticSyncDecision(
                learningEnabled = true,
                applied = false,
                configuredIds = listOf("light.known_good"),
                blockingIssues = false,
                forceBootstrap = true,
            ),
        )
    }

    @Test fun subscriptionPreviewMakesApplyOutcomeExplicit() {
        assertEquals(
            EntitySubscriptionPreview(currentCount = 3, additions = 1, removals = 1, streamChange = true),
            previewEntitySubscription(
                filtered = true,
                currentIds = listOf("light.a", "light.b", "sensor.c"),
                catalogCount = 100,
                desiredIds = listOf("light.a", "light.b", "person.d"),
            ),
        )
        assertEquals(
            EntitySubscriptionPreview(currentCount = 3, additions = 0, removals = 0, streamChange = false),
            previewEntitySubscription(true, listOf("light.a", "light.b", "sensor.c"), 100, listOf("sensor.c", "light.b", "light.a")),
        )
        assertEquals(
            EntitySubscriptionPreview(currentCount = 100, additions = 0, removals = 97, streamChange = true),
            previewEntitySubscription(false, emptyList(), 100, listOf("light.a", "light.b", "sensor.c")),
        )
        assertEquals(
            EntitySubscriptionPreview(currentCount = 100, additions = 0, removals = 100, streamChange = true),
            previewEntitySubscription(false, emptyList(), 100, emptyList()),
        )
    }

    @Test fun observerAttributesInitialHydrationAndFlushesPromptly() {
        val script = EntityLearningProtocol.documentStartScript("https://example.test")
        assertTrue(script.contains("if(window.top&&window.top!==window)return"))
        assertTrue(script.contains("recordMetric(k,event.a[k])"))
        assertTrue(script.contains("__ha_paneld_observer"))
        assertTrue(script.contains("m.id!==entitySubscriptionId"))
        assertTrue(script.contains("Array.isArray(decoded)?decoded:[decoded]"))
        assertTrue(script.contains("},5000);"))
    }

    @Test fun observerAcceptsThePermittedUpgradedSocketOrigin() {
        val script = EntityLearningProtocol.documentStartScript(
            "http://ha.example",
            linkedSetOf("http://ha.example", "https://ha.example"),
        )

        assertTrue(script.contains("targetWsOrigins=[\"ws://ha.example\",\"wss://ha.example\"]"))
        assertTrue(script.contains("targetWsOrigins.includes(u.origin)"))
    }

    @Test fun browserObserverEnvelopeBoundsPressureCounters() {
        val envelope = EntityLearningProtocol.parseMetricEnvelope(
            """{"sensor.room":[3,120],"__ha_paneld_observer":{"frames":4,"entities":7,"frame_chars":8192,"parse_us":2100,"stringify_us":900,"dropped":11,"coalesced":13}}""",
        )

        assertEquals(mapOf("sensor.room" to (3L to 120L)), envelope.metrics)
        assertEquals(4L, envelope.observer?.frames)
        assertEquals(11L, envelope.observer?.dropped)
        assertEquals(13L, envelope.observer?.coalesced)
    }

    @Test fun dashboardScannerFindsNestedLiteralsActionsTargetsAndUnresolvedCustomLogic() {
        val scan = EntityLearningProtocol.scanDashboard(
            """{"views":[{"cards":[
              {"type":"tile","entity":"light.kitchen"},
              {"type":"custom:bubble-card","tap_action":{"target":{"area_id":"office"}},"name":"sensor.room_temperature"},
              {"type":"custom:mystery-card","value":"[[[ return variables.entity ]]]"}
            ]}]}""",
        )
        assertEquals(setOf("light.kitchen", "sensor.room_temperature"), scan.entityIds)
        assertTrue(scan.targets.single().contains("area_id"))
        assertTrue(scan.unresolved.any { it.contains("custom:mystery-card") })
        assertTrue(scan.unresolved.any { it.contains("dynamic-template") })
    }

    @Test fun mushroomCardsUseGenericEntityTargetAndConditionalChipScanning() {
        val scan = EntityLearningProtocol.scanDashboard(
            """{"views":[{"cards":[
              {"type":"custom:mushroom-light-card","entity":"light.kitchen",
                "tap_action":{"action":"perform-action","target":{
                  "entity_id":"script.evening_scene","device_id":"device_123",
                  "area_id":"living_room","floor_id":"ground_floor","label_id":"wall_panel"
                }}},
              {"type":"custom:mushroom-chips-card","chips":[
                {"type":"entity","entity":"sensor.room_temperature"},
                {"type":"conditional","conditions":[
                  {"condition":"state","entity":"binary_sensor.window_open","state":"on"}
                ],"chip":{"type":"light","entity":"light.desk"}}
              ]}
            ]}]}""",
        )

        assertEquals(
            setOf(
                "light.kitchen",
                "script.evening_scene",
                "sensor.room_temperature",
                "binary_sensor.window_open",
                "light.desk",
            ),
            scan.entityIds,
        )
        assertTrue(scan.targets.single().contains("device_id"))
        assertTrue(scan.targets.single().contains("area_id"))
        assertTrue(scan.targets.single().contains("floor_id"))
        assertTrue(scan.targets.single().contains("label_id"))
        assertTrue(scan.unresolved.none { it.contains("mushroom") })
    }

    @Test fun bubbleStaticSchemaUsesGenericScanningAcrossSubButtonsPopupsAndNumberedFields() {
        val scan = EntityLearningProtocol.scanDashboard(
            """{"views":[{"cards":[{
              "type":"custom:bubble-card","card_type":"pop-up","entity":"light.popup_header",
              "trigger_entity":"binary_sensor.popup_motion",
              "trigger":{"condition":"state","entity":"binary_sensor.popup_allowed","state":"on"},
              "open_action":{"action":"perform-action","target":{
                "entity_id":"script.popup_open","area_id":"living_room"
              }},
              "sub_button":{
                "main":[{"entity":"sensor.bubble_main_sub"}],
                "bottom":[
                  {"entity":"sensor.bubble_bottom_sub"},
                  {"group":[{"entity":"input_boolean.bubble_group_member"}]}
                ]
              },
              "cards":[
                {"type":"custom:bubble-card","card_type":"button","entity":"switch.popup_child"},
                {"type":"custom:bubble-card","card_type":"calendar","entities":[
                  {"entity":"calendar.household"},{"entity":"calendar.birthdays"}
                ]},
                {"type":"custom:bubble-card","card_type":"horizontal-buttons-stack",
                  "1_entity":"light.living_room","1_pir_sensor":"binary_sensor.living_room_motion"}
              ]
            }]}]}""",
        )

        assertEquals(
            setOf(
                "light.popup_header",
                "binary_sensor.popup_motion",
                "binary_sensor.popup_allowed",
                "script.popup_open",
                "sensor.bubble_main_sub",
                "sensor.bubble_bottom_sub",
                "input_boolean.bubble_group_member",
                "switch.popup_child",
                "calendar.household",
                "calendar.birthdays",
                "light.living_room",
                "binary_sensor.living_room_motion",
            ),
            scan.entityIds,
        )
        assertEquals(1, scan.targets.size)
        assertTrue(scan.targets.single().contains("area_id"))
        assertTrue(scan.unresolved.none { it.contains("custom:bubble-card") })
    }

    @Test fun mushroomTemplateCardUsesExplicitContextDependenciesAndLiteralJinjaScanning() {
        val template = "{{ states('sensor.jinja_literal') }}"
        val scan = EntityLearningProtocol.scanDashboard(
            """{"views":[{"cards":[{
              "type":"custom:mushroom-template-card",
              "entity":"light.reading_lamp",
              "area":"living_room",
              "entity_id":["sensor.template_update","binary_sensor.template_gate"],
              "secondary":${org.json.JSONObject.quote(template)}
            }]}]}""",
        )

        assertTrue(scan.entityIds.containsAll(setOf("light.reading_lamp", "sensor.template_update", "binary_sensor.template_gate")))
        assertTrue(scan.targets.isEmpty())
        assertEquals(template, scan.dynamicExpressions.single().literal)
        assertTrue(scan.unresolved.none { it.contains("mushroom-template-card") })
    }

    @Test fun cardModAndModCardKeepUnderlyingCardsAndLiteralTemplatesVisibleToGenericScan() {
        val scan = EntityLearningProtocol.scanDashboard(
            """{"views":[{"cards":[
              {"type":"custom:mushroom-entity-card","entity":"switch.wall_display",
                "card_mod":{"style":"ha-card { color: {{ states('sensor.card_mod_color') }}; }"}},
              {"type":"custom:mod-card",
                "card_mod":{"style":"ha-card { opacity: {{ states('sensor.mod_card_opacity') }}; }"},
                "card":{"type":"custom:mushroom-entity-card","entity":"climate.room",
                  "tap_action":{"action":"perform-action","target":{"entity_id":"script.climate_preset"}}}}
            ]}]}""",
        )

        assertTrue(scan.entityIds.containsAll(setOf("switch.wall_display", "climate.room", "script.climate_preset")))
        assertTrue(scan.targets.isEmpty())
        assertEquals(2, scan.dynamicExpressions.size)
        assertTrue(scan.unresolved.none { it.contains("custom:mod-card") || it.contains("mushroom-entity-card") })
    }

    @Test fun builtInEntityFilterCollectsFiniteCandidatesConditionsAndNestedCardReferences() {
        val scan = EntityLearningProtocol.scanDashboard(
            """{"views":[{"cards":[{
              "type":"entity-filter",
              "entities":[
                "light.kitchen",
                {"entity":"sensor.office_temperature","name":"Office temperature"}
              ],
              "conditions":[
                {"condition":"state","entity":"binary_sensor.office_window","state":"on"},
                {"condition":"numeric_state","entity":"sensor.office_humidity",
                  "above":"input_number.minimum_humidity","below":"sensor.maximum_humidity"}
              ],
              "card":{"type":"entities","entities":["input_boolean.filter_override"]}
            }]}]}""",
        )

        assertEquals(
            setOf(
                "light.kitchen",
                "sensor.office_temperature",
                "binary_sensor.office_window",
                "sensor.office_humidity",
                "input_number.minimum_humidity",
                "sensor.maximum_humidity",
                "input_boolean.filter_override",
            ),
            scan.entityIds,
        )
        assertTrue(scan.targets.isEmpty())
        assertTrue(scan.unresolved.isEmpty())
    }

    @Test fun dashboardScannerReturnsStructuredDynamicExpressionsWithVerbatimLiterals() {
        val literal = "[[[ return hass.states['sensor.room'].state; ]]]"
        val scan = EntityLearningProtocol.scanDashboard(
            """{"views":[{"cards":[{"type":"custom:sample-card","value":${org.json.JSONObject.quote(literal)}}]}]}""",
        )

        val expression = scan.dynamicExpressions.single()
        assertEquals("dashboard.views[0].cards[0].value", expression.sourceLocation)
        assertEquals(literal, expression.literal)
        assertFalse(expression.truncated)
        assertEquals(expression, scan.dynamicExpressions.single())
        assertEquals(literal, expression.toJson().getString("literal"))
        assertEquals(expression.sourceLocation, expression.toJson().getString("source_location"))
    }

    @Test fun dynamicExpressionLiteralIsBoundedAndFingerprintUsesTheCompleteValue() {
        val prefix = "{{ "
        val first = prefix + "a".repeat(EntityLearningProtocol.MAX_DYNAMIC_EXPRESSION_LENGTH + 100) + " }}"
        val second = prefix + "a".repeat(EntityLearningProtocol.MAX_DYNAMIC_EXPRESSION_LENGTH + 99) + "b }}"
        fun scan(value: String) = EntityLearningProtocol.scanDashboard(
            """{"views":[{"cards":[{"type":"markdown","content":${org.json.JSONObject.quote(value)}}]}]}""",
        ).dynamicExpressions.single()

        val a = scan(first)
        val b = scan(second)

        assertEquals(EntityLearningProtocol.MAX_DYNAMIC_EXPRESSION_LENGTH, a.literal.length)
        assertTrue(a.truncated)
        assertEquals(a.literal, b.literal)
        assertFalse(a.fingerprint == b.fingerprint)
    }

    @Test fun autoEntitiesFilterExpressionsAreObservedButNotPromotedAsStaticIds() {
        val literal = "{{ states('sensor.selector_source') }}"
        val scan = EntityLearningProtocol.scanDashboard(
            """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{"include":[{"entity_id":${org.json.JSONObject.quote(literal)}}]}}]}]}""",
        )

        assertTrue(scan.entityIds.isEmpty())
        assertEquals(literal, scan.dynamicExpressions.single().literal)
        assertEquals(
            "dashboard.views[0].cards[0].filter.include[0].entity_id",
            scan.dynamicExpressions.single().sourceLocation,
        )
        assertTrue(scan.unresolved.none { it.contains("custom:auto-entities") })
        val lint = DashboardConfigurationLint.analyze(
            """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{"include":[{"entity_id":${org.json.JSONObject.quote(literal)}}]}}]}]}""",
            listOf("sensor.sample"),
            emptyMap(),
        )
        assertTrue(lint.blocking)
        assertEquals(DashboardConfigurationLint.IssueType.UNBOUNDED_SELECTOR, lint.issues.single().type)
    }

    @Test fun dynamicExpressionsAreDeterministicallyOrderedBySource() {
        val scan = EntityLearningProtocol.scanDashboard(
            """{"views":[{"cards":[
              {"type":"markdown","content":"{{ states('sensor.first') }}"},
              {"type":"markdown","content":"[[[ return states['sensor.second']; ]]]"}
            ]}]}""",
        )

        assertEquals(
            listOf("dashboard.views[0].cards[0].content", "dashboard.views[0].cards[1].content"),
            scan.dynamicExpressions.map { it.sourceLocation },
        )
        assertEquals(2, scan.dynamicExpressions.map { it.fingerprint }.distinct().size)
    }

    @Test fun canonicalDashboardHashIsIndependentOfObjectKeyOrder() {
        val a = EntityLearningProtocol.canonical(org.json.JSONObject("""{"b":2,"a":{"d":4,"c":3}}"""))
        val b = EntityLearningProtocol.canonical(org.json.JSONObject("""{"a":{"c":3,"d":4},"b":2}"""))
        assertEquals(a, b)
        assertEquals(EntityLearningProtocol.hash(a), EntityLearningProtocol.hash(b))
    }

    @Test fun accessAndMetricBatchesAcceptOnlyEntityIds() {
        val access = EntityLearningProtocol.parseAccessBatch(
            """{"accessed":["light.a","bad"],"missing":["sensor.b","Light.c"]}""",
        )
        assertEquals(mapOf("light.a" to 1L), access.first)
        assertEquals(setOf("sensor.b"), access.second)
        assertEquals(mapOf("sensor.b" to (3L to 120L)), EntityLearningProtocol.parseMetricEnvelope("""{"sensor.b":[3,120],"bad":[9,9]}""").metrics)
    }

    @Test fun accessBatchPreservesLookupCountsAndAcceptsPrototypeArrays() {
        val counted = EntityLearningProtocol.parseAccessBatch(
            """{"accessed":{"light.kitchen":17,"sensor.room":2},"missing":[]}""",
        )
        assertEquals(mapOf("light.kitchen" to 17L, "sensor.room" to 2L), counted.first)
        assertEquals(mapOf("light.kitchen" to 1L), EntityLearningProtocol.parseAccessBatch(
            """{"accessed":["light.kitchen"],"missing":[]}""",
        ).first)
    }

    @Test fun dashboardUrlPathDistinguishesExplicitDefaultLovelace() {
        assertEquals("sample-panel", EntityLearningProtocol.dashboardUrlPath("/sample-panel/dash"))
        assertEquals("", EntityLearningProtocol.dashboardUrlPath("/lovelace/0"))
        assertFalse(EntityLearningProtocol.usesFrontendDefaultPanel("/lovelace/0"))
    }

    @Test fun blankOrRootDashboardRequiresAuthenticatedResolution() {
        for (configured in listOf("", "   ", "/", " /?kiosk ", "/#view")) {
            assertTrue(EntityLearningProtocol.usesFrontendDefaultPanel(configured))
        }
    }

    @Test fun deterministicHomeDashboardResolutionUsesOnlyAuthenticatedLegalChoices() {
        val legal = listOf(
            EntityLearningProtocol.HomeDashboardChoice("/lovelace", "Overview"),
            EntityLearningProtocol.HomeDashboardChoice("/alpha", "Office"),
            EntityLearningProtocol.HomeDashboardChoice("/energy", "Energy"),
        )
        assertEquals(
            EntityLearningProtocol.HomeDashboardResolution(
                "/alpha/view?kiosk=1#main",
                EntityLearningProtocol.HomeDashboardSource.EXPLICIT,
            ),
            EntityLearningProtocol.resolveHomeDashboard(
                "/alpha/view?kiosk=1#main", "lovelace", "energy", legal,
            ),
        )
        assertEquals(
            "/alpha/Upper%20Floor?panel=Wall#main",
            EntityLearningProtocol.resolveHomeDashboard(
                "/alpha/Upper%20Floor?panel=Wall#main", null, null, legal,
            ).path,
        )
        assertEquals(
            EntityLearningProtocol.HomeDashboardResolution(
                "/lovelace", EntityLearningProtocol.HomeDashboardSource.USER_DEFAULT,
            ),
            EntityLearningProtocol.resolveHomeDashboard("/deleted/view", "lovelace", "energy", legal),
        )
        assertEquals(
            EntityLearningProtocol.HomeDashboardResolution(
                "/energy", EntityLearningProtocol.HomeDashboardSource.SYSTEM_DEFAULT,
            ),
            EntityLearningProtocol.resolveHomeDashboard("", "stale-user", "energy", legal),
        )
        assertEquals(
            EntityLearningProtocol.HomeDashboardResolution(
                "/lovelace", EntityLearningProtocol.HomeDashboardSource.FIRST_LEGAL,
            ),
            EntityLearningProtocol.resolveHomeDashboard("", null, "stale-system", legal),
        )
        assertEquals(
            EntityLearningProtocol.HomeDashboardResolution(),
            EntityLearningProtocol.resolveHomeDashboard("/alpha/view", "alpha", "energy", emptyList()),
        )
    }

    @Test fun malformedDashboardCandidatesNeverEscapeTheAuthenticatedLegalList() {
        val legal = listOf(
            EntityLearningProtocol.HomeDashboardChoice("/alpha", "Office"),
            EntityLearningProtocol.HomeDashboardChoice("/energy", "Energy"),
        )
        val malformed = listOf(
            "https://ha.example/wall-panel",
            "//ha.example/wall-panel",
            "../wall-panel",
            "wall%2fpanel",
            "wall\\panel",
            "null",
        )
        for (candidate in malformed) {
            assertEquals(
                EntityLearningProtocol.HomeDashboardSource.USER_DEFAULT,
                EntityLearningProtocol.resolveHomeDashboard(candidate, "alpha", "energy", legal).source,
            )
            assertEquals(
                EntityLearningProtocol.HomeDashboardSource.SYSTEM_DEFAULT,
                EntityLearningProtocol.resolveHomeDashboard("", candidate, "energy", legal).source,
            )
            assertEquals(
                EntityLearningProtocol.HomeDashboardSource.FIRST_LEGAL,
                EntityLearningProtocol.resolveHomeDashboard("", null, candidate, legal).source,
            )
        }
        for (traversal in listOf("/alpha/%2e%2e/evil", "/alpha/%2Foutside")) {
            assertEquals(
                EntityLearningProtocol.HomeDashboardSource.USER_DEFAULT,
                EntityLearningProtocol.resolveHomeDashboard(traversal, "alpha", "energy", legal).source,
            )
        }
    }

    @Test fun homeDashboardChoicesPreserveHaOrderAndHideAdminOnlyDashboards() {
        val dashboards = JSONArray("""
            [
              {"url_path":"lovelace","title":"Home"},
              {"url_path":"alpha","title":"Office"},
              {"url_path":"admin","title":"Admin","require_admin":true},
              {"url_path":"alpha","title":"Duplicate"},
              {"url_path":"https://invalid","title":"Invalid"}
            ]
        """.trimIndent())

        assertEquals(
            listOf(
                EntityLearningProtocol.HomeDashboardChoice("/lovelace", "Home"),
                EntityLearningProtocol.HomeDashboardChoice("/alpha", "Office"),
            ),
            EntityLearningProtocol.homeDashboardChoices(dashboards, isAdmin = false),
        )
        assertEquals(
            listOf("/lovelace", "/alpha", "/admin"),
            EntityLearningProtocol.homeDashboardChoices(dashboards, isAdmin = true).map { it.path },
        )
    }

    @Test fun homeDashboardChoicesCarrySanitizedIconsForThePicker() {
        // The picker renders each dashboard's own mdi icon like HA does; a malformed icon value must reduce
        // to blank (client falls back to mdi:view-dashboard) rather than reach innerHTML.
        val dashboards = JSONArray("""
            [
              {"url_path":"alpha","title":"Office","icon":"mdi:sofa"},
              {"url_path":"garden","title":"Garden","icon":"<script>alert(1)</script>"},
              {"url_path":"plain","title":"Plain"}
            ]
        """.trimIndent())
        assertEquals(
            listOf("mdi:sofa", "", ""),
            EntityLearningProtocol.homeDashboardChoices(dashboards, isAdmin = true).map { it.icon },
        )
    }

    @Test fun panelDashboardChoicesFollowHaFixedOrderAndRespectAdminGating() {
        // HA's picker lists the built-in panel dashboards in the frontend's fixed PANEL_DASHBOARDS order
        // (home, light, security, climate, energy, maintenance), not registration or sidebar order, and
        // only those that actually exist on this installation.
        val panels = JSONObject("""
            {
              "energy": {"url_path":"energy","title":"Energy","icon":"mdi:lightning-bolt"},
              "home": {"url_path":"home","icon":"mdi:home"},
              "maintenance": {"url_path":"maintenance","title":"Maintenance","icon":"mdi:wrench-clock","require_admin":true},
              "lovelace": {"url_path":"lovelace","title":"Overview"}
            }
        """.trimIndent())
        val nonAdmin = EntityLearningProtocol.panelDashboardChoices(panels, isAdmin = false)
        assertEquals(listOf("/home", "/energy"), nonAdmin.map { it.path })
        // A blank panel title falls back to the capitalized key; groups mark these as the panel section.
        assertEquals(listOf("Home", "Energy"), nonAdmin.map { it.title })
        assertEquals(listOf("panel", "panel"), nonAdmin.map { it.group })
        assertEquals(
            listOf("/home", "/energy", "/maintenance"),
            EntityLearningProtocol.panelDashboardChoices(panels, isAdmin = true).map { it.path },
        )
        assertEquals(emptyList<EntityLearningProtocol.HomeDashboardChoice>(),
            EntityLearningProtocol.panelDashboardChoices(null, isAdmin = true))
    }

    @Test fun homeDashboardDefaultReportsWhetherARealServerSideDefaultExists() {
        // Since HA 2025.12 the default dashboard is per-user server data (user overrides system). Setup
        // demotes "follow the account's default" when neither layer names one, because the effective
        // default is then HA's own fallback — rarely what a wall panel should show.
        val legal = listOf(
            EntityLearningProtocol.HomeDashboardChoice("/energy", "Energy"),
            EntityLearningProtocol.HomeDashboardChoice("/alpha", "Office"),
        )
        assertEquals(
            EntityLearningProtocol.HomeDashboardDefault(explicit = true, path = "/energy"),
            EntityLearningProtocol.homeDashboardDefault("energy", null, legal),
        )
        assertEquals(
            EntityLearningProtocol.HomeDashboardDefault(explicit = true, path = "/alpha"),
            EntityLearningProtocol.homeDashboardDefault("", "alpha", legal),
        )
        assertEquals(
            EntityLearningProtocol.HomeDashboardDefault(),
            EntityLearningProtocol.homeDashboardDefault(null, null, legal),
        )
        // Malformed and out-of-list user values are rejected; a legal system value remains eligible.
        assertEquals(
            EntityLearningProtocol.HomeDashboardDefault(explicit = true, path = "/alpha"),
            EntityLearningProtocol.homeDashboardDefault("https://evil", "alpha", legal),
        )
    }

    @Test fun learningScriptPreservesWebsocketShapeAndBatchesBridgeCalls() {
        val script = EntityLearningProtocol.documentStartScript("https://ha.example")
        assertTrue(script.contains("entityLearningAccesses"))
        assertTrue(script.contains("entityLearningMetrics"))
        assertTrue(script.contains("Object.setPrototypeOf(LearningWebSocket,Parent)"))
        assertTrue(script.contains("wss://ha.example"))
    }

    @Test fun autoEntitiesExcludeIdsAreNotCollectedAsOrdinaryStaticReferences() {
        val scan = EntityLearningProtocol.scanDashboard(
            """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{"include":[{"domain":"light"}],"exclude":[{"entity_id":"light.hidden"}]}}]}]}""",
        )
        assertFalse("light.hidden" in scan.entityIds)
        assertTrue(scan.unresolved.none { it.contains("custom:auto-entities") })
    }

    @Test fun autoEntitiesIncludeOptionsContributeDependenciesWithoutPromotingSelectorCriteria() {
        val scan = EntityLearningProtocol.scanDashboard(
            """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{
              "include":[{"entity_id":"light.visible","options":{"tap_action":{
                "action":"more-info","entity":"script.include_helper"
              }}}],
              "exclude":[{"entity_id":"light.hidden"}]
            }}]}]}""",
        )

        assertEquals(setOf("script.include_helper"), scan.entityIds)
        assertFalse("light.visible" in scan.entityIds)
        assertFalse("light.hidden" in scan.entityIds)
        assertTrue(scan.unresolved.none { it.contains("custom:auto-entities") })
    }

    @Test fun autoEntitiesTypedIncludesAreScannedAsDirectCardConfiguration() {
        val scan = EntityLearningProtocol.scanDashboard(
            """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{"include":[
              {"type":"entity","entity":"sensor.direct_row","tap_action":{"target":{"entity_id":"script.helper"}}}
            ]}}]}]}""",
        )

        assertEquals(setOf("sensor.direct_row", "script.helper"), scan.entityIds)
        assertTrue(scan.unresolved.none { it.contains("custom:auto-entities") })
    }

    @Test fun thirdPartySelectorGrammarsRemainExplicitlyUnresolved() {
        val scan = EntityLearningProtocol.scanDashboard(
            """{"views":[{"cards":[
              {"type":"custom:battery-state-card","filter":{"include":[{"name":"entity_id","value":"sensor.*"}]}},
              {"type":"custom:flex-table-card","entities":{"include":"sensor.*"}}
            ]}]}""",
        )

        assertTrue(scan.unresolved.any { it.contains("custom:battery-state-card") })
        assertTrue(scan.unresolved.any { it.contains("custom:flex-table-card") })
    }

    @Test fun unknownWrapperAroundKnownAutoEntitiesRemainsUnresolved() {
        val scan = EntityLearningProtocol.scanDashboard(
            """{"views":[{"cards":[{"type":"custom:mystery-wrapper","cards":[
              {"type":"custom:auto-entities","filter":{"include":[{"domain":"light"}]}}
            ]}]}]}""",
        )

        assertTrue(scan.unresolved.any { it.contains("custom:mystery-wrapper") })
        assertTrue(scan.unresolved.none { it.contains("custom:auto-entities") })
    }

    @Test fun regexAndTemplateTargetsAreObservedAsDynamicNotSentToHomeAssistant() {
        val scan = EntityLearningProtocol.scanDashboard(
            """{"views":[{"cards":[
              {"type":"custom:auto-entities","filter":{"include":[{"entity_id":"/.*_rssi/"}]}},
              {"type":"button","tap_action":{"target":{"entity_id":"{{ dynamic_entity }}","area_id":"office"}}}
            ]}]}""",
        )
        assertEquals(1, scan.targets.size)
        assertTrue(scan.targets.single().contains("area_id"))
        assertFalse(scan.targets.single().contains("entity_id"))
        assertEquals(1, scan.unresolved.count { it.contains("dynamic-target") })
    }

    @Test fun literalEntityTargetsAreResolvedLocallyWithoutHomeAssistantExpansion() {
        val scan = EntityLearningProtocol.scanDashboard(
            """{"views":[{"cards":[
              {"type":"tile","entity_id":"light.kitchen"},
              {"type":"button","target":{"entity_id":["switch.fan","switch.heater"]}},
              {"type":"button","target":{"entity_id":"cover.blind","device_id":"device_123"}}
            ]}]}""",
        )
        assertEquals(setOf("light.kitchen", "switch.fan", "switch.heater", "cover.blind"), scan.entityIds)
        assertEquals(1, scan.targets.size)
        assertTrue(scan.targets.single().contains("device_id"))
        assertFalse(scan.targets.single().contains("entity_id"))
    }
}
