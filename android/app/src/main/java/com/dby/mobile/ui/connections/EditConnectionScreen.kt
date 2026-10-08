package com.dby.mobile.ui.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.dby.core.ConnectParams
import com.dby.core.Env
import com.dby.core.TlsMode
import com.dby.core.openSession
import com.dby.mobile.DbyApp
import com.dby.mobile.data.ConnectionForm
import com.dby.mobile.data.Sessions
import com.dby.mobile.topSpace
import com.dby.mobile.ui.common.ConfirmSheet
import com.dby.mobile.ui.common.FieldRow
import com.dby.mobile.ui.common.GroupCard
import com.dby.mobile.ui.common.Hairline
import com.dby.mobile.ui.common.LargeTitle
import com.dby.mobile.ui.common.PrimaryButton
import com.dby.mobile.ui.common.ProblemBanner
import com.dby.mobile.ui.common.SecondaryButton
import com.dby.mobile.ui.common.SectionHeader
import com.dby.mobile.ui.common.Segmented
import com.dby.mobile.ui.common.TopBar
import com.dby.mobile.ui.glass.GlassHost
import com.dby.mobile.ui.nav.Screen
import com.dby.mobile.ui.nav.ScreenModel
import com.dby.mobile.ui.theme.Dby
import com.dby.mobile.ui.theme.Type
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class EditConnectionModel(private val id: String?, private val sessions: Sessions) : ScreenModel() {
    private val existing = id?.let(sessions::saved)
    val isNew = existing == null
    var name by mutableStateOf(existing?.name ?: "")
    var host by mutableStateOf(existing?.host ?: "")
    var port by mutableStateOf(existing?.port?.toString() ?: "3306")
    var user by mutableStateOf(existing?.user ?: "")
    var password by mutableStateOf("")
    var database by mutableStateOf(existing?.database ?: "")
    var env by mutableStateOf(existing?.env ?: Env.DEV)
    var tls by mutableStateOf(existing?.tls ?: TlsMode.VERIFY)
    var busy by mutableStateOf(false)
    var tested by mutableStateOf<String?>(null)
    var problem by mutableStateOf<Throwable?>(null)
    var deleting by mutableStateOf(false)

    val valid: Boolean
        get() = name.isNotBlank() && host.isNotBlank() && user.isNotBlank() && port.toIntOrNull() in 1..65535

    fun test() {
        scope.launch {
            busy = true
            tested = null
            problem = null
            try {
                val pw = password.ifEmpty { existing?.let { sessions.savedPassword(it) } ?: "" }
                val session = openSession(ConnectParams(host.trim(), port.toInt().toUShort(), user, pw, database.trim(), tls))
                val info = session.serverInfo()
                tested = "Connected · ${info.version} · ${info.connectMs} ms"
                session.disconnect()
                session.close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e
            } finally {
                busy = false
            }
        }
    }

    fun save(done: () -> Unit) {
        scope.launch {
            busy = true
            problem = null
            try {
                val form = ConnectionForm(name, host, port.toInt(), user, database, env, tls)
                sessions.save(id, form, password.takeIf { it.isNotEmpty() || isNew })
                done()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problem = e
            } finally {
                busy = false
            }
        }
    }

    fun delete(done: () -> Unit) {
        val target = id ?: return
        scope.launch {
            sessions.delete(target)
            done()
        }
    }
}

private fun TlsMode.label() = when (this) {
    TlsMode.VERIFY -> "Verify"
    TlsMode.ENCRYPT_ONLY -> "Encrypt only"
    TlsMode.OFF -> "Off"
}

private fun TlsMode.explain() = when (this) {
    TlsMode.VERIFY -> "Encrypted, and the server's certificate is checked (Amazon RDS certificates included)."
    TlsMode.ENCRYPT_ONLY -> "Encrypted, not verified: anyone on the network path could pretend to be the server."
    TlsMode.OFF -> "Not encrypted: the password and data cross the network in the clear."
}

@Composable
fun EditConnectionScreen(app: DbyApp, id: String?) {
    val nav = app.nav
    val model = nav.model(Screen.EditConnection(id)) { EditConnectionModel(id, app.sessions) }
    GlassHost(
        overlay = { backdrop ->
            if (model.deleting) {
                ConfirmSheet(
                    backdrop,
                    title = "Delete ${model.name}?",
                    message = "Its saved password and cached schema are removed from this phone.",
                    confirm = "Delete",
                    danger = true,
                    onConfirm = { model.delete { model.deleting = false; nav.back() } },
                    onDismiss = { model.deleting = false },
                )
            }
        },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = topSpace()).navigationBarsPadding().imePadding().padding(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            TopBar(onBack = { nav.back() })
            LargeTitle(if (model.isNew) "New connection" else "Edit connection", Modifier.padding(top = 8.dp, bottom = 16.dp))
            GroupCard {
                FieldRow("Name", model.name, { model.name = it }, "Orders — Production", mono = false)
                Hairline()
                FieldRow("Host", model.host, { model.host = it }, "db.example.com", KeyboardType.Uri)
                Hairline()
                FieldRow("Port", model.port, { model.port = it.filter(Char::isDigit) }, "3306", KeyboardType.Number)
                Hairline()
                FieldRow("User", model.user, { model.user = it }, "app_reader")
                Hairline()
                FieldRow("Password", model.password, { model.password = it }, if (model.isNew) "" else "Unchanged", KeyboardType.Password, secret = true)
                Hairline()
                FieldRow("Database", model.database, { model.database = it }, "shop")
            }
            SectionHeader("Environment")
            Segmented(Env.entries, model.env, { model.env = it }, { it.name }, Modifier.padding(horizontal = 16.dp).fillMaxWidth())
            Text(
                if (model.env == Env.PROD) "Opens read-only while “Read-only on PROD” is on in Settings." else "The tag colours this connection everywhere.",
                style = Type.Caption, color = Dby.Secondary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            SectionHeader("Encryption")
            Segmented(TlsMode.entries, model.tls, { model.tls = it }, { it.label() }, Modifier.padding(horizontal = 16.dp).fillMaxWidth())
            Text(
                model.tls.explain(),
                style = Type.Caption,
                color = if (model.tls == TlsMode.VERIFY) Dby.Secondary else Dby.Danger,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            model.tested?.let { Text(it, style = Type.Secondary.copy(fontWeight = FontWeight.SemiBold), color = Dby.Success, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) }
            model.problem?.let { ProblemBanner(it, modifier = Modifier.padding(vertical = 8.dp)) }
            if (!model.isNew) {
                Text(
                    "Delete connection",
                    style = Type.Body,
                    color = Dby.Danger,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(12.dp).clickable { model.deleting = true },
                )
            }
        }
        // Test and Save stay on screen above the gesture bar and the keyboard.
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Brush.verticalGradient(0f to Color.Transparent, 0.2f to Dby.Bg))
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SecondaryButton("Test", model::test, Modifier.weight(1f), enabled = model.valid, busy = model.busy)
            PrimaryButton("Save", { model.save { nav.back() } }, Modifier.weight(1.4f), enabled = model.valid && !model.busy)
        }
    }
}
