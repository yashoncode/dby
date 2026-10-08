package com.dby.mobile.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dby.core.Env
import com.dby.mobile.DbyApp
import com.dby.mobile.data.details
import com.dby.mobile.data.sentence
import com.dby.mobile.ui.DbyIcons
import com.dby.mobile.ui.glass.frostedGlass
import com.dby.mobile.ui.glass.lightGlass
import com.dby.mobile.ui.highlightSql
import com.dby.mobile.ui.theme.Dby
import com.dby.mobile.ui.theme.LocalAccent
import com.dby.mobile.ui.theme.Type
import com.dby.mobile.ui.theme.colors
import com.kyant.backdrop.Backdrop
import com.kyant.shapes.UnevenRoundedRectangle

/** A 44 dp round icon button with the canvas's light fill. */
@Composable
fun RoundButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Dby.Fg,
    enabled: Boolean = true,
) {
    val haptic = rememberHaptics()
    Box(
        modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(Dby.FillStrong)
            .clickable(enabled = enabled, role = Role.Button) { haptic(HapticFeedbackType.ContextClick); onClick() }
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (enabled) tint else Dby.Faint, modifier = Modifier.size(20.dp))
    }
}

/** The header row: back on the left, actions on the right. */
@Composable
fun TopBar(onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (onBack != null) RoundButton(DbyIcons.Back, "Back", onBack)
        Spacer(Modifier.weight(1f))
        actions()
    }
}

@Composable
fun LargeTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Type.LargeTitle, modifier = modifier.padding(horizontal = 16.dp))
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = Type.Section, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** A light-glass card of rows; put a [Hairline] between rows. */
@Composable
fun GroupCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.padding(horizontal = 16.dp).fillMaxWidth().lightGlass(RoundedCornerShape(22.dp)), content = content)
}

/** The 0.5 dp divider, inset to the text column. */
@Composable
fun Hairline(start: Dp = 16.dp) {
    Box(Modifier.padding(start = start).fillMaxWidth().height(0.5.dp).background(Dby.Hairline))
}

@Composable
fun Chevron() {
    Icon(DbyIcons.Chevron, contentDescription = null, tint = Dby.Faint, modifier = Modifier.size(16.dp))
}

/** A row: leading slot, title (with extras such as a badge) and subtitle, trailing slot. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    titleStyle: TextStyle = Type.Body,
    subtitleColor: Color = Dby.Secondary,
    leading: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit = { if (onClick != null) Chevron() },
    titleExtra: @Composable RowScope.() -> Unit = {},
) {
    val haptic = rememberHaptics()
    val clicks = if (onClick != null || onLongClick != null) {
        Modifier.combinedClickable(
            onClick = { onClick?.invoke() },
            onLongClick = onLongClick?.let { long -> { haptic(HapticFeedbackType.LongPress); long() } },
        )
    } else {
        Modifier
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).then(clicks).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = titleStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                titleExtra()
            }
            if (subtitle != null) {
                Text(subtitle, style = Type.Secondary, color = subtitleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing()
    }
}

@Composable
fun EnvBadge(env: Env) {
    val (fg, bg) = env.colors()
    Text(
        env.name,
        style = Type.Caption.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp, fontSize = 11.sp, lineHeight = 14.sp),
        color = fg,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** A pill chip; selected chips invert to light on dark like the canvas. */
