package io.warpnect.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.warpnect.platform.discovery.AndroidDiscoveryDebugLog
import io.warpnect.session.SessionRole
import io.warpnect.session.discovery.DiscoveredPresence
import io.warpnect.session.discovery.DiscoveryAvailability
import io.warpnect.session.discovery.DiscoveryDisplayAlias
import io.warpnect.session.discovery.DiscoveryPresenceId
import io.warpnect.session.discovery.DiscoveryPresenceSchema
import io.warpnect.session.discovery.DiscoveryPresenceStatus
import io.warpnect.session.discovery.DiscoveryRouteKind
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostChooserInstrumentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun oneCandidateCreatesOneVisibleSelectableHostRow() {
        val host = discoveredHost()
        val selected = AtomicReference<DiscoveredPresence?>()
        val debugLog = AndroidDiscoveryDebugLog(ApplicationProvider.getApplicationContext())

        composeRule.setContent {
            HostChooser(
                rows = hostChooserRows(listOf(host)),
                onConnect = selected::set,
                debugLog = debugLog,
            )
        }

        composeRule.onNodeWithTag(HOST_CHOOSER_TEST_TAG).assertIsDisplayed()
        composeRule.onAllNodesWithTag(HOST_ROW_TEST_TAG).assertCountEquals(1)
        composeRule.onNodeWithTag(HOST_ROW_TEST_TAG).assertIsDisplayed()

        composeRule.onNodeWithTag(HOST_CONNECT_TEST_TAG)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        composeRule.runOnIdle { assertSame(host, selected.get()) }
    }

    private fun discoveredHost() = DiscoveredPresence(
        presenceId = DiscoveryPresenceId.requireValid(1uL, 2uL),
        displayAlias = DiscoveryDisplayAlias.requireValid("Test Host"),
        offeredRole = SessionRole.Host,
        availability = DiscoveryAvailability.Available,
        discoverySchemaVersion = DiscoveryPresenceSchema.VERSION,
        firstSeenMonotonicMs = 1L,
        lastSeenMonotonicMs = 1L,
        availablePathKinds = listOf(DiscoveryRouteKind.Lan),
        status = DiscoveryPresenceStatus.Usable,
    )
}
