package org.indra.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NmapSmokeInstrumentedTest {
    @Test
    fun appContext_packageName() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(ctx.packageName == "org.indra.android")
    }
    // TODO: end-to-end nmap localhost scan requires rooted device + daemon.
    // Add when running on a rooted test device:
    //   - start daemon via CoreManager
    //   - run SmartToolBox.nmap("127.0.0.1", "1-100")
    //   - assert at least one port found
}
