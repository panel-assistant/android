package io.github.maxlyth.hapaneld.http

import io.github.maxlyth.hapaneld.mqttBrokerSuggestionFromHaUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConfigDiscoverySuggestionContractTest {
    @Test fun mqttBrokerSuggestionsPreferHomeAssistantHostnameWhenAvailable() {
        assertEquals("tcp://homeassistant.local:1883", mqttBrokerSuggestionFromHaUrl("https://homeassistant.local:8123"))
        assertEquals("tcp://[fd00::1234]:1883", mqttBrokerSuggestionFromHaUrl("http://[fd00::1234]:8123"))
        assertNull(mqttBrokerSuggestionFromHaUrl(""))
        assertNull(mqttBrokerSuggestionFromHaUrl("not a url"))
    }
}