@Composable
fun Chip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    count: String? = null,
    icon: ImageVector? = null,
    onClose: (() -> Unit)? = null,
) {
    val fg = if (selected) Dby.Bg else Dby.Fg
    val haptic = rememberHaptics()
    Row(
        Modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(if (selected) Dby.Fg else Dby.Fill)
            .border(0.5.dp, if (selected) Dby.Fg else Dby.Outline, CircleShape)
            .selectable(selected = selected, role = Role.Button) { haptic(HapticFeedbackType.SegmentTick); onClick() }
            .padding(start = 14.dp, end = if (onClose != null) 8.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
        Text(text, style = Type.Secondary.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1)
        if (count != null) Text(count, style = Type.Caption, color = if (selected) Color(0xA80B0B0E) else Dby.Tertiary)
        if (onClose != null) {
            Box(Modifier.size(24.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(DbyIcons.Close, contentDescription = "Remove", tint = fg, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
fun <T> Segmented(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
) {
    val haptic = rememberHaptics()
    Row(
        modifier.clip(RoundedCornerShape(22.dp)).background(Dby.Fill).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (option in options) {
            val on = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (on) Dby.Selected else Color.Transparent)
                    .selectable(selected = on, role = Role.Tab) { haptic(HapticFeedbackType.SegmentTick); onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    style = Type.Secondary.copy(fontWeight = FontWeight.SemiBold),
                    color = if (on) Dby.Fg else Dby.Secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** The canvas's switch: 52 × 32 track, accent when on. */
@Composable
fun Toggle(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val accent = LocalAccent.current
    val knob by animateDpAsState(if (checked) 20.dp else 0.dp, label = "knob")
    val haptic = rememberHaptics()
    Box(
        Modifier.size(64.dp, 44.dp).toggleable(checked, enabled = enabled, role = Role.Switch) {
            haptic(if (it) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
            onChange(it)
        },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(52.dp, 32.dp)
                .clip(CircleShape)
                .background(if (checked) accent else Color(0x3DFFFFFF))
                .padding(2.dp),
        ) {
            Box(Modifier.offset(x = knob).size(28.dp).shadow(2.dp, CircleShape).background(Color.White, CircleShape))
        }
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = Type.Body, color = if (enabled) Dby.Fg else Dby.Tertiary)
            if (subtitle != null) Text(subtitle, style = Type.Secondary.copy(fontSize = 14.sp, lineHeight = 19.sp), color = Dby.Secondary)
        }
        Toggle(checked, onChange, enabled)
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    busy: Boolean = false,
) {
    val accent = LocalAccent.current
    val fg = if (enabled) Dby.Bg else Dby.Faint
    val haptic = rememberHaptics()
    Row(
        modifier
            .height(52.dp)
            .clip(CircleShape)
            .background(if (enabled) accent else Dby.FillStrong)
            .clickable(enabled = enabled && !busy, role = Role.Button) { haptic(HapticFeedbackType.Confirm); onClick() }
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), color = fg, strokeWidth = 2.dp)
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        }
        Text(text, style = Type.Button, color = fg, maxLines = 1)
    }
}

@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    tint: Color = Dby.Fg,
    busy: Boolean = false,
) {
    val fg = if (enabled) tint else Dby.Faint
    val haptic = rememberHaptics()
    Row(
        modifier
            .height(52.dp)
            .clip(CircleShape)
            .background(Dby.FillStrong)
            .clickable(enabled = enabled && !busy, role = Role.Button) { haptic(HapticFeedbackType.ContextClick); onClick() }
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), color = fg, strokeWidth = 2.dp)
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        }
        Text(text, style = Type.Button.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1)
    }
}

/** A bare text field in the app's type; the container comes from where it is placed. */
@Composable
fun Input(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    mono: Boolean = false,
    enabled: Boolean = true,
    align: TextAlign = TextAlign.Start,
    singleLine: Boolean = true,
    style: TextStyle = if (mono) Type.Mono else Type.Body,
) {
    val textStyle = style.copy(color = if (enabled) Dby.Fg else Dby.Tertiary, textAlign = align)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = singleLine,
        textStyle = textStyle,
        cursorBrush = SolidColor(LocalAccent.current),
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboard,
            autoCorrectEnabled = false,
            capitalization = KeyboardCapitalization.None,
        ),
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier.heightIn(min = 44.dp),
        decorationBox = { inner ->
            Box(contentAlignment = if (align == TextAlign.End) Alignment.CenterEnd else Alignment.CenterStart) {
                if (value.isEmpty()) Text(placeholder, style = textStyle.copy(color = Dby.Faint), maxLines = 1)
                inner()
            }
        },
    )
}

/** A form row: label on the left, value on the right, as in the canvas's Edit row sheet. */
@Composable
fun FieldRow(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    mono: Boolean = true,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.width(112.dp)) {
            Text(label, style = Type.Body.copy(fontSize = 16.sp))
            if (supporting != null) Text(supporting, style = Type.Caption, color = Dby.Tertiary)
        }
        Input(value, onChange, Modifier.weight(1f), placeholder, keyboard, secret, mono, enabled, TextAlign.End)
    }
}

