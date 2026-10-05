package io.panelassistant.android.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardConfigurationLintPresentationTest {
    @Test fun everyTypedPresentationCodeIsEmittedByItsSemanticProducer() {
        val observed = linkedSetOf<DashboardConfigurationLint.PresentationCode>()

        fun observe(
            config: String,
            catalog: Collection<String> = emptyList(),
            metadata: Map<String, String> = emptyMap(),
            registryComplete: Boolean = false,
        ) {
            observed += DashboardConfigurationLint.analyze(
                config,
                catalog,
                metadata,
                registryMetadataComplete = registryComplete,
            ).issues.map { it.presentationCode }
        }

        observe(autoEntities(""""filter":{"template":"template"}"""))
        observe(autoEntities(""""entities":[{"type":"custom:row"}],"filter":{"include":[{"entity_id":"sensor.safe"}]},"card":{"type":"entities"}"""), listOf("sensor.safe"))
        observe(autoEntities(""""filter":{"include":[{"type":"custom:row"}]}"""))
        observe(autoEntities(""""filter":{"include":[{"entity_id":"sensor.safe","options":{"type":"custom:row"}}]}"""), listOf("sensor.safe"))
        observe(autoEntities(""""filter":{"include":[{"entity_id":"sensor.safe","options":{"eval_js":true}}]}"""), listOf("sensor.safe"))
        observe(autoEntities(""""filter":{"exclude":[{"entity_id":"sensor.hidden"}]}"""))
        observe(autoEntities(""""filter":{"include":[{}]}"""))
        observe(autoEntities(""""filter":{"include":[{"area":"study"}]}"""))
        observe(autoEntities(""""filter":{"include":[{"domain":"sensor"}]}"""), entities("sensor", 65))
        observe(card("""{"type":"area"}"""))
        observe(card("""{"type":"area","area":"study"}"""))
        val areaEntities = entities("sensor", 65)
        observe(
            card("""{"type":"area","area":"study"}"""),
            areaEntities,
            areaEntities.associateWith { """{"ai":"study"}""" },
            registryComplete = true,
        )
        observe(card("""{"type":"map","show_all":true}"""), registryComplete = true)
        observe(card("""{"type":"map","entities":["person.owner"]}"""), listOf("person.owner", "zone.home"))
        observe(
            card("""{"type":"map","entities":["person.owner"]}"""),
            listOf("person.owner") + entities("zone", 65),
            registryComplete = true,
        )
        observe("""{"strategy":{"type":"custom:test"}}""")
        observe(
            """{"views":[{"cards":[
              ${autoEntitiesCard("sensor")},${autoEntitiesCard("light")},${autoEntitiesCard("switch")}
            ]}]}""",
            entities("sensor", 50) + entities("light", 50) + entities("switch", 50),
        )
        observe(card("""{"type":"custom:streamline-card"}"""))
        observe(card("""{"type":"custom:decluttering-card"}"""))
        observe(card("""{"type":"custom:button-card","template":"shared"}"""))
        observe(card("""{"type":"custom:bubble-card"}"""))
        observe("""{"kiosk_mode":{"hide_header":"[[[ return states[id]; ]]]"}}""")

        val overflowingViews = (0..EntityCatalogIssuePersistence.MAX_ISSUE_GROUPS).joinToString(",") { index ->
            """{"path":"blocked-$index","cards":[${autoEntitiesCardWithTemplate("template-$index")}]}"""
        }
        observe("""{"views":[$overflowingViews]}""")

        val expectedWireNames = setOf(
            "diagnostic-limit",
            "template-selector",
            "auto-entities-seed-row-dynamic",
            "auto-entities-typed-row-dynamic",
            "auto-entities-options-dynamic",
            "auto-entities-options-javascript",
            "selector-missing-include",
            "selector-unbounded-or-dynamic",
            "selector-registry-incomplete",
            "selector-broad",
            "area-missing-id",
            "area-registry-incomplete",
            "area-broad",
            "map-dynamic-enumeration",
            "map-zone-registry-incomplete",
            "map-zones-broad",
            "streamline-runtime-coverage",
            "decluttering-template-expansion",
            "button-card-limited-support",
            "button-card-dynamic-features",
            "bubble-card-runtime-coverage",
            "kiosk_mode-limited-support",
            "kiosk_mode-dynamic-javascript",
            "dashboard-strategy",
            "selector-total-budget",
        )
        assertEquals(expectedWireNames, DashboardConfigurationLint.PresentationCode.entries.map { it.wireName }.toSet())
        assertEquals(DashboardConfigurationLint.PresentationCode.entries.toSet(), observed)
        assertEquals(25, observed.size)
    }

    @Test fun issueJsonAddsOnlyTheBoundedCodeWithoutChangingCompatibilityIdentity() {
        assertEquals(5, DashboardConfigurationLint.ANALYZER_POLICY_VERSION)
        val config = autoEntities(""""filter":{"include":[{"domain":"sensor"}]}""")
        val forward = DashboardConfigurationLint.analyze(config, entities("sensor", 65), emptyMap()).issues.single()
        val reversed = DashboardConfigurationLint.analyze(config, entities("sensor", 65).reversed(), emptyMap()).issues.single()
        val json = forward.toJson()

        assertEquals(forward.fingerprint, reversed.fingerprint)
        assertEquals("b59f08e4163289bc", forward.fingerprint)
        assertEquals(DashboardConfigurationLint.PresentationCode.SELECTOR_BROAD, forward.presentationCode)
        assertTrue(json.has("presentation_code"))
        assertEquals("selector-broad", json.getString("presentation_code"))
        assertFalse(json.has("presentation_params"))
        assertTrue(json.has("view_title_index"))
        assertEquals(1, json.getInt("view_title_index"))
        assertFalse(json.has("card_title_hacs_kiosk"))
        assertEquals(forward.ruleSummary, json.getString("rule_summary"))
        assertEquals(forward.reason, json.getString("reason"))
        assertEquals(forward.recommendation, json.getString("recommendation"))
        assertTrue(DashboardConfigurationLint.PresentationCode.entries.all {
            it.wireName.length in 1..EntityCatalogIssuePersistence.MAX_PRESENTATION_CODE_BYTES &&
                it.wireName.all { character -> character.code in 0x21..0x7e }
        })
    }

    @Test fun synthesizedTitleMarkersComeOnlyFromTypedProducerSites() {
        val userTitle = DashboardConfigurationLint.analyze(
            """{"views":[{"title":"View 1","cards":[${autoEntitiesCard("sensor")}]}]}""",
            entities("sensor", 65),
            emptyMap(),
        ).issues.single().toJson()
        assertEquals("View 1", userTitle.getString("view_title"))
        assertFalse(userTitle.has("view_title_index"))

        val kiosk = DashboardConfigurationLint.analyze(
            """{"kiosk_mode":{"hide_header":"[[[ return states[id]; ]]]"}}""",
            emptyList(),
            emptyMap(),
        ).issues.map { it.toJson() }
        assertEquals(2, kiosk.size)
        assertTrue(kiosk.all { it.has("card_title_hacs_kiosk") })
        assertTrue(kiosk.all { it.getBoolean("card_title_hacs_kiosk") })

        val userCard = DashboardConfigurationLint.analyze(
            """{"views":[{"cards":[{
              "type":"custom:button-card","title":"HACS Kiosk Mode configuration"
            }]}]}""",
            emptyList(),
            emptyMap(),
        ).issues.single().toJson()
        assertEquals("HACS Kiosk Mode configuration", userCard.getString("card_title"))
        assertFalse(userCard.has("card_title_hacs_kiosk"))
    }

    private fun card(card: String): String = """{"views":[{"cards":[$card]}]}"""

    private fun autoEntities(body: String): String = card("""{"type":"custom:auto-entities",$body}""")

    private fun autoEntitiesCard(domain: String): String =
        """{"type":"custom:auto-entities","filter":{"include":[{"domain":"$domain"}]}}"""

    private fun autoEntitiesCardWithTemplate(template: String): String =
        """{"type":"custom:auto-entities","filter":{"template":"$template"}}"""

    private fun entities(domain: String, count: Int) = (1..count).map { "$domain.sample_$it" }
}
