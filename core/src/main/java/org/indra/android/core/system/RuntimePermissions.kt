package org.indra.android.core.system

import android.Manifest
import android.os.Build

/**
 * The permissions Indra actually has to ask the user for, plus the three-state decision for
 * each one (see [state]).
 *
 * Two properties are pinned by unit tests here rather than left to `MainActivity`:
 *
 * 1. **Only permissions a dialog can grant are requested.** The old list also contained
 *    `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_WIFI_STATE`, `VIBRATE`, `WAKE_LOCK`,
 *    `FOREGROUND_SERVICE` and `FOREGROUND_SERVICE_SPECIAL_USE`, which the platform grants at
 *    install time. They are always "granted", so they never caused a dialog — but they made
 *    the missing-permission list look non-empty in whatever way the platform felt like, and a
 *    denial callback for something the user cannot refuse has no fix.
 * 2. **The list matches the manifest's `maxSdkVersion` windows.** `WRITE_EXTERNAL_STORAGE` is
 *    declared with `maxSdkVersion="28"`, so requesting it on Android 10..12 was a request for
 *    an *undeclared* permission: the platform auto-denies it without a dialog. That made
 *    `getMissingPermissions()` permanently non-empty there, so the "permissions were denied"
 *    snackbar appeared on every launch and the Grant action could never clear it.
 */
object RuntimePermissions {
    /**
     * Permissions to request at runtime on [sdk], mirroring the manifest declarations and
     * their `maxSdkVersion` windows.
     */
    fun requested(sdk: Int): List<String> =
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (sdk >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
            } else {
                // READ_EXTERNAL_STORAGE is declared up to API 32, WRITE only up to 28.
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                if (sdk <= Build.VERSION_CODES.P) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            if (sdk >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }

    /**
     * Permissions that can authorise a Wi-Fi **scan** on [sdk].
     *
     * Wi-Fi results moved from `ACCESS_FINE_LOCATION` to `NEARBY_WIFI_DEVICES` on Android 13,
     * and Indra declares `NEARBY_WIFI_DEVICES` with `neverForLocation` — so a user who granted
     * "Nearby devices" and denied location could scan while the old location-only gate said
     * location was required. Either grant is accepted on 13+; only location works below it.
     */
    fun wifiScanPermissions(sdk: Int): List<String> =
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /** The three states a runtime permission can be in once the grant check has failed. */
    enum class State {
        /**
         * Not granted and never asked on this install: launch the system dialog directly.
         */
        ASK,

        /**
         * Denied before, but the platform would still show the dialog: explain why first
         * (`shouldShowRequestPermissionRationale`), then launch it.
         */
        RATIONALE,

        /**
         * Denied with "don't ask again": the dialog will not appear any more, so the only fix
         * is the app's own settings page (`ACTION_APPLICATION_DETAILS_SETTINGS`).
         */
        BLOCKED,
    }

    /**
     * Classifies a denied permission.
     *
     * [rationale] is `shouldShowRequestPermissionRationale(...)`; [alreadyAsked] says whether
     * this install has already shown the system dialog for it. The platform cannot tell
     * "never asked" from "don't ask again" — both return `false` — so that bit has to be
     * remembered by the app. Without it the screen re-launched an already-blocked request on
     * every launch: no dialog appears, the denial callback fires immediately, and the snackbar
     * comes straight back.
     *
     * Only permissions that already failed the grant check are classified; a granted one never
     * needs a dialog at all, so it is not a state.
     */
    fun state(
        rationale: Boolean,
        alreadyAsked: Boolean,
    ): State =
        when {
            rationale -> State.RATIONALE
            alreadyAsked -> State.BLOCKED
            else -> State.ASK
        }
}
