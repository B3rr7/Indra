package org.indra.android.ui.screens.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.indra.android.core.system.terminal.AnsiStyle
import org.indra.android.core.system.terminal.TerminalSession
import org.indra.android.ui.components.AutoFollowScrollEffect
import org.indra.android.ui.components.rememberAutoFollowState

private const val NO_HISTORY = -1
private const val FONT_NORMAL = 12
private const val FONT_COMPACT = 10
private const val LINE_NORMAL = 17
private const val LINE_COMPACT = 14
private const val PAD_NORMAL = 8
private const val PAD_COMPACT = 6
private const val ICON_NORMAL = 22
private const val ICON_COMPACT = 16
private const val DIM_ALPHA = 0.65f
private const val SNIPPET_ALPHA = 0.35f

/**
 * The terminal itself: a scrolling transcript of ANSI-coloured lines plus the prompt input.
 *
 * Used by three surfaces — the full screen, the floating bubble and the over-app overlay —
 * so all three read the same [TerminalSession] state and look identical. Commands are not
 * run here: this composable only reports what the user typed (the session owns the process,
 * its lifetime and the cwd/env carried between commands).
 */
@Composable
fun TerminalConsole(
    state: TerminalSession.State,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showSnippets: Boolean = true,
    onRun: (String) -> Unit,
    onStop: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var historyIndex by remember { mutableIntStateOf(NO_HISTORY) }
    val listState = rememberLazyListState()
    val follow = rememberAutoFollowState(listState)
    // The transcript is capped at 4000 lines, so "how many lines are missing from the head"
    // is the first line's id — a size-keyed auto-follow would freeze once the cap is hit.
    val headTrimmed = state.lines.firstOrNull()?.id?.toInt() ?: 0

    AutoFollowScrollEffect(listState, state.appendCount.toInt(), { state.lines.size - 1 }, follow, headTrimmed)
    LaunchedEffect(state.running) {
        if (state.running) follow.resumeFollowing()
    }

    fun submit() {
        val command = input.trim()
        if (command.isEmpty() || state.running) return
        onRun(command)
        input = ""
        historyIndex = NO_HISTORY
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(TerminalPalette.background),
    ) {
        Transcript(
            state = state,
            listState = listState,
            compact = compact,
            modifier = Modifier.weight(1f),
        )
        if (showSnippets && !compact) {
            SnippetRow(
                enabled = !state.running,
                chroot = state.chroot,
                onPick = { input = it },
            )
        }
        InputRow(
            state = state,
            input = input,
            compact = compact,
            onInputChange = {
                input = it
                historyIndex = NO_HISTORY
            },
            onRecall = { step ->
                val (index, text) = recall(state.history, historyIndex, step)
                historyIndex = index
                input = text
            },
            onSubmit = ::submit,
            onStop = onStop,
        )
    }
}

@Composable
private fun Transcript(
    state: TerminalSession.State,
    listState: LazyListState,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    val padding = if (compact) PAD_COMPACT else PAD_NORMAL
    val empty = state.lines.isEmpty()
    LazyColumn(
        state = listState,
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = padding.dp, vertical = padding.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        if (empty) {
            item(key = "hint") {
                Text(
                    text =
                        "Indra shell — type a command and press ↵.\n" +
                            "▲ ▼ walk the history; \"clear\" wipes the screen.",
                    color = TerminalPalette.info,
                    fontFamily = FontFamily.Monospace,
                    fontSize = (if (compact) FONT_COMPACT else FONT_NORMAL).sp,
                )
            }
        }
        items(state.lines, key = { it.id }) { line ->
            LineRow(line, compact)
        }
        if (state.running) {
            item(key = "cursor") {
                Text(
                    text = "▌",
                    color = TerminalPalette.accent,
                    fontFamily = FontFamily.Monospace,
                    fontSize = (if (compact) FONT_COMPACT else FONT_NORMAL).sp,
                )
            }
        }
    }
}

@Composable
private fun LineRow(
    line: TerminalSession.Line,
    compact: Boolean,
) {
    Text(
        text = line.annotated(),
        fontFamily = FontFamily.Monospace,
        fontSize = (if (compact) FONT_COMPACT else FONT_NORMAL).sp,
        lineHeight = (if (compact) LINE_COMPACT else LINE_NORMAL).sp,
    )
}

@Composable
private fun InputRow(
    state: TerminalSession.State,
    input: String,
    compact: Boolean,
    onInputChange: (String) -> Unit,
    onRecall: (Int) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
) {
    val font = (if (compact) FONT_COMPACT else FONT_NORMAL).sp
    val iconSize = (if (compact) ICON_COMPACT else ICON_NORMAL).dp
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(TerminalPalette.background)
                .padding(horizontal = if (compact) 4.dp else 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = state.prompt,
            color = TerminalPalette.prompt,
            fontFamily = FontFamily.Monospace,
            fontSize = font,
            maxLines = 1,
        )
        BasicTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            enabled = !state.running,
            textStyle =
                LocalTextStyle.current.copy(
                    color = TerminalPalette.foreground,
                    fontFamily = FontFamily.Monospace,
                    fontSize = font,
                ),
            cursorBrush = SolidColor(TerminalPalette.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSubmit() }),
            decorationBox = { field ->
                if (input.isEmpty() && !state.running) {
                    Text(
                        text = "command…",
                        color = TerminalPalette.info.copy(alpha = DIM_ALPHA),
                        fontFamily = FontFamily.Monospace,
                        fontSize = font,
                    )
                }
                field()
            },
        )
        InputActions(
            running = state.running,
            canRecall = state.history.isNotEmpty(),
            canSubmit = input.isNotBlank(),
            iconSize = iconSize,
            onRecall = onRecall,
            onSubmit = onSubmit,
            onStop = onStop,
        )
    }
}

