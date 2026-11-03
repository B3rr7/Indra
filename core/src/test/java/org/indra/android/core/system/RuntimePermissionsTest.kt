package org.indra.android.core.system

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePermissionsTest {
    @Test
    fun `api 28 asks for both storage permissions`() {
        val requested = RuntimePermissions.requested(28)
        assertTrue(Manifest.permission.WRITE_EXTERNAL_STORAGE in requested)
        assertTrue(Manifest.permission.READ_EXTERNAL_STORAGE in requested)
    }

    @Test
    fun `api 29 to 32 never asks for write storage`() {
        // The manifest declares WRITE_EXTERNAL_STORAGE with maxSdkVersion=28; requesting it
        // there is an undeclared request that the platform auto-denies without a dialog, so a
        // launch-time request would nag forever.
        listOf(29, 30, 31, 32).forEach { sdk ->
            val requested = RuntimePermissions.requested(sdk)
            assertFalse("api $sdk", Manifest.permission.WRITE_EXTERNAL_STORAGE in requested)
            assertTrue("api $sdk", Manifest.permission.READ_EXTERNAL_STORAGE in requested)
        }
    }

    @Test
    fun `api 33 drops storage for nearby wifi and notifications`() {
        val requested = RuntimePermissions.requested(33)
        assertFalse(Manifest.permission.READ_EXTERNAL_STORAGE in requested)
        assertFalse(Manifest.permission.WRITE_EXTERNAL_STORAGE in requested)
        assertTrue(Manifest.permission.POST_NOTIFICATIONS in requested)
        assertTrue(Manifest.permission.NEARBY_WIFI_DEVICES in requested)
        // Bluetooth permissions arrived in 31 and stay.
        assertTrue(Manifest.permission.BLUETOOTH_SCAN in requested)
        assertTrue(Manifest.permission.BLUETOOTH_CONNECT in requested)
    }

    @Test
    fun `normal permissions are never requested`() {
        val normal =
            listOf(
                Manifest.permission.INTERNET,
                Manifest.permission.ACCESS_NETWORK_STATE,
                Manifest.permission.CHANGE_WIFI_STATE,
                Manifest.permission.VIBRATE,
                Manifest.permission.WAKE_LOCK,
                Manifest.permission.FOREGROUND_SERVICE,
                Manifest.permission.FOREGROUND_SERVICE_SPECIAL_USE,
            )
        (26..35).forEach { sdk ->
            val requested = RuntimePermissions.requested(sdk)
            normal.forEach { permission ->
                assertFalse("api $sdk: $permission", permission in requested)
            }
        }
    }

    @Test
    fun `bluetooth is only asked from api 31`() {
        assertFalse(Manifest.permission.BLUETOOTH_SCAN in RuntimePermissions.requested(30))
        assertTrue(Manifest.permission.BLUETOOTH_SCAN in RuntimePermissions.requested(31))
    }

    @Test
    fun `location is always requested as a foreground pair`() {
        (26..35).forEach { sdk ->
            val requested = RuntimePermissions.requested(sdk)
            assertTrue("api $sdk", Manifest.permission.ACCESS_FINE_LOCATION in requested)
            assertTrue("api $sdk", Manifest.permission.ACCESS_COARSE_LOCATION in requested)
            // Background location has to be a separate, later step.
            assertFalse("api $sdk", Manifest.permission.ACCESS_BACKGROUND_LOCATION in requested)
        }
    }

    @Test
    fun `wifi scan accepts nearby devices on api 33 and only location below it`() {
        assertEquals(
            listOf(Manifest.permission.ACCESS_FINE_LOCATION),
            RuntimePermissions.wifiScanPermissions(32),
        )
        assertEquals(
            listOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION),
            RuntimePermissions.wifiScanPermissions(33),
        )
    }

    @Test
    fun `rationale wins over already asked`() {
        assertEquals(
            RuntimePermissions.State.RATIONALE,
            RuntimePermissions.state(rationale = true, alreadyAsked = true),
        )
        assertEquals(
            RuntimePermissions.State.RATIONALE,
            RuntimePermissions.state(rationale = true, alreadyAsked = false),
        )
    }

    @Test
    fun `first denial is ask and a repeat without rationale is blocked`() {
        assertEquals(
            RuntimePermissions.State.ASK,
            RuntimePermissions.state(rationale = false, alreadyAsked = false),
        )
        assertEquals(
            RuntimePermissions.State.BLOCKED,
            RuntimePermissions.state(rationale = false, alreadyAsked = true),
        )
    }
}
