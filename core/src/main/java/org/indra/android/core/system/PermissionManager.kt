package org.indra.android.core.system

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PermissionManager
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /**
         * The runtime permissions this build has to ask for on this API level.
         *
         * The list lives in [RuntimePermissions] (pure, unit-tested) because two of its
         * properties are easy to get wrong and invisible at runtime: normal permissions are
         * never requestable, and a permission past its manifest `maxSdkVersion` is an
         * undeclared request the platform auto-denies without a dialog.
         */
        fun getRequiredPermissions(): Array<String> =
            RuntimePermissions.requested(Build.VERSION.SDK_INT).toTypedArray()

        /** Reads the grant now; never cache the result — a permission can be revoked anytime. */
        fun checkPermission(permission: String): Boolean =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

        fun checkAllPermissions(): Boolean = getRequiredPermissions().all { checkPermission(it) }

        fun getMissingPermissions(): List<String> = getRequiredPermissions().filterNot { checkPermission(it) }
    }
