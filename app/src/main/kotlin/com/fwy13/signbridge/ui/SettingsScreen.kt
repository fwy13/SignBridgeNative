package com.fwy13.signbridge.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fwy13.signbridge.*
import com.fwy13.signbridge.data.*

@Composable
fun PronounPicker(
    current: Pronouns,
    onChoose: (Pronouns) -> Unit,
    onDismiss: () -> Unit,
    allowDefault: Boolean = false,
    onDefault: () -> Unit = {}
) {
    var custom by remember { mutableStateOf(false) }
    var speaker by remember { mutableStateOf(if (current.id == "custom") current.speaker else "") }
    var listener by remember { mutableStateOf(if (current.id == "custom") current.listener else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(if (custom) "settings.customPronounsTitle" else "translation.choosePronouns")) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (custom) {
                    Text(tr("settings.customPronounsDescription"))
                    OutlinedTextField(
                        speaker,
                        { speaker = it },
                        label = { Text(tr("settings.speakerPronoun")) })
                    OutlinedTextField(
                        listener,
                        { listener = it },
                        label = { Text(tr("settings.listenerPronoun")) })
                } else {
                    if (allowDefault) TextButton(onClick = onDefault) { Text(tr("translation.useDefaultPronouns")) }
                    pronounPresets.forEach { p ->
                        TextButton(
                            onClick = { onChoose(p) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(if (p.addSubject) p.label else tr("settings.noAutomaticSubject")) }
                    }
                    TextButton(onClick = { custom = true }) { Text(tr("settings.customPronouns")) }
                }
            }
        },
        confirmButton = {
            if (custom) TextButton(
                onClick = {
                    onChoose(
                        Pronouns(
                            "custom",
                            speaker.trim(),
                            listener.trim()
                        )
                    )
                },
                enabled = speaker.isNotBlank() && listener.isNotBlank()
            ) { Text(tr("settings.save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("settings.cancel")) } })
}

@Composable
fun SettingsScreen(vm: BridgeViewModel) {
    val settings by vm.preferences.state.collectAsStateWithLifecycle()
    val profile by vm.account.profile.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf(false) }
    ScreenColumn {
        Brand(centered = true)
        Section(
            profile.optJSONObject("user_metadata")?.optString("full_name")
                ?.takeIf(String::isNotBlank) ?: bilingual("Người dùng", "User")
        ) { Text(profile.optString("email")) }
        Section(tr("settings.appLanguage")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "vi" to "Tiếng Việt",
                    "en" to "English"
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = settings.language == value,
                        onClick = { vm.preferences.update(settings.copy(language = value)) },
                        label = { Text(label) })
                }
            }
        }
        Section(tr("settings.signLanguage")) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("hoa_de" to "Hòa Đê", "vsl" to "VSL").forEach { (value, label) ->
                    FilterChip(
                        selected = settings.region == value,
                        onClick = { vm.region(value) },
                        label = { Text(label) },
                        enabled = !vm.busy
                    )
                }
            }
        }
        Section(tr("settings.pronouns")) {
            TextButton(onClick = {
                picker = true
            }) { Text(if (settings.pronouns.addSubject) settings.pronouns.label else tr("settings.noAutomaticSubject")) }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(tr("settings.darkMode"), Modifier.weight(1f)); Switch(
            checked = settings.dark,
            onCheckedChange = { vm.preferences.update(settings.copy(dark = it)) })
        }
        FilledTonalButton(
            onClick = { vm.page = Page.Models },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                Icons.Default.Memory,
                null
            ); Spacer(Modifier.width(8.dp)); Text(
            bilingual(
                "Mô hình trên thiết bị",
                "Local models"
            )
        )
        }
        OutlinedButton(
            onClick = vm::logout,
            modifier = Modifier.fillMaxWidth(),
            enabled = !vm.busy
        ) { Text(tr("settings.logout")) }
        Text(tr("settings.version"), style = MaterialTheme.typography.labelMedium)
    }
    if (picker) PronounPicker(settings.pronouns,
        { vm.preferences.update(settings.copy(pronouns = it)); picker = false },
        { picker = false })
}
