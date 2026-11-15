package org.indra.android.ui.screens.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.indra.android.core.system.ToolRunner
import org.indra.android.core.system.hid.HidLayout
import org.indra.android.ui.components.FormError

/**
 * The gate the two HID screens share: "can this phone type into a host, and what would make it".
 *
 * Both screens used to run `[ -e /dev/hidg0 ]` and print one sentence — `hidg0 not available: no HID
 * function bound to a UDC` — for four different states. Two of them are fixable from the screen
 * (`RadioProfile`-style: ask the driver instead of assuming) and one of those is a button:
 *
 * * **ready** — the node exists, keystrokes go to the host;
 * * **needs preparing** — the kernel supports HID and the gadget is live but nothing is linked in,
 *   so [HidLayout.Verdict.Creatable] carries the commands and the screen offers
 *   [HidLayout.PREPARE_LABEL] (measured on the moto g60: this state produced `/dev/hidg0` and a real
 *   keyboard on the attached host);
 * * **blocked** — no controller, no gadget, or a driver that will not do it; the sentence names the
 *   actual cause instead of the symptom.
 */
sealed interface GadgetGate {
    data object Ready : GadgetGate

    data class NeedsPrepare(
        val gadget: HidLayout.Verdict.Creatable,
        val message: String,
    ) : GadgetGate

    data class Blocked(val message: String) : GadgetGate
}

/** Asks the device once, before a screen starts writing reports that would silently vanish. */
internal suspend fun ToolRunner.gadgetGate(): GadgetGate =
    when (val verdict = hidLayout()) {
        is HidLayout.Verdict.Ready -> GadgetGate.Ready
        is HidLayout.Verdict.Creatable ->
            GadgetGate.NeedsPrepare(verdict, HidLayout.creatableWarning(verdict.gadget, verdict.udc))
        is HidLayout.Verdict.Unbound ->
            GadgetGate.Blocked(
                "This phone's USB gadget is not bound to a controller (${verdict.gadget}), so there is no " +
                    "HID interface to write to. Reconnecting the cable usually re-binds it.",
            )
        is HidLayout.Verdict.Refused -> GadgetGate.Blocked(verdict.reason)
    }

/**
 * The line a run reports when the gate is not ready, or null when it is.
 *
 * The explanation belongs to the fix card, not to the transcript: a run that refuses should say
 * what to press, and pressing it shows why.
 */
internal fun GadgetGate.blockedMessage(): String? =
    when (this) {
        GadgetGate.Ready -> null
        is GadgetGate.NeedsPrepare -> "The HID gadget is not ready. Press ${HidLayout.PREPARE_LABEL} first."
        is GadgetGate.Blocked -> message
    }

/**
 * What a HID screen shows for the gate: nothing when ready, the fix card when one command away,
 * the honest sentence when this device cannot do it.
 */
@Composable
internal fun GadgetSection(
    gate: GadgetGate?,
    preparing: Boolean,
    onPrepare: () -> Unit,
) {
    when (gate) {
        null, GadgetGate.Ready -> Unit
        is GadgetGate.NeedsPrepare -> HidGadgetFix(gate, preparing, onPrepare)
        is GadgetGate.Blocked -> FormError(gate.message)
    }
}

/**
 * The fix card for [GadgetGate.NeedsPrepare] — a sentence and one button.
 *
 * Deliberately not a silent automatic repair: the commands re-enumerate USB, which drops the host's
 * view of the phone (and any adb session over that cable) for a moment, so it stays a user action.
 */
@Composable
internal fun HidGadgetFix(
    gate: GadgetGate.NeedsPrepare,
    preparing: Boolean,
    onPrepare: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(gate.message, style = MaterialTheme.typography.bodySmall)
        Button(onClick = onPrepare, enabled = !preparing) {
            Text(if (preparing) "Preparing…" else HidLayout.PREPARE_LABEL)
        }
    }
}
