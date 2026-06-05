package ai.elrond.ui

import ai.elrond.ElrondApplication
import ai.elrond.settings.SettingsRepository
import ai.elrond.settings.SettingsViewModel
import ai.elrond.settings.settingsViewModelFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/** Settings (debug). Currently lets the developer change the `/Q` activation command. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as ElrondApplication
    val viewModel: SettingsViewModel = viewModel(factory = settingsViewModelFactory(app.settingsRepository))
    val trigger by viewModel.triggerCommand.collectAsStateWithLifecycle()

    var draft by remember(trigger) { mutableStateOf(trigger) }
    val tooLong = draft.trim().length > SettingsRepository.MAX_TRIGGER_LENGTH
    val empty = draft.isBlank()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Debugging", style = MaterialTheme.typography.titleMedium)
            Text(
                "Activation command — the text you write on the canvas to ask the AI. " +
                    "Max ${SettingsRepository.MAX_TRIGGER_LENGTH} characters (e.g. /Q, >Q, @Q).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = draft,
                onValueChange = { new ->
                    draft = new
                    // Save immediately when valid so it takes effect on the next note opened.
                    if (new.isNotBlank() && new.trim().length <= SettingsRepository.MAX_TRIGGER_LENGTH) {
                        viewModel.setTriggerCommand(new)
                    }
                },
                singleLine = true,
                isError = tooLong || empty,
                label = { Text("Trigger command") },
                supportingText = {
                    when {
                        empty -> Text("Cannot be empty")
                        tooLong -> Text("Too long — max ${SettingsRepository.MAX_TRIGGER_LENGTH} characters")
                        else -> Text("Saved")
                    }
                },
            )
        }
    }
}
