package org.indra.android.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The retention rules behind "switch tools and the first tool's results are still there".
 *
 * The bug this pins: Navigation 3 cleared each destination's `ViewModelStore` on pop, so a plugin
 * screen that was left (or replaced by another plugin) came back with a fresh ViewModel — no
 * results, and no live collector for a scan still running. The back stack still pops; only the
 * *state* is retained, and only for the app session.
 */
class SessionViewModelStoresTest {
    @Test
    fun `the same destination is served the same store`() {
        val stores = SessionViewModelStores()
        assertSame(stores.storeFor(Screen.PortScanner), stores.storeFor(Screen.PortScanner))
    }

    @Test
    fun `destinations do not share a store`() {
        val stores = SessionViewModelStores()
        assertNotSame(stores.storeFor(Screen.PortScanner), stores.storeFor(Screen.Inspector))
    }

    @Test
    fun `identity is by value, so a rebuilt navigation content key hits the same store`() {
        // Navigation 3 hands the decorator `Pair(key.toString(), key::class.toString())`, freshly
        // built for every visit. Equality on that Pair (not instance identity) is what makes the
        // store outlive the entry.
        val stores = SessionViewModelStores()
        val first = stores.storeFor(Screen.PortScanner to "Screen.PortScanner")
        val second = stores.storeFor(Screen.PortScanner to "Screen.PortScanner")
        assertSame(first, second)
        assertNotSame(first, stores.storeFor(Screen.Inspector to "Screen.Inspector"))
    }

    @Test
    fun `a retained view model is reused across visits, not re-created`() {
        val stores = SessionViewModelStores()
        val first = probeIn(stores, Screen.PortScanner)
        assertSame(first, probeIn(stores, Screen.PortScanner))
        assertEquals(0, first.cleared)
    }

    @Test
    fun `clearing the session clears every retained view model`() {
        val stores = SessionViewModelStores()
        val scanner = probeIn(stores, Screen.PortScanner)
        val inspector = probeIn(stores, Screen.Inspector)

        stores.clearAll()

        assertEquals(1, scanner.cleared)
        assertEquals(1, inspector.cleared)
        // And the destination comes back clean rather than resurrecting a cleared ViewModel.
        val revived =
            ViewModelProvider(stores.storeFor(Screen.PortScanner), probeFactory).get(ProbeViewModel::class.java)
        assertNotSame(scanner, revived)
        assertEquals(0, revived.cleared)
    }

    /** Adds [ProbeViewModel] to the destination's store the same way `hiltViewModel()` would. */
    private fun probeIn(stores: SessionViewModelStores, key: Any): ProbeViewModel =
        ViewModelProvider(stores.storeFor(key), probeFactory).get(ProbeViewModel::class.java)

    private val probeFactory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ProbeViewModel() as T
        }

    private class ProbeViewModel : ViewModel() {
        var cleared = 0
            private set

        override fun onCleared() {
            cleared++
        }
    }
}