@Composable
private fun InputActions(
    running: Boolean,
    canRecall: Boolean,
    canSubmit: Boolean,
    iconSize: Dp,
    onRecall: (Int) -> Unit,
    onSubmit: () -> Unit,
    onStop: () -> Unit,
) {
    IconButton(
        onClick = { onRecall(-1) },
        enabled = !running && canRecall,
        modifier = Modifier.size(iconSize + 8.dp),
    ) {
        Icon(
            Icons.Default.KeyboardArrowUp,
            contentDescription = "Previous command",
            tint = TerminalPalette.info,
            modifier = Modifier.size(iconSize),
        )
    }
    IconButton(
        onClick = { onRecall(1) },
        enabled = !running && canRecall,
        modifier = Modifier.size(iconSize + 8.dp),
    ) {
        Icon(
            Icons.Default.KeyboardArrowDown,
            contentDescription = "Next command",
            tint = TerminalPalette.info,
            modifier = Modifier.size(iconSize),
        )
    }
    if (running) {
        IconButton(
            onClick = onStop,
            modifier = Modifier.size(iconSize + 8.dp),
        ) {
            Icon(
                Icons.Default.Stop,
                contentDescription = "Stop",
                tint = TerminalPalette.error,
                modifier = Modifier.size(iconSize),
            )
        }
    } else {
        IconButton(
            onClick = onSubmit,
            enabled = canSubmit,
            modifier = Modifier.size(iconSize + 8.dp),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Send,
                contentDescription = "Run",
                tint = TerminalPalette.accent,
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/** The commands worth one tap on a phone keyboard: nothing to type, and no `sudo` needed. */
@Composable
private fun SnippetRow(
    enabled: Boolean,
    chroot: Boolean,
    onPick: (String) -> Unit,
) {
    val snippets =
        if (chroot) {
            listOf("apk update", "apk add python3", "python3 --version")
        } else {
            listOf("ip addr", "ip neigh", "ps -A", "ls -l", "id")
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                // Scrolls instead of clipping: five chips do not fit a 1080 px phone once the
                // IME is up, and a clipped chip is a command the user cannot reach.
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        snippets.forEach { command ->
            Text(
                text = command,
                color = if (enabled) TerminalPalette.accent else TerminalPalette.info,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = SNIPPET_ALPHA))
                        .clickable(enabled = enabled) { onPick(command) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * Walks the history: `step = -1` is older, `1` is newer. Returns the new cursor index (or
 * [NO_HISTORY]) and the text to put in the field — stepping past the newest entry clears it.
 */
private fun recall(
    history: List<String>,
    from: Int,
    step: Int,
): Pair<Int, String> =
    when {
        history.isEmpty() -> NO_HISTORY to ""
        step > 0 && from < 0 -> NO_HISTORY to ""
        step > 0 -> (from + 1).takeIf { it <= history.lastIndex }?.let { it to history[it] } ?: (NO_HISTORY to "")
        from < 0 -> history.lastIndex to history.last()
        else -> (from - 1).coerceAtLeast(0).let { it to history[it] }
    }

/** Turns one transcript line into styled text — this is what makes the output *look* a terminal. */
private fun TerminalSession.Line.annotated(): AnnotatedString =
    buildAnnotatedString {
        spans.forEach { span ->
            withStyle(span.style.resolve(kind)) { append(span.text) }
        }
    }

private fun TerminalSession.Kind.baseColour(): Color =
    when (this) {
        TerminalSession.Kind.ERROR -> TerminalPalette.error
        TerminalSession.Kind.INFO -> TerminalPalette.info
        TerminalSession.Kind.COMMAND -> TerminalPalette.prompt
        TerminalSession.Kind.OUTPUT -> TerminalPalette.foreground
    }

private fun AnsiStyle.resolve(kind: TerminalSession.Kind): SpanStyle {
    val base = kind.baseColour()
    val foreground = foreground?.let { TerminalPalette.of(it) } ?: base
    val background = background?.let { TerminalPalette.of(it) }
    // Reverse video is how many tools draw their current selection (`\e[7m`), so it has to
    // swap the two colours rather than be ignored.
    val color = if (inverse) background ?: TerminalPalette.background else foreground
    val fill = if (inverse) foreground else background
    return SpanStyle(
        color = if (dim) color.copy(alpha = DIM_ALPHA) else color,
        background = fill ?: Color.Unspecified,
        fontWeight = if (bold) FontWeight.Bold else null,
        fontStyle = if (italic) FontStyle.Italic else null,
        textDecoration = if (underline) TextDecoration.Underline else null,
    )
}
