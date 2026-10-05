package io.panelassistant.android.dashboard

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardConfigurationLintTest {
    @Test fun warningIssueTypesAreNeverBlocking() {
        val warnings = DashboardConfigurationLint.IssueType.entries.filter { it.severity == "warning" }

        assertTrue(warnings.isNotEmpty())
        assertTrue(warnings.all { !it.blocking && !it.ignorable })
    }

    @Test
    fun slashRegexSafetyRejectsBacktrackingConstructsButKeepsCommonEntityPatterns() {
        assertTrue(DashboardConfigurationLint.isSafeEntitySelectorRegex("^sensor\\..*_temperature$"))
        assertTrue(DashboardConfigurationLint.isSafeEntitySelectorRegex("^sensor\\.[a-z0-9_]+$"))
        assertFalse(DashboardConfigurationLint.isSafeEntitySelectorRegex("^sensor\\..*.*_temperature$"))
        assertFalse(DashboardConfigurationLint.isSafeEntitySelectorRegex("^.*sensor.*$"))
        assertFalse(DashboardConfigurationLint.isSafeEntitySelectorRegex("^sensor\\.[a-z]+_[a-z]+$"))
        assertFalse(DashboardConfigurationLint.isSafeEntitySelectorRegex("(a+)+$"))
        assertFalse(DashboardConfigurationLint.isSafeEntitySelectorRegex("(a|aa)+$"))
        assertFalse(DashboardConfigurationLint.isSafeEntitySelectorRegex("^(.*.*.*.*.*)z$"))
        assertFalse(DashboardConfigurationLint.isSafeEntitySelectorRegex("^(sensor)\\.\\1$"))
    }

    @Test fun resolvesStructuralSupersetsWithoutUsingDynamicRefinementsOrDisplayLimits() {
        val config = """{"views":[{"title":"Overview","path":"overview","cards":[
          {"type":"custom:auto-entities","title":"Room lights","filter":{"include":[
            {"domain":"light","area":"study","state":"on","options":{"secondary_info":"last-changed"}}
          ],"exclude":[{"entity_id":"light.study_hidden"}]},"sort":{"count":1}}
        ]}]}"""
        val catalog = listOf("light.study_main", "light.study_hidden", "light.kitchen", "sensor.study")
        val metadata = mapOf(
            "light.study_main" to """{"ai":"study"}""",
            "light.study_hidden" to """{"ai":"study"}""",
            "light.kitchen" to """{"ai":"kitchen"}""",
            "sensor.study" to """{"ai":"study"}""",
        )

        val result = DashboardConfigurationLint.analyze(
            config, catalog, metadata, registryMetadataComplete = true,
        )

        assertEquals(setOf("light.study_main"), result.safeEntityIds)
        assertFalse(result.blocking)
    }

    @Test fun supportsExactGlobRegexAreaFloorAndLabelStructuralBounds() {
        val config = """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{"include":[
          {"entity_id":"sensor.exact"},
          {"entity_id":"light.study_*"},
          {"entity_id":"/.*_rssi$/"},
          {"area":"study"},
          {"floor":"upper"},
          {"label":"important"}
        ]}}]}]}"""
        val catalog = listOf(
            "sensor.exact", "light.study_main", "sensor.router_rssi", "binary_sensor.study_window",
            "cover.upper_blind", "switch.priority", "sensor.other",
        )
        val metadata = mapOf(
            "binary_sensor.study_window" to """{"ai":"study"}""",
            "cover.upper_blind" to """{"fi":"upper"}""",
            "switch.priority" to """{"lb":["important"]}""",
        )

        val result = DashboardConfigurationLint.analyze(
            config, catalog, metadata, registryMetadataComplete = true,
        )

        assertEquals(catalog.dropLast(1).toSet(), result.safeEntityIds)
        assertTrue(result.issues.isEmpty())
    }

    @Test fun dynamicExcludeDoesNotUnsafelyReduceCandidateSuperset() {
        val config = """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{
          "include":[{"domain":"sensor"}],
          "exclude":[{"domain":"sensor","state":"unavailable"}]
        }}]}]}"""
        val catalog = (1..65).map { "sensor.sample_$it" }

        val result = DashboardConfigurationLint.analyze(config, catalog, emptyMap())

        assertTrue(result.safeEntityIds.isEmpty())
        assertEquals(DashboardConfigurationLint.IssueType.BROAD_SELECTOR, result.issues.single().type)
        assertEquals(65, result.issues.single().candidateCount)
    }

    @Test fun nestedMissingAndMalformedIncludesAreOwnedAndBlockedBySelectorLint() {
        val config = """{"views":[{"cards":[{"type":"vertical-stack","cards":[
          {"type":"custom:auto-entities","filter":{"include":[{"entity_id":"sensor.visible"}]}},
          {"type":"custom:auto-entities","filter":{"exclude":[{"entity_id":"sensor.hidden"}]}},
          {"type":"custom:auto-entities","filter":{"include":[{}]}}
        ]}]}]}"""

        val result = DashboardConfigurationLint.analyze(config, listOf("sensor.visible", "sensor.hidden"), emptyMap())

        assertTrue(result.blocking)
        assertEquals(setOf("sensor.visible"), result.safeEntityIds)
        assertEquals(2, result.issues.size)
        assertTrue(result.issues.all { it.type == DashboardConfigurationLint.IssueType.UNBOUNDED_SELECTOR })
        assertEquals(
            listOf(
                "dashboard.views[0].cards[0].cards[1]",
                "dashboard.views[0].cards[0].cards[2]",
            ),
            result.issues.flatMap { it.sourceLocations }.sorted(),
        )
    }

    @Test fun friendlyNameRegexBoundsCommonAlarmSelectorsBeforeApplyingDynamicStateRules() {
        val config = """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{
          "include":[{"domain":"binary_sensor","name":"/otion [Gg]roup/","state":"on","sort":{"method":"friendly_name"}}],
          "exclude":[{"state":"unknown"},{"state":"unavailable"}]
        }}]}]}"""
        val catalog = entities("binary_sensor", 537)
        val names = catalog.associateWith { id ->
            when (id) {
                "binary_sensor.sample_7" -> "Ground Floor Motion Group"
                "binary_sensor.sample_42" -> "Garage motion group"
                else -> "Contact ${id.substringAfterLast('_')}"
            }
        }

        val result = DashboardConfigurationLint.analyze(config, catalog, emptyMap(), names)

        assertEquals(setOf("binary_sensor.sample_7", "binary_sensor.sample_42"), result.safeEntityIds)
        assertTrue(result.issues.isEmpty())
    }

    @Test fun friendlyNameMatchingIsCaseSensitiveAndMissingNamesNeverMatch() {
        val config = """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{"include":[
          {"domain":"binary_sensor","name":"Window Group"}
        ]}}]}]}"""
        val catalog = listOf("binary_sensor.exact", "binary_sensor.lower", "binary_sensor.missing")

        val result = DashboardConfigurationLint.analyze(
            config, catalog, emptyMap(),
            mapOf("binary_sensor.exact" to "Window Group", "binary_sensor.lower" to "window group"),
        )

        assertEquals(setOf("binary_sensor.exact"), result.safeEntityIds)
        assertTrue(result.issues.isEmpty())
    }

    @Test fun unsafeFriendlyNamePatternCannotNarrowABroadDomain() {
        val config = """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{"include":[
          {"domain":"binary_sensor","name":"/(.*.*)group/"}
        ]}}]}]}"""
        val catalog = entities("binary_sensor", 65)
        val names = catalog.associateWith { "Window group" }

        val result = DashboardConfigurationLint.analyze(config, catalog, emptyMap(), names)

        assertTrue(result.safeEntityIds.isEmpty())
        assertEquals(DashboardConfigurationLint.IssueType.BROAD_SELECTOR, result.issues.single().type)
        assertEquals(65, result.issues.single().candidateCount)
    }

    @Test fun structuralExcludeCanBoundASelectorWithoutSamplingIt() {
        val config = """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{
          "include":[{"domain":"sensor"}],
          "exclude":[{"entity_id":"sensor.omit_*"}]
        }}]}]}"""
        val catalog = entities("sensor", 64) + (1..20).map { "sensor.omit_$it" }

        val result = DashboardConfigurationLint.analyze(config, catalog, emptyMap())

        assertEquals(64, result.safeEntityIds.size)
        assertTrue(result.safeEntityIds.none { it.startsWith("sensor.omit_") })
        assertTrue(result.issues.isEmpty())
    }

    @Test fun unboundedTemplateIsBlockingAndNeverLeaksTemplateText() {
        val secretMarker = "private_marker"
        val config = """{"views":[{"title":"Overview","cards":[
          {"type":"custom:auto-entities","title":"{{ $secretMarker }}","filter":{"include":[
            {"entity_id":"{{ states('input_text.$secretMarker') }}","state":"on"}
          ]}}
        ]}]}"""

        val result = DashboardConfigurationLint.analyze(config, listOf("light.sample"), emptyMap())
        val issue = result.issues.single()

        assertTrue(result.blocking)
        assertEquals(DashboardConfigurationLint.IssueType.UNBOUNDED_SELECTOR, issue.type)
        assertNull(issue.cardTitle)
        assertFalse(issue.toJson().toString().contains(secretMarker))
    }

    @Test fun templateFilterAdvisorySeparatesReturnedEntitiesFromServerEvaluatedReads() {
        // The card from issue #113, verbatim in shape: a filter template whose *condition* reads one
        // entity and whose *result* names another. The reporter's complaint was that allowing the check
        // still did not add sensor.hourly_tick. It never should: Home Assistant renders the template and
        // delivers the result over a render_template subscription, which EntityFilterProtocol does not
        // mutate, so that entity is supposed to be absent. Advising a pin for it would inflate the very
        // list the filter exists to shrink, which is the asymmetry these assertions hold.
        val config = """{"views":[{"title":"Cameras","cards":[{
          "type":"custom:auto-entities",
          "card":{"type":"picture-entity","entity":"camera.driveway_medium_resolution_channel"},
          "filter":{"template":"{% if states('sensor.hourly_tick') %}\n  {{ [{'entity': 'camera.driveway_medium_resolution_channel'}] }}\n{% endif %}"},
          "sort":{"method":"unused"},"show_empty":true
        }]}]}"""

        val result = DashboardConfigurationLint.analyze(
            config,
            listOf("sensor.hourly_tick", "camera.driveway_medium_resolution_channel"),
            emptyMap(),
        )
        val issue = result.issues.single()
        val payload = issue.toJson().toString()

        assertTrue(result.blocking)
        assertEquals(DashboardConfigurationLint.IssueType.UNBOUNDED_SELECTOR, issue.type)
        assertEquals("Unbounded template entity selector", issue.ruleSummary)

        // Neither the entity the template returns nor the entity it only tests is inferred. Promoting
        // either would mean reading the template, which is the boundary this check exists to hold.
        assertEquals(emptySet<String>(), result.safeEntityIds)

        // The explanation separates the two kinds and gives them opposite advice: what the template
        // returns can need a pin, what it only reads never does.
        val reason = requireNotNull(issue.reason)
        assertTrue(reason, reason.contains("does not evaluate templates"))
        assertTrue(reason, reason.contains("which entities this filter returns"))
        assertTrue(reason, reason.contains("delivered outside this filter"))
        assertTrue(reason, reason.contains("they need nothing"))
        val recommendation = requireNotNull(issue.recommendation)
        assertTrue(recommendation, recommendation.contains("pin only the entities the template returns"))
        // The advice a reader could act on wrongly: never tell anyone to pin what the template reads.
        assertFalse(recommendation, recommendation.contains("only tests"))
        assertFalse(reason, reason.contains("is a dependency too"))

        // The advisory record carries no template text and no entity ID lifted out of one.
        assertFalse(payload, payload.contains("hourly_tick"))
        assertFalse(payload, payload.contains("driveway_medium_resolution_channel"))
        assertFalse(payload, payload.contains("{%"))
        assertFalse(payload, payload.contains("{{"))
    }

    @Test fun templateFilterConditionEntityIsNeverPromotedByTheScanner() {
        // The other half of the same report: the dashboard scanner records the expression as evidence
        // for the operator but contributes no entity ID from inside it.
        val config = """{"views":[{"cards":[{
          "type":"custom:auto-entities",
          "filter":{"template":"{% if states('sensor.hourly_tick') %}{{ [{'entity': 'camera.driveway'}] }}{% endif %}"}
        }]}]}"""

        val scan = EntityLearningProtocol.scanDashboard(config)

        assertFalse(scan.entityIds.contains("sensor.hourly_tick"))
        assertFalse(scan.entityIds.contains("camera.driveway"))
        val expression = scan.dynamicExpressions.single()
        assertTrue(expression.literal.contains("sensor.hourly_tick"))
        assertEquals("dashboard.views[0].cards[0].filter.template", expression.sourceLocation)
    }

    @Test fun cardLevelTemplateBlocksEvenWhenIncludeRulesAreOtherwiseBounded() {
        val secretMarker = "private_template_marker"
        val config = """{"views":[{"title":"Overview","cards":[{
          "type":"custom:auto-entities","title":"Bounded-looking selector",
          "filter":{
            "template":"{{ states.sensor | selectattr('entity_id', 'contains', '$secretMarker') | map(attribute='entity_id') | list }}",
            "include":[{"entity_id":"sensor.explicit"}]
          }
        }]}]}"""

        val result = DashboardConfigurationLint.analyze(
            config,
            listOf("sensor.explicit", "sensor.dynamic_result"),
            emptyMap(),
        )

        assertTrue(result.blocking)
        assertEquals(setOf("sensor.explicit"), result.safeEntityIds)
        assertEquals(DashboardConfigurationLint.IssueType.UNBOUNDED_SELECTOR, result.issues.single().type)
        assertEquals(listOf("dashboard.views[0].cards[0]"), result.issues.single().sourceLocations)
        assertFalse(result.issues.single().toJson().toString().contains(secretMarker))
    }

    @Test fun areaCardIncludesDirectAndDeviceInheritedMembersWithoutFloorOrLabelWidening() {
        val config = """{"views":[{"cards":[{"type":"area","area":"study"}]}]}"""
        val catalog = listOf(
            "light.direct_member",
            "switch.device_inherited_member",
            "sensor.same_floor_only",
            "binary_sensor.same_label_only",
            "cover.unrelated",
        )
        val metadata = mapOf(
            "light.direct_member" to """{"ai":"study","fi":"upper","lb":["lighting"]}""",
            "switch.device_inherited_member" to """{"ai":"study","fi":"upper"}""",
            "sensor.same_floor_only" to """{"ai":"hall","fi":"upper"}""",
            "binary_sensor.same_label_only" to """{"ai":"entry","fi":"ground","lb":["study"]}""",
            "cover.unrelated" to """{"ai":"garage","fi":"ground"}""",
        )

        val result = DashboardConfigurationLint.analyze(
            config, catalog, metadata, registryMetadataComplete = true,
        )

        assertEquals(setOf("light.direct_member", "switch.device_inherited_member"), result.safeEntityIds)
        assertTrue(result.issues.isEmpty())
    }

    @Test fun structuredDisplayNameSourcesAreNotEntitySelectors() {
        listOf("area", "device", "entity", "floor").forEach { nameSource ->
            val config = """{"views":[{"cards":[{
              "type":"media-control",
              "entity":"media_player.office_musicassistant",
              "name":{"type":"$nameSource"}
            }]}]}"""

            val result = DashboardConfigurationLint.analyze(
                config,
                listOf("media_player.office_musicassistant"),
                emptyMap(),
                registryMetadataComplete = false,
            )

            assertFalse(nameSource, result.blocking)
            assertTrue(nameSource, result.issues.isEmpty())
            assertTrue(nameSource, result.safeEntityIds.isEmpty())
            assertEquals(
                nameSource,
                DashboardConfigurationLint.RegistryRequirements(),
                DashboardConfigurationLint.registryRequirements(config),
            )
            assertEquals(
                nameSource,
                setOf("media_player.office_musicassistant"),
                EntityLearningProtocol.scanDashboard(config).entityIds,
            )
        }
    }

    @Test fun compositeDisplayNameIsNotTraversedAsCardConfiguration() {
        val config = """{"views":[{"cards":[{
          "type":"media-control",
          "entity":"media_player.office_musicassistant",
          "name":[{"type":"text","text":"Office"},{"type":"area"},{"type":"device"},{"type":"entity"},{"type":"floor"}]
        }]}]}"""

        val result = DashboardConfigurationLint.analyze(
            config,
            listOf("media_player.office_musicassistant"),
            emptyMap(),
            registryMetadataComplete = false,
        )

        assertTrue(result.issues.isEmpty())
        assertFalse(DashboardConfigurationLint.registryRequirements(config).any)
        assertEquals(
            setOf("media_player.office_musicassistant"),
            EntityLearningProtocol.scanDashboard(config).entityIds,
        )
    }

    @Test fun genuineNestedAreaCardStillRequiresAStaticAreaId() {
        val result = DashboardConfigurationLint.analyze(
            """{"views":[{"cards":[{"type":"vertical-stack","cards":[{"type":"area"}]}]}]}""",
            emptyList(),
            emptyMap(),
            registryMetadataComplete = false,
        )

        assertTrue(result.blocking)
        assertEquals("Area card has no static area ID", result.issues.single().ruleSummary)
        assertEquals(
            listOf("dashboard.views[0].cards[0].cards[0]"),
            result.issues.single().sourceLocations,
        )
    }

    @Test fun customFieldNamedNameStillTraversesItsNestedCard() {
        val config = """{"views":[{"cards":[{
          "type":"custom:button-card",
          "entity":"media_player.office_musicassistant",
          "custom_fields":{"name":{"card":{"type":"area","area":"office"}}}
        }]}]}"""
        val metadata = mapOf("media_player.office_musicassistant" to """{"ai":"office"}""")

        val result = DashboardConfigurationLint.analyze(
            config,
            listOf("media_player.office_musicassistant"),
            metadata,
            registryMetadataComplete = true,
        )

        assertEquals(setOf("media_player.office_musicassistant"), result.safeEntityIds)
        assertEquals(
            DashboardConfigurationLint.RegistryRequirements(entities = true, areas = true, devices = true),
            DashboardConfigurationLint.registryRequirements(config),
        )
    }

    @Test fun areaCardIncludesExternalTemperatureAndHumidityOverridesFromAreaRegistryProjection() {
        val config = """{"views":[{"cards":[{"type":"area","area":"study"}]}]}"""
        val catalog = listOf(
            "light.study",
            "sensor.remote_temperature",
            "sensor.remote_humidity",
            "sensor.unrelated",
        )
        val metadata = mapOf(
            "light.study" to """{"ai":"study"}""",
            "sensor.remote_temperature" to """{"ai":"utility"}""",
            "sensor.remote_humidity" to """{"ai":"utility"}""",
            "sensor.unrelated" to """{"ai":"utility"}""",
        )

        val result = DashboardConfigurationLint.analyze(
            config,
            catalog,
            metadata,
            areaRegistryEntities = mapOf(
                "study" to setOf("sensor.remote_temperature", "sensor.remote_humidity"),
            ),
            registryMetadataComplete = true,
        )

        assertEquals(
            setOf("light.study", "sensor.remote_temperature", "sensor.remote_humidity"),
            result.safeEntityIds,
        )
        assertTrue(result.issues.isEmpty())
    }

    @Test fun areaCardRetainsRegistryBackedMembersThatAreTemporarilyStateless() {
        val result = DashboardConfigurationLint.analyze(
            """{"views":[{"cards":[{"type":"area","area":"study"}]}]}""",
            catalog = listOf("light.live_member"),
            metadataJson = mapOf(
                "light.live_member" to """{"ai":"study"}""",
                "switch.stateless_member" to """{"ai":"study"}""",
            ),
            areaRegistryEntities = mapOf("study" to setOf("sensor.stateless_temperature")),
            registryMetadataComplete = true,
        )

        assertEquals(
            setOf("light.live_member", "switch.stateless_member", "sensor.stateless_temperature"),
            result.safeEntityIds,
        )
        assertFalse(result.blocking)
    }

    @Test fun areaCardBlocksWhenRegistryMetadataIsIncomplete() {
        val config = """{"views":[{"cards":[{"type":"area","area":"study"}]}]}"""

        val result = DashboardConfigurationLint.analyze(
            config,
            listOf("light.study"),
            mapOf("light.study" to """{"ai":"study"}"""),
            registryMetadataComplete = false,
        )

        assertTrue(result.blocking)
        assertEquals(DashboardConfigurationLint.IssueType.UNBOUNDED_SELECTOR, result.issues.single().type)
        assertEquals(listOf("dashboard.views[0].cards[0]"), result.issues.single().sourceLocations)
    }

    @Test fun areaCardAcceptsExactlyPerSelectorLimitAndBlocksOneMore() {
        fun analyze(count: Int): DashboardConfigurationLint.Result {
            val catalog = entities("sensor", count)
            val metadata = catalog.associateWith { """{"ai":"study"}""" }
            return DashboardConfigurationLint.analyze(
                """{"views":[{"cards":[{"type":"area","area":"study"}]}]}""",
                catalog,
                metadata,
                registryMetadataComplete = true,
            )
        }

        val atLimit = analyze(DashboardConfigurationLint.SELECTOR_ENTITY_LIMIT)
        assertEquals(DashboardConfigurationLint.SELECTOR_ENTITY_LIMIT, atLimit.safeEntityIds.size)
        assertTrue(atLimit.issues.isEmpty())

        val overLimit = analyze(DashboardConfigurationLint.SELECTOR_ENTITY_LIMIT + 1)
        assertTrue(overLimit.safeEntityIds.isEmpty())
        assertEquals(DashboardConfigurationLint.IssueType.BROAD_SELECTOR, overLimit.issues.single().type)
        assertEquals(DashboardConfigurationLint.SELECTOR_ENTITY_LIMIT + 1, overLimit.issues.single().candidateCount)
    }

    @Test fun mapShowAllBlocksEvenWhenCurrentCatalogIsTiny() {
        val config = """{"views":[{"cards":[{
          "type":"map","show_all":true,"entities":["person.resident"]
        }]}]}"""

        val result = DashboardConfigurationLint.analyze(
            config, listOf("person.resident"), emptyMap(), registryMetadataComplete = true,
        )

        assertTrue(result.blocking)
        assertEquals(DashboardConfigurationLint.IssueType.UNBOUNDED_SELECTOR, result.issues.single().type)
        assertEquals(listOf("dashboard.views[0].cards[0]"), result.issues.single().sourceLocations)
    }

    @Test fun mapWithNonemptyGeoLocationSourcesBlocks() {
        val config = """{"views":[{"cards":[{
          "type":"map","show_all":false,"geo_location_sources":["nws"],
          "entities":["person.resident"]
        }]}]}"""

        val result = DashboardConfigurationLint.analyze(
            config, listOf("person.resident"), emptyMap(), registryMetadataComplete = true,
        )

        assertTrue(result.blocking)
        assertEquals(DashboardConfigurationLint.IssueType.UNBOUNDED_SELECTOR, result.issues.single().type)
    }

    @Test fun mapWithExplicitDeviceTrackerAndEmptyGeoLocationSourcesNeedsNoSelectorExpansion() {
        val config = """{"views":[{"cards":[{
          "type":"map","show_all":false,"geo_location_sources":[],
          "entities":["device_tracker.phone"]
        }]}]}"""

        val result = DashboardConfigurationLint.analyze(
            config,
            listOf("device_tracker.phone", "zone.home"),
            emptyMap(),
        )

        assertTrue(result.safeEntityIds.isEmpty())
        assertTrue(result.issues.isEmpty())
    }

    @Test fun mapWithPersonRetainsAllZonesNeededByFutureInZonesLocations() {
        val result = DashboardConfigurationLint.analyze(
            """{"views":[{"cards":[{
              "type":"map","entities":["person.resident"]
            }]}]}""",
            listOf("person.resident", "zone.home", "zone.work", "sensor.unrelated"),
            emptyMap(),
            registryMetadataComplete = true,
        )

        assertEquals(setOf("zone.home", "zone.work"), result.safeEntityIds)
        assertFalse(result.blocking)
    }

    @Test fun mapWithPersonBlocksWhenZoneRegistryProjectionIsIncomplete() {
        val result = DashboardConfigurationLint.analyze(
            """{"views":[{"cards":[{"type":"map","entities":["person.resident"]}]}]}""",
            listOf("person.resident", "zone.home"),
            emptyMap(),
            registryMetadataComplete = false,
        )

        assertTrue(result.safeEntityIds.isEmpty())
        assertTrue(result.blocking)
        assertEquals("Map person locations require complete zone registry metadata", result.issues.single().ruleSummary)
    }

    @Test fun autoEntitiesInjectsBoundedPeopleIntoChildMapAndIncludesStatelessZones() {
        val result = DashboardConfigurationLint.analyze(
            """{"views":[{"cards":[{
              "type":"custom:auto-entities",
              "filter":{"include":[{"entity_id":"person.resident"}]},
              "card":{"type":"map"}
            }]}]}""",
            listOf("person.resident", "zone.home"),
            mapOf("zone.work" to "{}"),
            registryMetadataComplete = true,
        )

        assertEquals(setOf("person.resident", "zone.home", "zone.work"), result.safeEntityIds)
        assertFalse(result.blocking)
    }

    @Test fun entityFilterPropagatesItsConfiguredPeopleIntoChildMap() {
        val result = DashboardConfigurationLint.analyze(
            """{"views":[{"cards":[{
              "type":"entity-filter","entities":["person.resident"],"card":{"type":"map"}
            }]}]}""",
            listOf("person.resident", "zone.home"),
            emptyMap(),
            registryMetadataComplete = true,
        )

        assertEquals(setOf("zone.home"), result.safeEntityIds)
        assertFalse(result.blocking)
    }

    @Test fun entityFilterCarriesOuterAutoEntitiesPeopleIntoChildMap() {
        val result = DashboardConfigurationLint.analyze(
            """{"views":[{"cards":[{
              "type":"custom:auto-entities",
              "filter":{"include":[{"entity_id":"person.first"}]},
              "card":{"type":"entity-filter","entities":["sensor.second"],"card":{"type":"map"}}
            }]}]}""",
            listOf("person.first", "sensor.second", "zone.home"),
            emptyMap(),
            registryMetadataComplete = true,
        )

        assertEquals(setOf("person.first", "zone.home"), result.safeEntityIds)
        assertFalse(result.blocking)
    }

    @Test fun autoEntitiesStaticOptionsEntityOverridePropagatesIntoChildMap() {
        val result = DashboardConfigurationLint.analyze(
            """{"views":[{"cards":[{
              "type":"custom:auto-entities",
              "filter":{"include":[{"entity_id":"sensor.source","options":{"entity":"person.override"}}]},
              "card":{"type":"map"}
            }]}]}""",
            listOf("sensor.source", "person.override", "zone.home"),
            emptyMap(),
            registryMetadataComplete = true,
        )

        assertEquals(setOf("sensor.source", "zone.home"), result.safeEntityIds)
        assertFalse(result.blocking)
    }

    @Test fun autoEntitiesNonEntityCardParameterDoesNotInjectRowsIntoMap() {
        val result = DashboardConfigurationLint.analyze(
            """{"views":[{"cards":[{
              "type":"custom:auto-entities","card_param":"cards",
              "filter":{"include":[{"entity_id":"person.resident"}]},
              "card":{"type":"map"}
            }]}]}""",
            listOf("person.resident", "zone.home"),
            emptyMap(),
            registryMetadataComplete = true,
        )

        assertEquals(setOf("person.resident"), result.safeEntityIds)
        assertFalse(result.blocking)
    }

    @Test fun rootAndViewStrategiesBothBlockStaticActivation() {
        val config = """{
          "strategy":{"type":"custom:root-strategy"},
          "views":[{
            "title":"Generated view","path":"generated",
            "strategy":{"type":"custom:view-strategy"}
          }]
        }"""

        val result = DashboardConfigurationLint.analyze(config, emptyList(), emptyMap())

        assertTrue(result.blocking)
        assertEquals(2, result.issues.size)
        assertTrue(result.issues.all { it.type == DashboardConfigurationLint.IssueType.UNBOUNDED_SELECTOR })
        assertEquals(
            listOf("dashboard.strategy", "dashboard.views[0].strategy"),
            result.issues.flatMap { it.sourceLocations }.sorted(),
        )
    }

    @Test fun autoEntitiesRegistrySelectorsMatchExactIdsAndDisplayNames() {
        val config = """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{"include":[
          {"area":"Living Room"},{"floor":"Upstairs"},{"label":"Needs Attention"}
        ]}}]}]}"""
        val catalog = listOf("light.living", "sensor.upstairs", "switch.attention", "sensor.other")
        val metadata = mapOf(
            "light.living" to """{"ai":"living_room","an":"Living Room"}""",
            "sensor.upstairs" to """{"fi":"upstairs","fn":"Upstairs"}""",
            "switch.attention" to """{"lb":["needs_attention"],"ln":["Needs Attention"]}""",
        )

        val result = DashboardConfigurationLint.analyze(
            config, catalog, metadata, registryMetadataComplete = true,
        )

        assertEquals(catalog.dropLast(1).toSet(), result.safeEntityIds)
        assertFalse(result.blocking)
    }

    @Test fun autoEntitiesRegistrySelectorsRetainMatchingEntitiesAbsentFromStateCatalog() {
        val result = DashboardConfigurationLint.analyze(
            """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{"include":[
              {"area":"study"}
            ]}}]}]}""",
            catalog = listOf("light.live"),
            metadataJson = mapOf(
                "light.live" to """{"ai":"study"}""",
                "switch.temporarily_stateless" to """{"ai":"study"}""",
            ),
            registryMetadataComplete = true,
        )

        assertEquals(setOf("light.live", "switch.temporarily_stateless"), result.safeEntityIds)
        assertFalse(result.blocking)
    }

    @Test fun autoEntitiesRegistrySelectorsBlockWhenRegistryProjectionIsIncomplete() {
        val config = """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{"include":[
          {"domain":"light","area":"Living Room"}
        ]}}]}]}"""

        val result = DashboardConfigurationLint.analyze(
            config,
            listOf("light.living", "light.kitchen"),
            mapOf("light.living" to """{"ai":"living_room","an":"Living Room"}"""),
            registryMetadataComplete = false,
        )

        assertTrue(result.safeEntityIds.isEmpty())
        assertTrue(result.blocking)
        assertEquals(DashboardConfigurationLint.IssueType.UNBOUNDED_SELECTOR, result.issues.single().type)
    }

    @Test fun autoEntitiesTypedIncludesAreNotMisreadAsSelectors() {
        val config = """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{"include":[
          {"type":"section","label":"Living Room"}
        ]}}]}]}"""

        val result = DashboardConfigurationLint.analyze(config, emptyList(), emptyMap())

        assertTrue(result.safeEntityIds.isEmpty())
        assertFalse(result.blocking)
    }

    @Test fun autoEntitiesStaticBuiltInTypedEntityRowRemainsProvable() {
        val config = """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{"include":[
          {"type":"entity","entity":"sensor.explicit"}
        ]}}]}]}"""

        val result = DashboardConfigurationLint.analyze(config, listOf("sensor.explicit"), emptyMap())

        assertTrue(result.safeEntityIds.isEmpty())
        assertFalse(result.blocking)
    }

    @Test fun autoEntitiesCustomOrDynamicTypedRowsBlockStaticActivation() {
        val config = """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{"include":[
          {"type":"custom:template-entity-row","entity":"sensor.explicit"},
          {"type":"entity","entity":"sensor.${'$'}{dynamic}"}
        ]}}]}]}"""

        val result = DashboardConfigurationLint.analyze(config, listOf("sensor.explicit"), emptyMap())

        assertTrue(result.blocking)
        assertEquals(2, result.issues.size)
        assertTrue(result.issues.all { it.ruleSummary.contains("typed row") })
        assertFalse(compatibilityText(result).contains("dynamic"))
    }

    @Test fun autoEntitiesCustomOrDynamicFilteredOptionsBlockStaticActivation() {
        val config = """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{"include":[
          {"entity_id":"sensor.first","options":{"type":"custom:template-entity-row"}},
          {"entity_id":"sensor.second","options":{"name":"${'$'}{dynamic}"}}
        ]}}]}]}"""

        val result = DashboardConfigurationLint.analyze(
            config, listOf("sensor.first", "sensor.second"), emptyMap(),
        )

        assertEquals(setOf("sensor.first", "sensor.second"), result.safeEntityIds)
        assertTrue(result.blocking)
        assertEquals(2, result.issues.size)
        assertTrue(result.issues.all { it.ruleSummary.contains("options") })
        assertFalse(compatibilityText(result).contains("dynamic"))
    }

    @Test fun autoEntitiesCustomOrDynamicSeedRowsBlockStaticActivation() {
        val config = """{"views":[{"cards":[{
          "type":"custom:auto-entities",
          "entities":[
            {"type":"custom:template-entity-row","entity":"sensor.first"},
            {"type":"entity","entity":"sensor.second","name":"${'$'}{dynamic}"}
          ],
          "filter":{"include":[{"entity_id":"sensor.third"}]},
          "card":{"type":"entities"}
        }]}]}"""

        val result = DashboardConfigurationLint.analyze(
            config, listOf("sensor.first", "sensor.second", "sensor.third"), emptyMap(),
        )

        assertEquals(setOf("sensor.third"), result.safeEntityIds)
        assertTrue(result.blocking)
        assertEquals(2, result.issues.size)
        assertTrue(result.issues.all { it.ruleSummary.contains("seed row") })
        assertFalse(compatibilityText(result).contains("dynamic"))
    }

    @Test fun registryRequirementsAreCapabilityScopedToDashboardStructure() {
        val plain = DashboardConfigurationLint.registryRequirements(
            """{"views":[{"cards":[{"type":"entities","entities":["sensor.room"]}]}]}""",
        )
        assertFalse(plain.any)

        val presentationRow = DashboardConfigurationLint.registryRequirements(
            """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{"include":[
              {"type":"section","label":"Presentation only"}
            ]}}]}]}""",
        )
        assertFalse(presentationRow.any)

        val map = DashboardConfigurationLint.registryRequirements(
            """{"views":[{"cards":[{"type":"map","entities":["person.resident"]}]}]}""",
        )
        assertEquals(
            DashboardConfigurationLint.RegistryRequirements(entities = true),
            map,
        )

        val area = DashboardConfigurationLint.registryRequirements(
            """{"views":[{"cards":[{"type":"area","area":"study"}]}]}""",
        )
        assertEquals(
            DashboardConfigurationLint.RegistryRequirements(entities = true, areas = true, devices = true),
            area,
        )

        val floorAndLabel = DashboardConfigurationLint.registryRequirements(
            """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{"include":[
              {"floor":"upper"},{"label":"important"},{"type":"section","label":"Presentation only"}
            ]}}]}]}""",
        )
        assertEquals(
            DashboardConfigurationLint.RegistryRequirements(
                entities = true, areas = true, devices = true, floors = true, labels = true,
            ),
            floorAndLabel,
        )

        val registryExclude = DashboardConfigurationLint.registryRequirements(
            """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{
              "include":[{"domain":"light"}],"exclude":[{"area":"utility"}]
            }}]}]}""",
        )
        assertEquals(
            DashboardConfigurationLint.RegistryRequirements(entities = true, areas = true, devices = true),
            registryExclude,
        )
    }

    @Test fun autoEntitiesEvalJsOptionsBlockActivationEvenWithBoundedCandidates() {
        val config = """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{"include":[
          {"domain":"sensor","options":{"eval_js":true,"entity":"${'$'}{state}"}}
        ]}}]}]}"""

        val result = DashboardConfigurationLint.analyze(config, listOf("sensor.source"), emptyMap())

        assertEquals(setOf("sensor.source"), result.safeEntityIds)
        assertTrue(result.blocking)
        assertEquals(DashboardConfigurationLint.IssueType.UNBOUNDED_SELECTOR, result.issues.single().type)
    }

    @Test fun upstreamWildcardDotSemanticsAreConservativeForAutoEntities() {
        val auto = DashboardConfigurationLint.analyze(
            """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{"include":[
              {"entity_id":"sensor.foo*"}
            ]}}]}]}""",
            listOf("sensor.foo_one", "sensorxfoo.two", "light.foo"),
            emptyMap(),
        )
        assertEquals(setOf("sensor.foo_one", "sensorxfoo.two"), auto.safeEntityIds)
        assertFalse(auto.blocking)

        val autoExclude = DashboardConfigurationLint.analyze(
            """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{
              "include":[{"entity_id":"*.*"}],"exclude":[{"entity_id":"sensor.foo*"}]
            }}]}]}""",
            listOf("sensor.foo_one", "sensorxfoo.two", "light.foo"),
            emptyMap(),
        )
        assertEquals(setOf("light.foo"), autoExclude.safeEntityIds)
        assertFalse(autoExclude.blocking)

    }

    @Test fun autoEntitiesFriendlyNameWildcardPreservesUpstreamDotSemantics() {
        val result = DashboardConfigurationLint.analyze(
            """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{"include":[
              {"name":"Kitchen.Temp*"}
            ]}}]}]}""",
            listOf("sensor.kitchen", "sensor.other"),
            emptyMap(),
            friendlyNames = mapOf(
                "sensor.kitchen" to "Kitchen Temp sensor",
                "sensor.other" to "Other sensor",
            ),
        )

        assertEquals(setOf("sensor.kitchen"), result.safeEntityIds)
        assertFalse(result.blocking)
    }

    @Test fun autoEntitiesQuestionMarkPatternIsRejectedInsteadOfInventingWildcardSemantics() {
        val result = DashboardConfigurationLint.analyze(
            """{"views":[{"cards":[{"type":"custom:auto-entities","filter":{"include":[
              {"entity_id":"sensor.foo?"}
            ]}}]}]}""",
            listOf("sensor.fooa"),
            emptyMap(),
        )

        assertTrue(result.safeEntityIds.isEmpty())
        assertTrue(result.blocking)
    }

    @Test fun knownTemplateCardsEmitNamedNoticesEvenWhenLiteralDependenciesHideGenericWarnings() {
        val config = """{"views":[{"title":"Templates","path":"templates","cards":[
          {"type":"custom:streamline-card","template":"room","entity":"sensor.streamline"},
          {"type":"custom:decluttering-card","template":"room","entity":"sensor.decluttering"},
          {"type":"custom:button-card","entity":"sensor.button","triggers_update":["sensor.watcher"]}
        ]}]}"""

        val scan = EntityLearningProtocol.scanDashboard(config)
        assertEquals(
            setOf("sensor.streamline", "sensor.decluttering", "sensor.button", "sensor.watcher"),
            scan.entityIds,
        )
        assertTrue(scan.unresolved.none { unresolved ->
            listOf("custom:streamline-card", "custom:decluttering-card", "custom:button-card")
                .any(unresolved::contains)
        })

        val result = DashboardConfigurationLint.analyze(config, scan.entityIds, emptyMap())
        val notices = result.issues.filter { it.type == DashboardConfigurationLint.IssueType.LIMITED_SUPPORT }

        assertEquals(1, notices.size)
        assertEquals(
            setOf("Button Card has limited entity discovery support"),
            notices.mapTo(mutableSetOf(), DashboardConfigurationLint.Issue::ruleSummary),
        )
        assertTrue(notices.all { !it.blocking && it.severity == "warning" && it.limit == null })
        assertTrue(notices.all { it.toJson().isNull("candidate_count") && it.toJson().isNull("limit") })
        assertEquals(1, result.issues.count { it.type == DashboardConfigurationLint.IssueType.COMPATIBILITY_GAP })
        assertEquals(1, result.issues.count { it.type == DashboardConfigurationLint.IssueType.RUNTIME_COVERAGE })
        assertTrue(result.issues.all { !it.blocking && it.severity == "warning" })
        assertFalse(result.blocking)
    }

    @Test fun streamlineEmitsOneDashboardWideRuntimeCoverageNotice() {
        val config = """{"views":[
          {"title":"Main","path":"main","cards":[
            {"type":"custom:streamline-card","template":"room","entity":"sensor.first"},
            {"type":"custom:streamline-card","template":"room","entity":"sensor.second"}
          ]},
          {"title":"Popup","path":"popup","cards":[
            {"type":"custom:streamline-card","template":"details","entity":"sensor.third"}
          ]}
        ]}"""

        val result = DashboardConfigurationLint.analyze(
            config,
            setOf("sensor.first", "sensor.second", "sensor.third"),
            emptyMap(),
        )
        val notice = result.issues.single()

        assertEquals(DashboardConfigurationLint.IssueType.RUNTIME_COVERAGE, notice.type)
        assertFalse(notice.blocking)
        assertFalse(notice.ignorable)
        assertEquals("Dashboard", notice.viewTitle)
        assertEquals("dashboard", notice.viewPath)
        assertEquals("Streamline entity discovery depends on dashboard coverage", notice.ruleSummary)
        assertTrue(notice.reason.contains("direct hass.states dependencies are learned automatically"))
        assertTrue(notice.reason.contains("may remain incomplete"))
        assertTrue(notice.recommendation.isEmpty())
        assertEquals(
            listOf(
                "dashboard.views[0].cards[0]",
                "dashboard.views[0].cards[1]",
                "dashboard.views[1].cards[0]",
            ),
            notice.sourceLocations,
        )
    }

    @Test fun bubbleCardEmitsOneDashboardWideRuntimeCoverageNotice() {
        val result = DashboardConfigurationLint.analyze(
            """{"views":[
              {"path":"main","cards":[
                {"type":"custom:bubble-card","entity":"light.one"},
                {"type":"custom:bubble-card","entity":"light.two"}
              ]},
              {"path":"popup","cards":[
                {"type":"custom:bubble-card","entity":"light.three"}
              ]}
            ]}""",
            listOf("light.one", "light.two", "light.three"),
            emptyMap(),
        )

        assertFalse(result.blocking)
        val notice = result.issues.single()
        assertEquals(DashboardConfigurationLint.IssueType.RUNTIME_COVERAGE, notice.type)
        assertFalse(notice.ignorable)
        assertEquals("Dashboard", notice.viewTitle)
        assertEquals("dashboard", notice.viewPath)
        assertEquals("Bubble Card entity discovery depends on dashboard coverage", notice.ruleSummary)
        assertTrue(notice.reason.contains("learned automatically"))
        assertTrue(notice.reason.contains("may remain incomplete"))
        assertTrue(notice.recommendation.isEmpty())
        assertEquals(
            listOf(
                "dashboard.views[0].cards[0]",
                "dashboard.views[0].cards[1]",
                "dashboard.views[1].cards[0]",
            ),
            notice.sourceLocations,
        )
    }

    @Test fun bubbleCardInlineLiteralsRemainStaticallyDetected() {
        val config = """{"views":[{"cards":[{
              "type":"custom:bubble-card","entity":"light.main",
              "styles":"${'$'}{hass.states['sensor.hidden'].state}",
              "modules":["room-accent"]
            }]}]}"""
        val scan = EntityLearningProtocol.scanDashboard(config)
        val result = DashboardConfigurationLint.analyze(config, scan.entityIds, emptyMap())

        assertFalse(result.blocking)
        assertEquals(setOf("light.main", "sensor.hidden"), scan.entityIds)
        val gap = result.issues.single { it.type == DashboardConfigurationLint.IssueType.RUNTIME_COVERAGE }
        assertEquals("Bubble Card entity discovery depends on dashboard coverage", gap.ruleSummary)
    }

    @Test fun kioskModeStaticAndServerTemplatesNeedNoCompatibilityIssue() {
        val result = DashboardConfigurationLint.analyze(
            """{"kiosk_mode":{
              "hide_header":true,
              "hide_sidebar":"{{ is_state('input_boolean.server_toggle', 'on') }}"
            },"views":[]}""",
            listOf("input_boolean.server_toggle"),
            emptyMap(),
        )

        assertFalse(result.blocking)
        assertTrue(result.issues.isEmpty())
    }

    @Test fun kioskModeClientJavascriptIsAdvisoryEvenForLiteralDependencies() {
        val config = """{"kiosk_mode":{
          "hide_header":"[[[ return is_state('input_boolean.panel_mode', 'on') && states(\"sensor.temperature\") > 20; ]]]",
          "hide_sidebar":"[[[ return !user_is_admin; ]]]"
        },"views":[]}"""
        val scan = EntityLearningProtocol.scanDashboard(config)
        val result = DashboardConfigurationLint.analyze(config, scan.entityIds, emptyMap())

        assertEquals(setOf("input_boolean.panel_mode", "sensor.temperature"), scan.entityIds)
        assertFalse(result.blocking)
        val limited = result.issues.single { it.type == DashboardConfigurationLint.IssueType.LIMITED_SUPPORT }
        assertTrue(limited.ruleSummary.contains("HACS Kiosk Mode"))
        val gap = result.issues.single { it.type == DashboardConfigurationLint.IssueType.COMPATIBILITY_GAP }
        assertEquals("HACS Kiosk Mode configuration", gap.cardTitle)
        assertTrue(gap.ruleSummary.contains("unknown entity dependencies"))
        assertTrue(gap.recommendation.contains("automatic entity filtering"))
        assertTrue(gap.recommendation.contains("native kiosk command"))
    }

    @Test fun kioskModeComputedAndEnumeratedClientDependenciesAreAdvisory() {
        val result = DashboardConfigurationLint.analyze(
            """{"kiosk_mode":{
              "hide_header":"[[[ const id = refs.entity_id; return states(id) === 'on'; ]]]",
              "hide_sidebar":"[[[ return Object.values(states.sensor).some((entity) => entity.state === 'on'); ]]]"
            },"views":[]}""",
            emptyList(),
            emptyMap(),
        )

        assertFalse(result.blocking)
        assertEquals(1, result.issues.count { it.type == DashboardConfigurationLint.IssueType.LIMITED_SUPPORT })
        val gap = result.issues.single { it.type == DashboardConfigurationLint.IssueType.COMPATIBILITY_GAP }
        assertTrue(gap.ruleSummary.contains("HACS Kiosk Mode"))
        assertTrue(gap.ruleSummary.contains("unknown entity dependencies"))
    }

    @Test fun kioskModeJavaScriptAliasesRemainVisibleButNonblocking() {
        val result = DashboardConfigurationLint.analyze(
            """{"kiosk_mode":{"hide_header":"[[[ const read = states; return read(refs.entity_id); ]]]"},"views":[]}""",
            emptyList(), emptyMap(),
        )
        assertFalse(result.blocking)
        assertTrue(result.issues.any { it.ruleSummary.contains("HACS Kiosk Mode") })
    }

    @Test fun opaqueCardApprovalFingerprintChangesWithDashboardDependencies() {
        fun analyze(config: String) = DashboardConfigurationLint.analyze(config, emptyList(), emptyMap())
        fun DashboardConfigurationLint.Result.gap() = issues.single {
            it.type == DashboardConfigurationLint.IssueType.COMPATIBILITY_GAP
        }

        val beforeConfig =
            """{"decluttering_templates":{"room":{"card":{"entity":"sensor.before"}}},"views":[{"cards":[{"type":"custom:decluttering-card","template":"room"}]}]}"""
        val beforeResult = analyze(beforeConfig)
        val before = beforeResult.gap()
        val after = analyze(
            """{"decluttering_templates":{"room":{"card":{"entity":"sensor.after"}}},"views":[{"cards":[{"type":"custom:decluttering-card","template":"room"}]}]}""",
        ).gap()
        val reordered = analyze(
            """{"views":[{"cards":[{"template":"room","type":"custom:decluttering-card"}]}],"decluttering_templates":{"room":{"card":{"entity":"sensor.before"}}}}""",
        ).gap()

        assertNotEquals(before.fingerprint, after.fingerprint)
        assertEquals(before.fingerprint, reordered.fingerprint)
        assertEquals(
            EntityLearningProtocol.hash(EntityLearningProtocol.canonical(JSONObject(beforeConfig))),
            beforeResult.dashboardRevision,
        )
        val effective = EntityCatalogIssuePersistence.applyIgnores(
            JSONArray().put(after.toJson()),
            setOf(before.fingerprint),
        )
        assertFalse(JSONArray(effective).getJSONObject(0).getBoolean("blocking"))
    }

    @Test fun repeatedLimitedSupportCardsAreGroupedByIntegrationAndView() {
        val result = DashboardConfigurationLint.analyze(
            """{"views":[
              {"path":"first","cards":[
                {"type":"custom:button-card","entity":"sensor.one"},
                {"type":"custom:button-card","entity":"sensor.two"}
              ]},
              {"path":"second","cards":[{"type":"custom:button-card","entity":"sensor.three"}]}
            ]}""",
            listOf("sensor.one", "sensor.two", "sensor.three"),
            emptyMap(),
        )

        assertFalse(result.blocking)
        assertEquals(2, result.issues.size)
        assertTrue(result.issues.all { it.type == DashboardConfigurationLint.IssueType.LIMITED_SUPPORT })
        assertEquals(
            listOf("dashboard.views[0].cards[0]", "dashboard.views[0].cards[1]"),
            result.issues.single { it.viewPath == "first" }.sourceLocations,
        )
        assertEquals(
            listOf("dashboard.views[1].cards[0]"),
            result.issues.single { it.viewPath == "second" }.sourceLocations,
        )
    }

    @Test fun compatibilityDiagnosticsAreBoundedBeforePersistenceWithoutCreatingBlockers() {
        val repeatedCards = (0 until 20).joinToString(",") { index ->
            """{"type":"custom:button-card","entity":"sensor.$index"}"""
        }
        val repeated = DashboardConfigurationLint.analyze(
            """{"views":[{"path":"repeated","cards":[$repeatedCards]}]}""",
            (0 until 20).map { "sensor.$it" },
            emptyMap(),
        )
        assertEquals(
            EntityCatalogIssuePersistence.MAX_SOURCES_PER_GROUP,
            repeated.issues.single().sourceLocations.size,
        )

        val views = (0 until 70).joinToString(",") { index ->
            val opaque = if (index == 69) ",{\"type\":\"custom:decluttering-card\",\"template\":\"room\"}" else ""
            """{"path":"view-$index","cards":[{"type":"custom:button-card","entity":"sensor.$index"}$opaque]}"""
        }
        val saturated = DashboardConfigurationLint.analyze(
            """{"views":[$views]}""",
            (0 until 70).map { "sensor.$it" },
            emptyMap(),
        )
        assertEquals(
            EntityCatalogIssuePersistence.MAX_ISSUE_GROUPS,
            saturated.issues.count { it.type == DashboardConfigurationLint.IssueType.LIMITED_SUPPORT },
        )
        assertFalse(saturated.issues.any {
            it.type == DashboardConfigurationLint.IssueType.COMPATIBILITY_GAP
        })
        assertFalse(saturated.blocking)
    }

    @Test fun everyTemplateSelectorGroupSurvivesTheBoundedIssuePayload() {
        // The largest shape the diagnostics can carry: MAX_ISSUE_GROUPS views, each saturating
        // MAX_SOURCES_PER_GROUP with templated auto-entities cards behind long owned paths.
        // Per-issue copy that pushes this past MAX_PAYLOAD_BYTES does not truncate a string, it drops
        // whole issues from the tail — and the blocking count is derived from the bounded payload, so
        // a dropped check would also stop blocking automatic activation. This is the size budget for
        // the template-specific reason and recommendation.
        val views = (0 until EntityCatalogIssuePersistence.MAX_ISSUE_GROUPS).joinToString(",") { view ->
            val cards = (0 until EntityCatalogIssuePersistence.MAX_SOURCES_PER_GROUP).joinToString(",") { card ->
                """{"nested_key_$card":{"type":"custom:auto-entities","filter":{"template":"{{ states('sensor.hourly_tick') }}"}}}"""
            }
            """{"path":"a-deliberately-long-dashboard-view-path-$view","cards":[$cards]}"""
        }
        val result = DashboardConfigurationLint.analyze("""{"views":[$views]}""", emptyList(), emptyMap())
        val templateIssues = result.issues.filter {
            it.type == DashboardConfigurationLint.IssueType.UNBOUNDED_SELECTOR
        }
        assertEquals(EntityCatalogIssuePersistence.MAX_ISSUE_GROUPS, templateIssues.size)
        assertEquals(
            EntityCatalogIssuePersistence.MAX_SOURCES_PER_GROUP,
            templateIssues.first().sourceLocations.size,
        )

        val bounded = JSONArray(
            EntityCatalogIssuePersistence.boundedJson(
                result.issues.map(DashboardConfigurationLint.Issue::toJson).onEach {
                    // Persistence admits this marker on every issue even though the current typed
                    // producer can emit it only for the two HACS Kiosk findings.
                    it.put("card_title_hacs_kiosk", true)
                },
            ),
        )

        val bytes = bounded.toString().toByteArray(Charsets.UTF_8).size
        assertEquals(
            "the bounded diagnostics payload dropped issues at $bytes bytes; shorten the per-issue copy",
            result.issues.size,
            bounded.length(),
        )
        assertTrue("regression fixture must exercise the 64 KiB boundary at $bytes bytes", bytes > 64 * 1024)
        assertTrue("regression fixture must kill the 68 KiB cap mutant at $bytes bytes", bytes > 68 * 1024)
        assertTrue("payload is $bytes bytes", bytes <= EntityCatalogIssuePersistence.MAX_PAYLOAD_BYTES)
        assertTrue((0 until bounded.length()).all {
            val issue = bounded.getJSONObject(it)
            issue.getString("presentation_code") == "template-selector" &&
                !issue.has("presentation_params") && issue.has("view_title_index") &&
                issue.getBoolean("card_title_hacs_kiosk")
        })

        val effective = JSONArray(
            EntityCatalogIssuePersistence.applyIgnores(
                bounded,
                result.issues.mapTo(mutableSetOf(), DashboardConfigurationLint.Issue::fingerprint),
            ),
        )
        val effectiveBytes = effective.toString().toByteArray(Charsets.UTF_8).size
        assertEquals(EntityCatalogIssuePersistence.MAX_ISSUE_GROUPS, effective.length())
        assertTrue("effective payload must still exercise the old cap at $effectiveBytes bytes", effectiveBytes > 64 * 1024)
        assertTrue("effective payload must kill the 68 KiB cap mutant at $effectiveBytes bytes", effectiveBytes > 68 * 1024)
        assertTrue("effective payload is $effectiveBytes bytes", effectiveBytes <= EntityCatalogIssuePersistence.MAX_PAYLOAD_BYTES)
        assertTrue((0 until effective.length()).all {
            val issue = effective.getJSONObject(it)
            issue.getBoolean("ignored") && !issue.getBoolean("blocking") && issue.getBoolean("would_block")
        })
    }

    @Test fun blockingDiagnosticOverflowCannotBeIgnored() {
        val views = (0..EntityCatalogIssuePersistence.MAX_ISSUE_GROUPS).joinToString(",") { index ->
            """{"path":"blocked-$index","cards":[{"type":"custom:auto-entities","filter":{"template":"template-$index"}}]}"""
        }
        val result = DashboardConfigurationLint.analyze("""{"views":[$views]}""", emptyList(), emptyMap())
        val overflow = result.issues.single {
            it.type == DashboardConfigurationLint.IssueType.DIAGNOSTIC_LIMIT
        }
        val ordinary = result.issues.filter {
            it.type == DashboardConfigurationLint.IssueType.UNBOUNDED_SELECTOR
        }

        assertEquals(EntityCatalogIssuePersistence.MAX_ISSUE_GROUPS, ordinary.size)
        assertTrue(overflow.blocking)
        assertFalse(overflow.ignorable)
        val effective = EntityCatalogIssuePersistence.applyIgnores(
            JSONArray(result.issues.map(DashboardConfigurationLint.Issue::toJson)),
            ordinary.mapTo(mutableSetOf(), DashboardConfigurationLint.Issue::fingerprint),
        )
        val retained = JSONArray(effective)
        val retainedOverflow = (0 until retained.length()).map(retained::getJSONObject).single {
            it.optString("type") == DashboardConfigurationLint.IssueType.DIAGNOSTIC_LIMIT.wireName
        }
        assertTrue(retainedOverflow.getBoolean("blocking"))
        assertFalse(retainedOverflow.getBoolean("ignored"))
        assertFalse(EntityCatalogIssuePersistence.canIgnore(retainedOverflow))
    }

    @Test fun buttonCardDynamicFeaturesWarnSeparatelyFromNamedLimitedSupportNotice() {
        val risky = DashboardConfigurationLint.analyze(
            """{"views":[{"cards":[{
              "type":"custom:button-card","entity":"sensor.primary","template":"shared",
              "triggers_update":"all","group_expand":true,
              "styles":{"name":"[[[ return states['sensor.hidden'].state; ]]]"}
            }]}]}""",
            listOf("sensor.primary", "sensor.hidden"),
            emptyMap(),
        )

        assertFalse(risky.blocking)
        assertEquals(2, risky.issues.size)
        assertEquals(1, risky.issues.count { it.type == DashboardConfigurationLint.IssueType.LIMITED_SUPPORT })
        val gap = risky.issues.single { it.type == DashboardConfigurationLint.IssueType.COMPATIBILITY_GAP }
        assertTrue(gap.ruleSummary.contains("config template inheritance"))
        assertTrue(gap.ruleSummary.contains("JavaScript templates"))
        assertTrue(gap.ruleSummary.contains("group expansion"))
        assertTrue(gap.ruleSummary.contains("triggers_update: all"))

        val explicit = DashboardConfigurationLint.analyze(
            """{"views":[{"cards":[{
              "type":"custom:button-card","entity":"sensor.primary",
              "triggers_update":["sensor.watcher"]
            }]}]}""",
            listOf("sensor.primary", "sensor.watcher"),
            emptyMap(),
        )
        assertFalse(explicit.blocking)
        assertEquals(DashboardConfigurationLint.IssueType.LIMITED_SUPPORT, explicit.issues.single().type)

        val malformedUppercase = DashboardConfigurationLint.analyze(
            """{"views":[{"cards":[{
              "type":"custom:button-card","entity":"sensor.primary","triggers_update":"ALL"
            }]}]}""",
            listOf("sensor.primary"),
            emptyMap(),
        )
        assertFalse(malformedUppercase.blocking)
    }

    @Test fun nestedButtonCardJavaScriptIsAttributedOnlyToTheNestedCard() {
        val result = DashboardConfigurationLint.analyze(
            """{"views":[{"cards":[{
              "type":"custom:button-card","entity":"sensor.outer","custom_fields":{"nested":{"card":{
                "type":"custom:button-card","entity":"sensor.inner",
                "name":"[[[[ return states['sensor.hidden'].state; ]]]]"
              }}}
            }]}]}""",
            listOf("sensor.outer", "sensor.inner", "sensor.hidden"),
            emptyMap(),
        )

        assertFalse(result.blocking)
        assertEquals(2, result.issues.size)
        val notice = result.issues.single { it.type == DashboardConfigurationLint.IssueType.LIMITED_SUPPORT }
        assertEquals(2, notice.sourceLocations.size)
        val gap = result.issues.single { it.type == DashboardConfigurationLint.IssueType.COMPATIBILITY_GAP }
        assertEquals(listOf("dashboard.views[0].cards[0].custom_fields.nested.card"), gap.sourceLocations)
        assertTrue(gap.limit == null && gap.toJson().isNull("candidate_count") && gap.toJson().isNull("limit"))
    }

    @Test fun typedButtonCardVariableDoesNotHideForceEvaluatedJavaScript() {
        val result = DashboardConfigurationLint.analyze(
            """{"views":[{"cards":[{
              "type":"custom:button-card","entity":"sensor.primary","variables":{"bootstrap":{
                "type":"metadata","force_eval":true,
                "value":"[[[ return states[window.dynamicEntity].state; ]]]"
              }}
            }]}]}""",
            listOf("sensor.primary"),
            emptyMap(),
        )

        assertFalse(result.blocking)
        assertEquals(1, result.issues.count { it.type == DashboardConfigurationLint.IssueType.LIMITED_SUPPORT })
        assertEquals(1, result.issues.count { it.type == DashboardConfigurationLint.IssueType.COMPATIBILITY_GAP })
    }

    @Test fun arbitraryButtonCardJavaScriptRemainsVisibleButNonblocking() {
        val result = DashboardConfigurationLint.analyze(
            """{"views":[{"cards":[{
              "type":"custom:button-card","entity":"sensor.primary",
              "name":"[[[ return entity.state; ]]]"
            }]}]}""",
            listOf("sensor.primary"),
            emptyMap(),
        )

        // A JavaScript program is not a bounded dependency declaration. Recognizing a few apparently
        // harmless expressions would create an open-ended parser contract and unsafe false negatives.
        assertFalse(result.blocking)
        assertEquals(1, result.issues.count { it.type == DashboardConfigurationLint.IssueType.LIMITED_SUPPORT })
        assertEquals(1, result.issues.count { it.type == DashboardConfigurationLint.IssueType.COMPATIBILITY_GAP })
    }

    @Test fun broadRepeatedRulesAreGroupedPerViewWithEverySourceLocation() {
        fun card(domain: String) = """{"type":"custom:auto-entities","filter":{"include":[{"domain":"$domain"}]}}"""
        val cards = listOf(
            card("light"), card("light"),
            card("binary_sensor"), card("binary_sensor"), card("binary_sensor"), card("binary_sensor"), card("binary_sensor"),
            card("sensor"), card("sensor"), card("sensor"), card("sensor"),
            card("switch"),
        ).joinToString(",")
        val config = """{"views":[{"title":"Overview","path":"overview","cards":[$cards]}]}"""
        val catalog = entities("light", 72) + entities("binary_sensor", 537) +
            entities("sensor", 1_600) + entities("switch", 263)

        val result = DashboardConfigurationLint.analyze(config, catalog, emptyMap())

        assertTrue(result.safeEntityIds.isEmpty())
        assertEquals(4, result.issues.size)
        assertEquals(2_472, result.issues.sumOf { it.candidateCount ?: 0 })
        assertEquals(listOf(2, 5, 4, 1), listOf("light", "binary_sensor", "sensor", "switch").map { domain ->
            result.issues.single { it.ruleSummary == "domain $domain" }.sourceLocations.size
        })
        assertTrue(result.issues.all { it.blocking && it.viewPath == "overview" })
    }

    @Test fun selectorUnionOverBudgetIsBlockingWithoutDiscardingBoundedEvidence() {
        val config = """{"views":[{"cards":[
          {"type":"custom:auto-entities","filter":{"include":[{"domain":"sensor"}]}},
          {"type":"custom:auto-entities","filter":{"include":[{"domain":"light"}]}},
          {"type":"custom:auto-entities","filter":{"include":[{"domain":"switch"}]}}
        ]}]}"""
        val catalog = entities("sensor", 50) + entities("light", 50) + entities("switch", 50)

        val result = DashboardConfigurationLint.analyze(config, catalog, emptyMap())

        assertEquals(150, result.safeEntityIds.size)
        val issue = result.issues.single()
        assertEquals(DashboardConfigurationLint.IssueType.SELECTOR_BUDGET, issue.type)
        assertEquals(150, issue.candidateCount)
        assertEquals(3, issue.sourceLocations.size)
    }

    @Test fun issueJsonUsesStableUiContractAndFingerprint() {
        val config = """{"views":[{"title":"Overview","path":"overview","cards":[
          {"type":"custom:auto-entities","title":"Sensors","filter":{"include":[{"domain":"sensor"}]}}
        ]}]}"""
        val catalog = entities("sensor", 65)
        val first = DashboardConfigurationLint.analyze(config, catalog, emptyMap()).issues.single()
        val second = DashboardConfigurationLint.analyze(config, catalog.reversed(), emptyMap()).issues.single()
        val json = first.toJson()

        assertEquals(first.fingerprint, second.fingerprint)
        assertEquals("error", json.getString("severity"))
        assertEquals("broad_selector", json.getString("type"))
        assertEquals("Overview", json.getString("view_title"))
        assertEquals("overview", json.getString("view_path"))
        assertEquals("Sensors", json.getString("card_title"))
        assertEquals(65, json.getInt("candidate_count"))
        assertTrue(json.getJSONArray("source_locations").getString(0).endsWith("cards[0]"))
    }

    private fun entities(domain: String, count: Int) = (1..count).map { "$domain.sample_$it" }

    private fun compatibilityText(result: DashboardConfigurationLint.Result): String =
        result.issues.flatMap { issue ->
            listOfNotNull(
                issue.viewTitle,
                issue.cardTitle,
                issue.ruleSummary,
                issue.reason,
                issue.recommendation,
            ) + issue.sourceLocations
        }.joinToString("\n")
}
