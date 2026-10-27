package org.indra.android.core.client

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IndraClient
    @Inject
    constructor() {
        /**
         * True after [startCommand] was rejected.
         *
         * The native `connected` flag is only cleared by an explicit [disconnect], never when
         * the peer dies (the reader thread just exits), so `isConnected()` keeps answering
         * true for a daemon that was killed or crashed. A command the daemon refused to start
         * is the honest liveness signal available without a native rebuild; a successful
         * reconnect clears it.
         */
        @Volatile
        private var startFailed = false

        /** Records the outcome of a start attempt: `false` means nothing ran. */
        fun noteStartResult(started: Boolean) {
            startFailed = !started
        }

        /** True when the last start attempt failed, i.e. the daemon no longer answers. */
        fun hasStartFailed(): Boolean = startFailed

        companion object {
            private var librariesLoaded = false

            init {
                try {
                    System.loadLibrary("IndraCommon")
                    System.loadLibrary("IndraClient")
                    librariesLoaded = true
                } catch (e: UnsatisfiedLinkError) {
                    org.indra.android.core.system.Logger
                        .e("Native libraries failed to load", e)
                    librariesLoaded = false
                }
            }

            fun isAvailable(): Boolean = librariesLoaded
        }

        external fun login(
            username: String,
            passwordHash: String,
        ): Boolean

        external fun isAuthenticated(): Boolean

        external fun loadHandlers(): Boolean

        external fun getHandlers(): Array<String>?

        external fun startCommand(
            handler: String,
            cmd: String,
            env: Array<String>?,
        ): Int

        external fun connect(path: String): Boolean

        external fun isConnected(): Boolean

        external fun disconnect()

        external fun shutdown()

        external fun kill(
            id: Int,
            signal: Int,
        )

        external fun sendTo(
            id: Int,
            data: ByteArray,
        ): Boolean

        external fun hadCrashed(): Boolean
    }
