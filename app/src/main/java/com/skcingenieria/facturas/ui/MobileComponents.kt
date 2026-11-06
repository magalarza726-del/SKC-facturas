package com.skcingenieria.facturas.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.skcingenieria.facturas.repository.BalanceRow
import com.skcingenieria.facturas.ui.theme.SkcPalette
import java.text.NumberFormat
import java.util.Locale

private val MoneyFormatter = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-EC")).apply { currency = java.util.Currency.getInstance("USD") }
fun money(value: Double): String = MoneyFormatter.format(value).replace("US$", "$").replace(" ", " ")

@Composable
fun MobilePage(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SkcPalette.Canvas)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = SkcPalette.Ink)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = SkcPalette.Muted)
        }
        content()
    }
}

@Composable
fun SkcTopBar(user: String, email: String, busy: Boolean, onSync: () -> Unit) {
    Surface(
        color = Color.White,
        shadowElevation = 1.dp,
        modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
            val availableWidth = maxWidth
            val syncWidth = 80.dp
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = SkcPalette.Blue
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("SKC", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
                Spacer(Modifier.width(9.dp))
                Column(modifier = Modifier.width((availableWidth - syncWidth - 61.dp).coerceAtLeast(120.dp))) {
                    Text("SKC Ingeniería · Facturas", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (email.isNotBlank()) "${user.ifBlank { "Usuario" }} · conectado" else user.ifBlank { "Sin sesión" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = SkcPalette.Muted
                    )
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onSync,
                    enabled = !busy,
                    modifier = Modifier.width(syncWidth).heightIn(min = 38.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) { Text(if (busy) "..." else "Sincronizar", fontSize = 9.sp, maxLines = 1) }
            }
        }
    }
}

@Composable
fun MobileCard(modifier: Modifier = Modifier, tone: Color = Color.White, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = tone),
        border = BorderStroke(1.dp, SkcPalette.Line),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp), content = content) }
}

@Composable
fun ReadinessAlert(title: String, detail: String, tone: String = "warning") {
    val bg = when (tone) { "ok" -> SkcPalette.GreenBg; "error" -> SkcPalette.RedBg; else -> Color(0xFFFFFAF0) }
    val fg = when (tone) { "ok" -> SkcPalette.Green; "error" -> SkcPalette.Red; else -> SkcPalette.Orange }
    Surface(color = bg, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, fg.copy(alpha = .35f))) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(9.dp), color = fg.copy(alpha = .12f), modifier = Modifier.size(30.dp)) {
                Box(contentAlignment = Alignment.Center) { Text(if (tone == "ok") "✓" else "!", color = fg, fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.width(9.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, color = SkcPalette.Ink)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = SkcPalette.Muted)
            }
        }
    }
}

@Composable
fun BalanceLiveCard(rows: List<BalanceRow>, title: String = "Saldos · flujo actual") {
    MobileCard {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Surface(shape = RoundedCornerShape(9.dp), border = BorderStroke(1.dp, Color(0xFFE5D19B)), color = SkcPalette.YellowRow) {
            Column {
                rows.forEachIndexed { index, row ->
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("◉", color = SkcPalette.Blue, modifier = Modifier.width(25.dp), fontSize = 12.sp)
                        Text(row.user.name, modifier = Modifier.width(128.dp), style = MaterialTheme.typography.bodyMedium)
                        Column(horizontalAlignment = Alignment.End, modifier = Modifier.fillMaxWidth()) {
                            Text(money(row.balance), fontWeight = FontWeight.Bold, color = if (row.balance < 0) SkcPalette.Red else SkcPalette.Green, fontSize = 13.sp)
                            if (row.pending > 0) Text("${row.pending} pendiente(s)", fontSize = 8.sp, color = SkcPalette.Orange)
                        }
                    }
                    if (index < rows.lastIndex) HorizontalDivider(color = Color(0xFFEADCB5))
                }

            }
        }
    }
}

@Composable
fun ModuleGrid(items: List<ModuleAction>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 9.dp
        val cardWidth = (maxWidth - gap) / 2
        Column(verticalArrangement = Arrangement.spacedBy(gap)) {
            items.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    row.forEach { item -> ModuleCard(item, Modifier.width(cardWidth)) }
                    if (row.size == 1) Spacer(Modifier.width(cardWidth))
                }
            }
        }
    }
}

data class ModuleAction(val icon: String, val title: String, val detail: String = "", val onClick: () -> Unit, val tone: String = "blue")

@Composable
fun ModuleCard(item: ModuleAction, modifier: Modifier = Modifier) {
    val accent = when (item.tone) { "green" -> SkcPalette.Green; "orange" -> SkcPalette.Orange; "red" -> SkcPalette.Red; else -> SkcPalette.Blue }
    Surface(
        modifier = modifier.heightIn(min = 92.dp).clickable { item.onClick() },
        shape = RoundedCornerShape(14.dp),
        color = Color.White,
        border = BorderStroke(1.dp, SkcPalette.Line),
        shadowElevation = 2.dp
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(11.dp)) {
            val availableWidth = maxWidth
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(10.dp), color = accent.copy(alpha = .10f), modifier = Modifier.size(39.dp)) {
                    Box(contentAlignment = Alignment.Center) { Text(item.icon, color = accent, fontSize = 18.sp) }
                }
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.width((availableWidth - 58.dp).coerceAtLeast(70.dp))) {
                    Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun SecureCard(title: String, detail: String) {
    Surface(color = Color(0xFFFBFDFF), shape = RoundedCornerShape(13.dp), border = BorderStroke(1.dp, Color(0xFFCFE0F4))) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(10.dp), color = SkcPalette.BlueSoft, modifier = Modifier.size(38.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("🔒", fontSize = 16.sp) }
            }
            Spacer(Modifier.width(9.dp))
            Column {
                Text(title, color = SkcPalette.Blue, style = MaterialTheme.typography.titleSmall)
                Text(detail, color = SkcPalette.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun StatusBadge(text: String, tone: String = "blue") {
    val fg = when (tone) { "green" -> SkcPalette.Green; "red" -> SkcPalette.Red; "orange" -> SkcPalette.Orange; else -> SkcPalette.Blue }
    Surface(color = fg.copy(alpha = .09f), shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, fg.copy(alpha = .35f))) {
        Text(text, color = fg, fontSize = 8.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp), maxLines = 1)
    }
}

@Composable
fun MobileTabs(items: List<String>, selected: String, onSelected: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        items.forEach { item ->
            val active = item == selected
            Surface(
                modifier = Modifier.clickable { onSelected(item) },
                color = if (active) SkcPalette.Blue else Color.White,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, if (active) SkcPalette.Blue else SkcPalette.Line)
            ) {
                Text(item, color = if (active) Color.White else SkcPalette.Ink, modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun ThreeStats(items: List<Pair<String, String>>, tones: List<String> = listOf("blue", "green", "orange")) {
    Surface(color = Color.White, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, SkcPalette.Line), shadowElevation = 2.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(10.dp)) {
            val itemWidth = maxWidth / items.size.coerceAtLeast(1)
            Row {
                items.forEachIndexed { index, item ->
                    val fg = when (tones.getOrNull(index)) { "green" -> SkcPalette.Green; "red" -> SkcPalette.Red; "orange" -> SkcPalette.Orange; else -> SkcPalette.Blue }
                    Column(Modifier.width(itemWidth).padding(horizontal = 7.dp), horizontalAlignment = Alignment.Start) {
                        Text(item.second, color = fg, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                        Text(item.first, color = SkcPalette.Muted, fontSize = 9.sp, maxLines = 2)
                    }
                }
            }
        }
    }
}