@Composable
fun SearchField(value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, inset: Dp = 16.dp) {
    Row(
        modifier
            .padding(horizontal = inset)
            .fillMaxWidth()
            .height(44.dp)
            .lightGlass(RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(DbyIcons.Search, contentDescription = null, tint = Dby.Tertiary, modifier = Modifier.size(18.dp))
        Input(value, onChange, Modifier.weight(1f), placeholder, style = Type.Body.copy(fontWeight = FontWeight.Normal))
        if (value.isNotEmpty()) {
            Icon(DbyIcons.Close, contentDescription = "Clear", tint = Dby.Tertiary, modifier = Modifier.size(18.dp).clickable { onChange("") })
        }
    }
}

/** A failure: one sentence, the raw text behind "Details", and an optional retry. */
@Composable
fun ProblemBanner(problem: Throwable, onRetry: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    var details by remember(problem) { mutableStateOf(false) }
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Dby.DangerFill)
            .border(0.5.dp, Color(0x40FF453A), shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(problem.sentence(), style = Type.Secondary.copy(fontWeight = FontWeight.SemiBold), color = Dby.Danger)
        if (details) Text(problem.details(), style = Type.MonoSmall, color = Dby.Secondary)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Text(
                if (details) "Hide details" else "Details",
                style = Type.Caption.copy(fontWeight = FontWeight.SemiBold),
                color = Dby.Secondary,
                modifier = Modifier.clickable { details = !details },
            )
            if (onRetry != null) {
                Text("Retry", style = Type.Caption.copy(fontWeight = FontWeight.SemiBold), color = LocalAccent.current, modifier = Modifier.clickable(onClick = onRetry))
            }
        }
    }
}

@Composable
fun Busy(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), color = Dby.Secondary, strokeWidth = 2.dp)
        Text(text, style = Type.Secondary, color = Dby.Secondary)
    }
}

@Composable
fun EmptyState(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = Type.Section, textAlign = TextAlign.Center)
        Text(body, style = Type.Secondary, color = Dby.Secondary, textAlign = TextAlign.Center)
        if (action != null) PrimaryButton(action, onAction, Modifier.padding(top = 8.dp), icon = DbyIcons.Plus)
    }
}

/** SQL in the canvas's dark box, coloured. */
@Composable
fun SqlBox(sql: String, modifier: Modifier = Modifier, header: String? = null, note: String? = null) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color(0x52000000))
            .border(0.5.dp, Dby.Hairline, shape)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (header != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(header, style = Type.Secondary.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.weight(1f))
                if (note != null) Text(note, style = Type.Caption, color = Dby.Secondary)
            }
        }
        Text(highlightSql(sql), style = Type.MonoCode, softWrap = false, modifier = Modifier.horizontalScroll(rememberScrollState()))
    }
}

/**
 * A bottom sheet drawn inside the screen rather than in its own window, so its frosted glass
 * blurs the screen underneath. It slides up on open; dragging it down (anywhere on it, or past the
 * top of a list inside it), Back and a tap on the scrim slide it away. The tab pill hides meanwhile.
 */
@Composable
fun Sheet(backdrop: Backdrop, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val nav = DbyApp.instance.nav
    DisposableEffect(Unit) {
        nav.sheetShown()
        onDispose { nav.sheetHidden() }
    }
    val dismissNow by rememberUpdatedState(onDismiss)
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screen = constraints.maxHeight.toFloat()
        // How far the sheet sits below its resting place, in pixels; starts off screen.
        val offset = remember { Animatable(screen) }
        var height by remember { mutableFloatStateOf(screen) }
        var closing by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { offset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }

        fun close() {
            if (closing) return
            closing = true
            scope.launch {
                offset.animateTo(height, tween(220))
                dismissNow()
            }
        }

        fun settle(velocity: Float) {
            if (offset.value > height * 0.25f || velocity > 1_800f) close()
            else scope.launch { offset.animateTo(0f, spring(stiffness = Spring.StiffnessMedium)) }
        }

        fun dragBy(delta: Float) {
            if (!closing) scope.launch { offset.snapTo((offset.value + delta).coerceAtLeast(0f)) }
        }

        // A list inside the sheet scrolls first; past its top, the drag moves the sheet instead.
        val lists = remember {
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (available.y >= 0f || offset.value <= 0f) return Offset.Zero
                    val used = maxOf(available.y, -offset.value)
                    dragBy(used)
                    return Offset(0f, used)
                }

                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (available.y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
                    dragBy(available.y)
                    return Offset(0f, available.y)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    if (offset.value <= 0f) return Velocity.Zero
                    settle(available.y)
                    return available
                }
            }
        }

        BackHandler { close() }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 1f - (offset.value / height).coerceIn(0f, 1f) }
                .background(Dby.Scrim)
                .clickable(interactionSource = null, indication = null) { close() },
        )
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .offset { IntOffset(0, offset.value.roundToInt()) }
                .fillMaxWidth()
                .heightIn(max = maxHeight * 0.92f)
                .onSizeChanged { height = it.height.toFloat().coerceAtLeast(1f) }
                .nestedScroll(lists)
                .draggable(
                    rememberDraggableState { dragBy(it) },
                    Orientation.Vertical,
                    onDragStopped = { settle(it) },
                )
                .frostedGlass(backdrop, UnevenRoundedRectangle(topStart = 32.dp, topEnd = 32.dp))
                .clickable(interactionSource = null, indication = null) {}
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(40.dp, 5.dp).clip(CircleShape).background(Color(0x4DFFFFFF)))
            content()
        }
    }
}

