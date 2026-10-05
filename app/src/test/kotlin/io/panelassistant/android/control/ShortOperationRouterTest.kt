package io.panelassistant.android.control

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShortOperationRouterTest {
    @Test fun effectStopsAtFirstAcceptedResult() {
        val calls = mutableListOf<String>()
        val route = ShortOperationRouter.effect(
            EffectAttempt(PrivilegeRoute.DAEMON) { calls += "daemon"; true },
            EffectAttempt(PrivilegeRoute.SU) { calls += "su"; true },
        )
        assertEquals(PrivilegeRoute.DAEMON, route)
        assertEquals(listOf("daemon"), calls)
    }

    @Test fun effectFallsThroughFailuresInOrder() {
        val calls = mutableListOf<String>()
        val route = ShortOperationRouter.effect(
            EffectAttempt(PrivilegeRoute.DAEMON) { calls += "daemon"; false },
            EffectAttempt(PrivilegeRoute.SU) { calls += "su"; true },
        )
        assertEquals(PrivilegeRoute.SU, route)
        assertEquals(listOf("daemon", "su"), calls)
    }

    @Test fun effectReturnsNullWhenEveryRouteFails() {
        assertNull(
            ShortOperationRouter.effect(
                EffectAttempt(PrivilegeRoute.DAEMON) { false },
                EffectAttempt(PrivilegeRoute.SU) { false },
            )
        )
    }

    @Test fun valueFallsThroughRejectedRepliesAndReportsWinner() {
        val calls = mutableListOf<String>()
        val result = ShortOperationRouter.value(
            ValueAttempt(PrivilegeRoute.DAEMON) { calls += "daemon"; null },
            ValueAttempt(PrivilegeRoute.SU) { calls += "su"; AppState.BG },
        )
        assertEquals(RoutedValue(PrivilegeRoute.SU, AppState.BG), result)
        assertEquals(listOf("daemon", "su"), calls)
    }
}
