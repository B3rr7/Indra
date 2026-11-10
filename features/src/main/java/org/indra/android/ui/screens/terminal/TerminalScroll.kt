package org.indra.android.ui.screens.terminal

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import org.indra.android.core.system.terminal.TerminalSession
import org.indra.android.ui.components.AutoFollowScrollEffect
import org.indra.android.ui.components.AutoFollowState

/**
 * Everything the console does on its own: follow the output, and keep the prompt reachable.
 *
 * Separate from [TerminalConsole] because these are four triggers over one scroll position, none
 * of which is a layout decision — the console's job is where things go, this one's is where the
 * list goes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TerminalScrollBehaviour(
    state: TerminalSession.State,
    listState: LazyListState,
    follow: AutoFollowState,
    focusRequest: Int,
    focusRequester: FocusRequester,
) {
    // The transcript is capped at 4000 lines, so "how many lines are missing from the head" is
    // the first line's id — a size-keyed auto-follow would freeze once the cap is hit.
    val headTrimmed = state.lines.firstOrNull()?.id?.toInt() ?: 0
    // The prompt is the transcript's last item, so its index is also the auto-follow target: the
    // prompt stays attached to the end of the output instead of being pinned to the screen. The
    // hint only exists on an empty transcript; the running cursor sits above the prompt.
    val promptIndex =
        state.lines.size + (if (state.lines.isEmpty()) 1 else 0) + (if (state.running) 1 else 0)

    AutoFollowScrollEffect(listState, state.appendCount.toInt(), { promptIndex }, follow, headTrimmed)
    LaunchedEffect(state.running) {
        if (state.running) follow.resumeFollowing()
    }
    // Back dismisses the keyboard but leaves the field focused, so a second `requestFocus()` is
    // a no-op and the caret stays invisible — the IME has to be asked for on its own.
    val keyboard = LocalSoftwareKeyboardController.current
    // Opening an empty terminal puts the caret in the prompt: the first thing the user came for
    // is typing. A transcript with output in it does not, so returning to *read* a finished run
    // does not pop the keyboard over it.
    LaunchedEffect(focusRequest) {
        if (focusRequest == 0) return@LaunchedEffect
        follow.resumeFollowing()
        revealPrompt(listState, promptIndex)
        // A tap can land while a fling is still settling, and a requester that is not attached
        // throws: dropping one tap is better than taking the screen down for it.
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }
    // The keyboard shrinks the console (`imePadding`) and LazyColumn keeps the *first* visible
    // item anchored, so the prompt would slide under the IME and the user would type blind.
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            follow.resumeFollowing()
            revealPrompt(listState, promptIndex)
        }
    }
}

/**
 * Scrolls the prompt into view, but only when it is off screen: the prompt is the transcript's
 * last row, so "reveal it" is also "go to the end", while a tap on a line that is already
 * visible must not throw the transcript away.
 */
private suspend fun revealPrompt(
    listState: LazyListState,
    index: Int,
) {
    val visible = listState.layoutInfo.visibleItemsInfo.any { it.index == index }
    if (!visible) listState.scrollToItem(index)
}
