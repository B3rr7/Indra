package org.indra.android.core.data

import org.indra.android.core.data.bundle.BundleManifest
import org.indra.android.core.system.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Typed accessors over the bundle-related preferences held by [SettingsRepository].
 * A narrow surface so the bundle channels and update manager do not depend on the whole
 * settings hub.
 */
@Singleton
class AppSettings @Inject constructor(
    private val settings: SettingsRepository,
) {
    /** Whether to check for updates only on Wi-Fi. */
    var bundleWifiOnly: Boolean
        get() = settings.bundleWifiOnly
        set(value) { settings.bundleWifiOnly = value }

    /** Epoch-ms of the last update check, for the daily throttle. */
    var bundleLastCheckMs: Long
        get() = settings.bundleLastCheckMs
        set(value) { settings.bundleLastCheckMs = value }

    /** The last manifest a check found for [id] ("tools"/"msf"), or null. */
    fun availableManifest(id: String): BundleManifest? = settings.availableManifest(id)

    /** Records (or clears) the pending manifest for [id]. */
    fun setAvailableManifest(id: String, manifest: BundleManifest?) = settings.setAvailableManifest(id, manifest)
}
