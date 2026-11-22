package com.dozy.pocketai.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Building blocks of the Winteria / Pulse phone look.

const val TOUCH = 44

/** Header bar: bar fill, big bold title, 1px outline line underneath. */
@Composable
fun PulseHeader(
    title: String,
    subtitle: String? = null,
    leading: (@Composable RowScope.() -> Unit)? = null,
    onTitleClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val p = LocalPulse.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(p.bar)
            .statusBarsPadding()
            .drawBehind {
                drawLine(p.outline, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            }
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .heightIn(min = TOUCH.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        leading?.invoke(this)
        Column(
            Modifier.weight(1f).then(
                if (onTitleClick != null) Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onTitleClick) else Modifier,
            ),
        ) {
            Text(title, color = p.ink, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(
                subtitle + if (onTitleClick != null) "  ▾" else "",
                color = p.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

/** Round icon pill: accent (primary) or neutral card with outline. */
@Composable
fun PillIcon(
    icon: ImageVector,
    contentDescription: String?,
    primary: Boolean = false,
    active: Boolean = false,
    size: Dp = TOUCH.dp,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val p = LocalPulse.current
    val fill = when {
        primary -> p.accent
        active -> p.selected
        else -> p.card
    }
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (enabled) fill else fill.copy(alpha = 0.4f))
            .then(if (!primary) Modifier.border(1.dp, if (active) p.accent else p.gridLine, CircleShape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = if (primary) p.onAccent else p.ink, modifier = Modifier.size(size * 0.5f))
    }
}

/** Pulse button: accent pill (primary) or neutral pill. */
@Composable
fun PillButton(
    text: String,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val p = LocalPulse.current
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .height(40.dp)
            .clip(shape)
            .background(if (primary) p.accent else p.card)
            .then(if (!primary) Modifier.border(1.dp, p.gridLine, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        val c = (if (primary) p.onAccent else p.ink).let { if (enabled) it else it.copy(alpha = 0.4f) }
        if (icon != null) {
            Icon(icon, null, tint = c, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = c, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Section heading: small, bold, upper-case, muted. */
@Composable
fun SectionHeading(text: String, modifier: Modifier = Modifier) {
    val p = LocalPulse.current
    Text(
        text.uppercase(), color = p.muted, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp, modifier = modifier.padding(top = 8.dp, bottom = 6.dp, start = 4.dp),
    )
}

/** Card: card fill, 16dp corners, 1px grid-line outline. */
@Composable
fun PulseCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    radius: Dp = 16.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = LocalPulse.current
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .clip(shape)
            .background(if (selected) p.selected else p.card)
            .border(1.dp, if (selected) p.accent.copy(alpha = 0.6f) else p.gridLine, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(14.dp),
        content = content,
    )
}

/** List row: coloured round badge with a letter, title, subtitle and a right-hand note. */
@Composable
fun ListRow(
    badge: Color,
    letter: String,
    title: String,
    sub: String = "",
    note: String = "",
    selected: Boolean = false,
    onClick: () -> Unit,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val p = LocalPulse.current
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(if (selected) p.selected else p.card)
            .then(if (!selected) Modifier.border(1.dp, p.gridLine, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).clip(CircleShape).background(badge.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
            Text(letter, color = if (p.dark) Color.White else Color(0xFF172227), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(11.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = p.ink, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub.isNotEmpty()) Text(sub, color = p.muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (note.isNotEmpty()) Text(note, color = p.muted, fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp))
        trailing()
    }
}

/** Drawer line: icon + label, the active one on a filled pill. */
@Composable
fun DrawerRow(icon: ImageVector, label: String, active: Boolean = false, onClick: () -> Unit, trailing: @Composable RowScope.() -> Unit = {}) {
    val p = LocalPulse.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) p.selected else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = p.ink, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, color = p.ink, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** Segmented pills (one selected). */
@Composable
fun Segmented(items: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEachIndexed { i, s -> PillButton(s, primary = i == selected) { onSelect(i) } }
    }
}

/** Bottom dock in the Pulse style: icon above label, active one on a selected pill. */
@Composable
fun DockItem(icon: ImageVector, label: String, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPulse.current
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .width(56.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(50))
                .background(if (active) p.selected else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, label, tint = p.ink, modifier = Modifier.size(22.dp)) }
        Text(label, color = if (active) p.ink else p.muted, fontSize = 11.sp, fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
    }
}
