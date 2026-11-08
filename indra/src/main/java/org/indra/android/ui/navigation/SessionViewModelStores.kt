package org.indra.android.ui.navigation

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.SAVED_STATE_REGISTRY_OWNER_KEY
import androidx.lifecycle.VIEW_MODEL_STORE_OWNER_KEY
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey

/**
 * One [ViewModelStore] per destination, kept for the whole app session.
 *
 * Navigation 3 normally scopes a ViewModel to its back-stack entry and clears it when the entry is
 * popped. That is right for a form, and wrong for a tool: leaving Port Scanner to open Masscan
 * destroyed the scanner's ViewModel, which
 * - erased its results (a fresh ViewModel starts from its default state), and
 * - cancelled `viewModelScope`, so the live output collector stopped mid-scan.
 *
 * A store per `Screen` fixes both without touching the 38 plugin ViewModels: the ViewModel is not
 * cleared on pop, so its `StateFlow` keeps the last results and a running tool keeps collecting.
 * The entries are still popped from the back stack — this is retention of state, not of
 * navigation history.
 *
 * Cleared when the host Activity is destroyed (the registry is itself a [ViewModel]), which runs
 * every retained ViewModel's `onCleared()` — that is what stops a tool still running when the app
 * goes away. A process kill skips it, as it did before.
 */
class SessionViewModelStores : ViewModel() {
    private val stores = LinkedHashMap<Any, ViewModelStore>()

    /**
     * The store for [key], created on first use and reused by every later visit.
     *
     * [key] is the navigation entry's `contentKey` — Navigation 3 builds it as
     * `Pair(key.toString(), key::class.toString())`, so it is unique per destination and
     * **value-equal** across visits (including after a config change or process death), which is
     * exactly the identity we need. The entry's own `key` is private and not reachable from a
     * decorator.
     */
    fun storeFor(key: Any): ViewModelStore = stores.getOrPut(key) { ViewModelStore() }

    override fun onCleared() = clearAll()

    /** Drops every retained store, running each ViewModel's `onCleared()`. */
    fun clearAll() {
        stores.values.forEach { it.clear() }
        stores.clear()
    }
}

/**
 * Replaces `rememberViewModelStoreNavEntryDecorator` with session-scoped stores.
 *
 * The saveable-state decorator is separate and still required: it owns `rememberSaveable`, which
 * this does not touch. Without an Activity (preview) the entry is rendered as-is and
 * `hiltViewModel()` falls back to the Activity store, exactly as before.
 */
@Composable
fun rememberSessionNavEntryDecorator(stores: SessionViewModelStores): NavEntryDecorator<NavKey> {
    val activity = LocalActivity.current
    return remember(stores, activity) {
        NavEntryDecorator<NavKey>(
            // Deliberately no cleanup: a popped entry must keep its ViewModel (and its tool)
            // alive. [SessionViewModelStores.onCleared] is the one place that clears them.
            onPop = {},
            decorate = { entry ->
                // Unconditional `remember` on the entry's identity: keying a conditional one
                // would change the composition structure between entries.
                val owner =
                    remember(entry.contentKey) {
                        (activity as? ComponentActivity)?.let {
                            SessionStoreOwner(stores.storeFor(entry.contentKey), it)
                        }
                    }
                if (owner == null) {
                    entry.Content()
                } else {
                    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                        entry.Content()
                    }
                }
            },
        )
    }
}

/**
 * A [ViewModelStoreOwner] over a retained store that still gives Hilt the Activity's factory.
 *
 * `hiltViewModel()` reads [HasDefaultViewModelProviderFactory] from the current owner; without it
 * the factory falls back to the Activity through `LocalContext`, but the creation extras would be
 * empty. Delegating both keeps `@HiltViewModel` construction identical to an entry-scoped store.
 */
private class SessionStoreOwner(
    override val viewModelStore: ViewModelStore,
    private val activity: ComponentActivity,
) : ViewModelStoreOwner,
    HasDefaultViewModelProviderFactory {
    override val defaultViewModelProviderFactory: ViewModelProvider.Factory
        get() = activity.defaultViewModelProviderFactory

    override val defaultViewModelCreationExtras: CreationExtras
        get() =
            MutableCreationExtras(activity.defaultViewModelCreationExtras).apply {
                set(ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY, activity.application)
                set(SAVED_STATE_REGISTRY_OWNER_KEY, activity)
                set(VIEW_MODEL_STORE_OWNER_KEY, this@SessionStoreOwner)
            }
}
