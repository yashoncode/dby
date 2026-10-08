package com.dby.mobile.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dby.mobile.DbyApp
import com.dby.mobile.topSpace
import com.dby.mobile.ui.common.GroupCard
import com.dby.mobile.ui.common.Hairline
import com.dby.mobile.ui.common.LargeTitle
import com.dby.mobile.ui.common.ListRow
import com.dby.mobile.ui.common.SectionHeader
import com.dby.mobile.ui.common.TopBar
import com.dby.mobile.ui.glass.GlassHost
import com.dby.mobile.ui.theme.Dby
import com.dby.mobile.ui.theme.Type

/** Third-party components and their licences (spec §15). */
private val COMPONENTS = listOf(
    "Jetpack Compose, AndroidX" to "Apache-2.0",
    "Kotlin coroutines" to "Apache-2.0",
    "Kyant0 backdrop and shapes" to "Apache-2.0",
    "JNA" to "Apache-2.0",
    "UniFFI" to "MPL-2.0",
    "mysql_async, mysql_common" to "MIT / Apache-2.0",
    "rustls, ring, webpki-roots" to "Apache-2.0 / ISC / MPL-2.0",
    "tokio, futures" to "MIT",
    "rusqlite, SQLite" to "MIT / public domain",
    "sqlparser" to "Apache-2.0",
    "serde, serde_json, thiserror" to "MIT / Apache-2.0",
    "Amazon RDS CA bundle" to "Amazon",
    "t8y2/dbx (type mapping ideas)" to "Apache-2.0",
    "Geist, Geist Mono" to "SIL Open Font License 1.1",
)

@Composable
fun LicencesScreen(app: DbyApp) {
    val context = LocalContext.current
    val ofl = remember { context.assets.open("licences/OFL-Geist.txt").bufferedReader().use { it.readText() } }
    GlassHost {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = topSpace()).navigationBarsPadding().padding(bottom = 24.dp)) {
            TopBar(onBack = { app.nav.back() })
            LargeTitle("Licences", Modifier.padding(top = 8.dp))
            SectionHeader("Components")
            GroupCard {
                COMPONENTS.forEachIndexed { i, (name, licence) ->
                    if (i > 0) Hairline()
                    ListRow(name, subtitle = licence, trailing = {})
                }
            }
            SectionHeader("Geist fonts")
            Text(ofl, style = Type.MonoSmall, color = Dby.Secondary, modifier = Modifier.padding(horizontal = 20.dp))
        }
    }
}