@Composable
fun SheetHeader(title: String, subtitle: String? = null, onClose: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = Type.Title)
            if (subtitle != null) Text(subtitle, style = Type.Mono, color = Dby.Secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
        RoundButton(DbyIcons.Close, "Close", onClose)
    }
}

class Action(val label: String, val icon: ImageVector? = null, val danger: Boolean = false, val onClick: () -> Unit)

/** A list of actions in a sheet: long-press menus and "More" buttons. */
@Composable
fun ActionSheet(backdrop: Backdrop, title: String?, actions: List<Action>, onDismiss: () -> Unit) {
    val haptic = rememberHaptics()
    Sheet(backdrop, onDismiss) {
        if (title != null) Text(title, style = Type.Section, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Column(Modifier.fillMaxWidth().lightGlass(RoundedCornerShape(22.dp))) {
            actions.forEachIndexed { i, action ->
                if (i > 0) Hairline(if (action.icon != null) 52.dp else 16.dp)
                val tint = if (action.danger) Dby.Danger else Dby.Fg
                Row(
                    Modifier.fillMaxWidth().height(56.dp).clickable(role = Role.Button) { haptic(HapticFeedbackType.ContextClick); action.onClick() }.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (action.icon != null) Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
                    Text(action.label, style = Type.Body, color = tint)
                }
            }
        }
    }
}

/** "Are you sure?" with the SQL that will run, when there is one. */
@Composable
fun ConfirmSheet(
    backdrop: Backdrop,
    title: String,
    message: String? = null,
    sql: String? = null,
    confirm: String,
    danger: Boolean = false,
    busy: Boolean = false,
    problem: Throwable? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Sheet(backdrop, onDismiss) {
        Text(title, style = Type.Title)
        if (message != null) Text(message, style = Type.Secondary, color = Dby.Secondary)
        if (sql != null) SqlBox(sql, header = "SQL to run")
        if (problem != null) ProblemBanner(problem, modifier = Modifier.padding(horizontal = 0.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("Cancel", onDismiss, Modifier.weight(1f))
            if (danger) {
                SecondaryButton(confirm, onConfirm, Modifier.weight(1.4f), tint = Dby.Danger, busy = busy)
            } else {
                PrimaryButton(confirm, onConfirm, Modifier.weight(1.4f), busy = busy)
            }
        }
    }
}

/** Asks for a connection's password when none is saved or it can no longer be read. */
@Composable
fun PasswordSheet(backdrop: Backdrop, name: String, onSubmit: (password: String, remember: Boolean) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(true) }
    Sheet(backdrop, onDismiss) {
        Text("Password for $name", style = Type.Title)
        Text("No saved password can be used. Enter it to connect.", style = Type.Secondary, color = Dby.Secondary)
        Column(Modifier.fillMaxWidth().lightGlass(RoundedCornerShape(22.dp))) {
            FieldRow("Password", password, { password = it }, keyboard = KeyboardType.Password, secret = true)
            Hairline()
            ToggleRow("Remember it", "Saved encrypted on this phone.", remember, { remember = it })
        }
        PrimaryButton("Connect", { onSubmit(password, remember) }, Modifier.fillMaxWidth())
    }
}

fun copyText(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}

/** A light tap of feedback on touch, unless Haptics is off in Settings. */
@Composable
fun rememberHaptics(): (HapticFeedbackType) -> Unit {
    val haptic = LocalHapticFeedback.current
    return remember(haptic) { { type -> if (DbyApp.instance.prefs.haptics) haptic.performHapticFeedback(type) } }
}
