@file:Suppress("MatchingDeclarationName")

package org.indra.android.ui.components

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow

/**
 * Tracks whether the user wants the output pane to follow new content.
 *
 * Intent-based instead of position-based: a burst stream can outrun per-item
 * position checks and strand the view mid-list, from which "am I at the
 * bottom?" can never recover. A user drag always pauses following; scrolling
 * back to the last item resumes it. Programmatic scrolls don't emit drags, so
 * the follow survives burst streaming.
 */
class AutoFollowState {
    internal var following: Boolean = true

    /** Re-enable following (e.g. when a new command is submitted). */
    fun resumeFollowing() {
        following = true
    }
}

@Composable
fun rememberAutoFollowState(listState: LazyListState): AutoFollowState {
    val state = remember { AutoFollowState() }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) state.following = false
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo }.collect { info ->
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            if (!state.following && last >= info.totalItemsCount - 1) {
                state.following = true
            }
        }
    }
    return state
}

/**
 * Scrolls to [targetIndex] on every [updateKey] change while following is on.
 *
 * While the user is reading history ([follow] off), [headTrimmed] — the
 * cumulative count of items dropped from the head of a rolling list — is
 * compensated by re-anchoring the scroll, so history does not drift past the
 * viewport as new items evict old ones.
 *
 * [updateKey] must change whenever the content changes — for a rolling list
 * the list size stops growing, so pass a monotonic counter of appended items
 * instead of `list.size`.
 */
@Composable
fun AutoFollowScrollEffect(
    listState: LazyListState,
    updateKey: Int,
    targetIndex: () -> Int,
    follow: AutoFollowState,
    headTrimmed: Int = 0,
) {
    val lastTrimmed = remember { mutableIntStateOf(0) }
    LaunchedEffect(updateKey, headTrimmed) {
        if (updateKey <= 0) return@LaunchedEffect
        if (!follow.following) {
            val delta = headTrimmed - lastTrimmed.value
            val first = listState.firstVisibleItemIndex
            val offset = listState.firstVisibleItemScrollOffset
            if (delta > 0 && first - delta >= 0) {
                listState.scrollToItem(first - delta, offset)
            }
        } else {
            val target = targetIndex()
            if (target >= 0) listState.scrollToItem(target)
        }
        lastTrimmed.intValue = headTrimmed
    }
}
