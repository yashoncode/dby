package com.dby.mobile.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dby.mobile.data.bytes
import com.dby.mobile.ui.common.PrimaryButton
import com.dby.mobile.ui.common.SecondaryButton
import com.dby.mobile.ui.common.Sheet
import com.dby.mobile.ui.theme.Dby
import com.dby.mobile.ui.theme.LocalAccent
import com.dby.mobile.ui.theme.Type
import com.dby.mobile.update.Release
import com.dby.mobile.update.Updater
import com.kyant.backdrop.Backdrop

/** Settings' Update sheet: version, size, notes, then Update / Cancel / Install (spec §10). */
@Composable
fun UpdateSheet(backdrop: Backdrop, updater: Updater, onDismiss: () -> Unit) {
    val state = updater.state
    val release: Release = when (state) {
        is Updater.State.Available -> state.release
        is Updater.State.Downloading -> state.release
        is Updater.State.Ready -> state.release
        is Updater.State.Failed -> state.release
        else -> null
    } ?: return onDismiss()
    Sheet(backdrop, onDismiss) {
        Text("DBY ${release.versionName}", style = Type.Title)
        Text(bytes(release.size), style = Type.Secondary, color = Dby.Secondary)
        release.notes.forEach { Text("•  $it", style = Type.Body) }
        when (state) {
            is Updater.State.Downloading -> {
                LinearProgressIndicator({ state.fraction }, Modifier.fillMaxWidth(), color = LocalAccent.current)
                SecondaryButton("Cancel", updater::cancel, Modifier.fillMaxWidth())
            }
            is Updater.State.Ready -> {
                state.message?.let { Text(it, style = Type.Secondary, color = Dby.Danger) }
                PrimaryButton("Install", { updater.install(state.file) }, Modifier.fillMaxWidth())
            }
            else -> {
                if (state is Updater.State.Failed) Text(state.message, style = Type.Secondary, color = Dby.Danger)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton("Later", onDismiss, Modifier.weight(1f))
                    PrimaryButton("Update", { updater.download(release) }, Modifier.weight(1.4f))
                }
            }
        }
    }
}
