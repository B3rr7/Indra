package org.indra.android.ui.screens.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.indra.android.core.system.terminal.AnsiStyle
import org.indra.android.core.system.terminal.TerminalInputMode
import org.indra.android.core.system.terminal.TerminalKeys
import org.indra.android.core.system.terminal.TerminalSession
import org.indra.android.ui.components.rememberAutoFollowState

private const val FONT_NORMAL = 12
private const val FONT_COMPACT = 10
private const val LINE_NORMAL = 17
private const val LINE_COMPACT = 14
private const val PAD_NORMAL = 8
private const val PAD_COMPACT = 6
private const val ICON_NORMAL = 22
private const val ICON_COMPACT = 16
private const val KEY_FONT = 13
private const val KEY_FONT_COMPACT = 11
private const val DIM_ALPHA = 0.65f
private const val SNIPPET_ALPHA = 0.35f
private const val SYMBOLS_COLLAPSED_COUNT = 4
private const val KNOB_NORMAL = 30
private const val KNOB_COMPACT = 26

/**
 * The terminal itself: a scrolling transcript of ANSI-coloured lines with the prompt as its
 * **last line**, the way Termux lays one out.
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
    val editor = remember { PromptEditor() }
    val listState = rememberLazyListState()
    val follow = rememberAutoFollowState(listState)
    // Focus requests go through a counter rather than a direct call: the prompt is an item of
    // the transcript now, so it has to be scrolled into view — and composed — before it can
    // hold the caret.
    var focusRequest by remember { mutableIntStateOf(if (!compact && state.lines.isEmpty()) 1 else 0) }
    // Tapping the output focuses the prompt, so the caret lives here rather than in the field:
    // one line at the end of a phone screen is easy to miss, and a terminal is a thing you tap
    // to type into.
    val focusRequester = remember { FocusRequester() }

    TerminalScrollBehaviour(
        state = state,
        listState = listState,
        follow = follow,
        focusRequest = focusRequest,
        focusRequester = focusRequester,
    )

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
            // The transcript owns the whole space above the key rows: the prompt is inside it,
            // so a short session starts at the top with the prompt right under it (Termux) and
            // a long one scrolls, with auto-follow keeping the prompt at the bottom.
            modifier =
                Modifier
                    .weight(1f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { focusRequest++ },
            prompt = {
                // The line the user types into, emitted as the transcript's last row: the
                // prompt flows out of the output instead of living in a separate bar.
                InputRow(
                    state = state,
                    field =
                        InputState(
                            text = editor.text,
                            cursor = editor.cursor,
                            onTextChange = editor::type,
                            onRecall = { step -> editor.recall(state.history, step) },
                            onSubmit = { editor.submit(state.running, onRun) },
                        ),
                    compact = compact,
                    inline = true,
                    focusRequester = focusRequester,
                    onStop = onStop,
                )
            },
        )
        // Snippets and symbols stay *below* the transcript — next to the keyboard, where the
        // thumb already is — and never scroll away with the output above them.
        if (showSnippets && !compact) {
            SnippetRow(
                chroot = state.chroot,
                onPick = { picked ->
                    editor.pick(picked)
                    focusRequest++
                },
            )
        }
        if (state.symbolKeys) {
            SymbolKeyRow(
                compact = compact,
                onKey = { symbol -> editor.insert(symbol.text) },
                onMove = editor::moveCursor,
                onBackspace = editor::backspace,
            )
        }
    }
}

@Composable
private fun Transcript(
    state: TerminalSession.State,
    listState: LazyListState,
    compact: Boolean,
    modifier: Modifier = Modifier,
    prompt: @Composable () -> Unit,
) {
    val padding = if (compact) PAD_COMPACT else PAD_NORMAL
    val empty = state.lines.isEmpty()
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        // Content padding rather than a modifier: the padding belongs *inside* the scrollable
        // area, so the first line is not clipped at the top once it scrolls and the prompt lines
        // up with the output it follows.
        contentPadding = PaddingValues(horizontal = padding.dp, vertical = padding.dp),
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
        items(
            items = state.lines,
            key = { it.id },
            contentType = { "terminal-line" },
        ) { line ->
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
        // Last, and always present: a terminal always shows a prompt.
        item(key = "prompt") { prompt() }
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

/** The command field's own state: the text, the caret, and what to do with them. */
private data class InputState(
    val text: String,
    val cursor: Int,
    val onTextChange: (String, Int) -> Unit,
    val onRecall: (Int) -> Unit,
    val onSubmit: () -> Unit,
)

@Composable
private fun InputRow(
    state: TerminalSession.State,
    field: InputState,
    compact: Boolean,
    inline: Boolean,
    focusRequester: FocusRequester,
    onStop: () -> Unit,
) {
    val font = (if (compact) FONT_COMPACT else FONT_NORMAL).sp
    val iconSize = (if (compact) ICON_COMPACT else ICON_NORMAL).dp
    val input = field.text
    // The field owns its selection so a tap-to-place-cursor works, but every change is reported
    // upward: the symbol row edits the text from outside and needs to know where the caret is.
    val fieldValue =
        remember(input, field.cursor) {
            TextFieldValue(input, selection = TextRange(field.cursor.coerceIn(0, input.length)))
        }
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(TerminalPalette.background)
                // Inline (the transcript's last row) it inherits the list's content padding, so
                // it needs none of its own — otherwise the prompt would be indented past every
                // line of the output it follows.
                .padding(
                    horizontal = if (inline) 0.dp else if (compact) 4.dp else 8.dp,
                    vertical = 2.dp,
                ),
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
            value = fieldValue,
            onValueChange = { value -> field.onTextChange(value.text, value.selection.end) },
            modifier =
                Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
            singleLine = state.inputMode != TerminalInputMode.PLAIN,
            // Deliberately **not** `enabled = !state.running`: disabling the field while a
            // command runs drops its focus, and dropping focus closes the IME — so every
            // command the user typed dismissed the keyboard mid-sentence. A terminal keeps
            // accepting input while its child runs; only *submission* waits (the send button
            // becomes Stop, and `submit()` refuses while running, keeping the text for later).
            textStyle =
                LocalTextStyle.current.copy(
                    color = TerminalPalette.foreground,
                    fontFamily = FontFamily.Monospace,
                    fontSize = font,
                ),
            cursorBrush = SolidColor(TerminalPalette.accent),
            keyboardOptions = state.inputMode.keyboardOptions(),
            keyboardActions = KeyboardActions(onSend = { field.onSubmit() }, onDone = { field.onSubmit() }),
            decorationBox = { field ->
                if (input.isEmpty()) {
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
            onRecall = field.onRecall,
            onSubmit = field.onSubmit,
            onStop = onStop,
        )
    }
}

