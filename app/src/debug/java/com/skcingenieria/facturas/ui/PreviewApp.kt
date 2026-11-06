package com.skcingenieria.facturas.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.skcingenieria.facturas.data.ROLE_ADMIN
import com.skcingenieria.facturas.data.UserProfile
import com.skcingenieria.facturas.repository.BalanceRow
import com.skcingenieria.facturas.ui.theme.SkcPalette
import com.skcingenieria.facturas.ui.theme.SkcTheme

private val previewUsers = listOf(
    UserProfile("dalton", "Dalton"),
    UserProfile("evelyn", "Evelyn"),
    UserProfile("javier", "Javier"),
    UserProfile("karen", "Karen"),
    UserProfile("administracion", "Administración", "administracion@gmail.skc", ROLE_ADMIN, "preview-auth-admin")
)

private val previewRows = listOf(
    BalanceRow(previewUsers[0], 0.0, 0.0, 0),
    BalanceRow(previewUsers[1], 30.0, 30.0, 0),
    BalanceRow(previewUsers[2], -49.0, -49.0, 1),
    BalanceRow(previewUsers[3], 10.0, 10.0, 0)
)

@Preview(name = "SKC Facturas 3.13.3", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun SkcInteractivePreview() {
    SkcTheme {
        var screen by remember { mutableStateOf(Screen.HOME) }
        Scaffold(
            containerColor = SkcPalette.Canvas,
            topBar = { SkcTopBar("Administración", "administracion@gmail.skc", false) {} }
        ) { padding ->
            Box(Modifier.padding(padding)) {
                when (screen) {
                    Screen.HOME -> PreviewHome { screen = it }
                    Screen.INVOICE -> SimplePreviewPage("Subir factura")
                    Screen.HISTORY -> HistoryPreview()
                    Screen.MESSAGES -> MessagesPreview()
                    Screen.REMINDERS -> RemindersPreview()
                    Screen.SETTINGS -> SettingsPreview()
                }
            }
        }
    }
}

@Preview(name = "Inicio 3.13.3", showBackground = true, widthDp = 390, heightDp = 844)
@Composable
fun HomePreview() { SkcTheme { PreviewHome {} } }

@Composable
private fun PreviewHome(onNav: (Screen) -> Unit) {
    MobilePage("Inicio") {
        ModuleGrid(
            listOf(
                ModuleAction("▤", "Subir factura", onClick = { onNav(Screen.INVOICE) }),
                ModuleAction("✉", "Mensajes", onClick = { onNav(Screen.MESSAGES) }, tone = "green"),
                ModuleAction("⏰", "Recordatorios", onClick = { onNav(Screen.REMINDERS) }, tone = "orange"),
                ModuleAction("▦", "Historial", onClick = { onNav(Screen.HISTORY) }, tone = "green"),
                ModuleAction("⚙", "Configuración", onClick = { onNav(Screen.SETTINGS) })
            )
        )
        BalanceLiveCard(previewRows, "Flujo")
        MobileCard {
            Button({}, Modifier.fillMaxWidth()) { Text("Descargar Excel de Facturas") }
            OutlinedButton({}, Modifier.fillMaxWidth()) { Text("Descargar Excel de Transferencias") }
        }
    }
}

@Composable
private fun HistoryPreview() {
    MobilePage("Historial") {
        MobileCard {
            Text("Factura")
            Text("$ 125,00")
        }
        MobileCard {
            Text("Transferencia")
            Text("$ 30,00")
        }
    }
}

@Composable
private fun MessagesPreview() {
    MobilePage("Mensajes") {
        Text("Contactos", style = MaterialTheme.typography.titleLarge)
        previewUsers.take(4).forEach { user ->
            MobileCard { Text(user.name) }
        }
        Button({}, Modifier.fillMaxWidth()) { Text("Solicitar transferencia") }
        OutlinedButton({}, Modifier.fillMaxWidth()) { Text("Enviar transferencia") }
    }
}

@Composable
private fun RemindersPreview() {
    MobilePage("Recordatorios") {
        MobileCard { Text("Comprar cable y conectores") }
        OutlinedTextField("", {}, label = { Text("Motivo de cancelación (opcional)") }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SettingsPreview() {
    MobilePage("Configuración") {
        MobileTabs(listOf("Cuenta", "Usuarios", "Formatos personalizados", "Nube y respaldo"), "Cuenta") {}
        MobileCard {
            Text("Usted ha iniciado (administracion@gmail.skc).")
            OutlinedButton({}, Modifier.fillMaxWidth()) { Text("Cerrar sesión") }
        }
    }
}

@Composable
private fun SimplePreviewPage(title: String) {
    MobilePage(title) { MobileCard { Spacer(Modifier.height(80.dp)) } }
}