/**
 * The IME contract for a shell.
 *
 * `SHELL` turns off autocorrect, suggestions and capitalisation and maps Return to Send. That is
 * the whole point: a phone IME will otherwise rewrite `-la` into a dictionary word and capitalise
 * `ls`, and a shell is case-sensitive. `PLAIN` keeps the keyboard quiet but lets Return insert a
 * newline, for a multi-line script typed by hand. `SYSTEM` hands control back to the IME.
 */
private fun TerminalInputMode.keyboardOptions(): KeyboardOptions =
    KeyboardOptions(
        // Compose 1.6 (BOM 2024.02.02) names this `autoCorrect`; it becomes `autoCorrectEnabled`
        // in 1.7. Rename it when the BOM moves.
        autoCorrect = autoCorrect,
        capitalization = if (autoCorrect) KeyboardCapitalization.Sentences else KeyboardCapitalization.None,
        keyboardType = KeyboardType.Ascii,
        imeAction = if (returnSubmits) ImeAction.Send else ImeAction.Default,
    )

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

/**
 * The Termux-style key row: the punctuation a shell needs, plus cursor movement and backspace.
 *
 * Collapsed by default — the 4 symbols a shell needs most, then a knob that zooms the row out to
 * the full [TerminalKeys.SYMBOLS] set. The knob is pinned to the leading edge rather than living in
 * its own row: a second row would cost exactly the height this compact mode is meant to save, and
 * a knob at the end of a scrollable row is a knob that scrolls out of reach.
 *
 * Always live, even while a command runs: these keys only edit the prompt's text, so there is
 * nothing to protect — and a key row that greys out for the length of a scan is a key row the
 * user cannot use to write the next command.
 */
@Composable
private fun SymbolKeyRow(
    compact: Boolean,
    onKey: (TerminalKeys.Symbol) -> Unit,
    onMove: (Int) -> Unit,
    onBackspace: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val visibleSymbols =
        remember(expanded) {
            if (expanded) TerminalKeys.SYMBOLS else TerminalKeys.SYMBOLS.take(SYMBOLS_COLLAPSED_COUNT)
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = if (compact) 4.dp else 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ZoomKnob(
            expanded = expanded,
            onToggle = { expanded = !expanded },
            compact = compact,
        )
        Row(
            modifier =
                Modifier
                    .weight(1f)
                    // Scrolls instead of clipping: the expanded row never fits a phone, and a
                    // clipped key is a key the user cannot press.
                    .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            KeyChip(label = "←", compact = compact) { onMove(-1) }
            KeyChip(label = "→", compact = compact) { onMove(1) }
            KeyChip(label = "⌫", compact = compact) { onBackspace() }
            visibleSymbols.forEach { symbol ->
                KeyChip(label = symbol.label, compact = compact) { onKey(symbol) }
            }
        }
    }
}

/**
 * The zoom knob: one round key that flips the symbol row between collapsed and full.
 *
 * Its own background rather than an [IconButton] tint because the row's keys are bare chips — the
 * knob has to read as a control, not as a symbol that inserts text.
 */
@Composable
private fun ZoomKnob(
    expanded: Boolean,
    onToggle: () -> Unit,
    compact: Boolean,
) {
    val knobSize = (if (compact) KNOB_COMPACT else KNOB_NORMAL).dp
    IconButton(
        onClick = onToggle,
        modifier = Modifier.size(knobSize),
    ) {
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription =
                if (expanded) {
                    "Collapse symbols"
                } else {
                    "Expand symbols, ${TerminalKeys.SYMBOLS.size} available"
                },
            tint = TerminalPalette.accent,
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = SNIPPET_ALPHA),
                        CircleShape,
                    )
                    .padding(if (compact) 3.dp else 4.dp),
        )
    }
}

/** One key. A bare chip rather than a Material button: 31 buttons would each carry their own
 *  minimum touch target and the row would be taller than the transcript. */
@Composable
private fun KeyChip(
    label: String,
    compact: Boolean,
    onClick: () -> Unit,
) {
    Text(
        text = label,
        color = TerminalPalette.foreground,
        fontFamily = FontFamily.Monospace,
        fontSize = (if (compact) KEY_FONT_COMPACT else KEY_FONT).sp,
        modifier =
            Modifier
                .clip(RoundedCornerShape(5.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = SNIPPET_ALPHA))
                .clickable { onClick() }
                .padding(horizontal = if (compact) 7.dp else 9.dp, vertical = 5.dp),
    )
}

/** The commands worth one tap on a phone keyboard: nothing to type, and no `sudo` needed. */
@Composable
private fun SnippetRow(
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
                color = TerminalPalette.accent,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = SNIPPET_ALPHA))
                        .clickable { onPick(command) }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
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
