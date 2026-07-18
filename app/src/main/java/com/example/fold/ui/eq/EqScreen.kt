package com.example.fold.ui.eq

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.example.fold.R
import com.example.fold.audio.DspEngine
import com.example.fold.audio.EqManager

@Composable
private fun getPresetName(key: String): String {
    val resId = when (key) {
        "flat" -> R.string.eq_preset_flat
        "bass_boost" -> R.string.eq_preset_bass_boost
        "treble_boost" -> R.string.eq_preset_treble_boost
        "vocal" -> R.string.eq_preset_vocal
        "rock" -> R.string.eq_preset_rock
        "pop" -> R.string.eq_preset_pop
        "jazz" -> R.string.eq_preset_jazz
        "classical" -> R.string.eq_preset_classical
        "electronic" -> R.string.eq_preset_electronic
        else -> R.string.eq_custom
    }
    return stringResource(resId)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqScreen(onBack: () -> Unit) {
    val state by EqManager.state.collectAsState()
    var showPresets by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // TopAppBar（只避开状态栏）
        TopAppBar(
            title = { Text(stringResource(R.string.eq_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.action_back))
                }
            },
            actions = {
                TextButton(onClick = {
                    EqManager.setBandGain(0, 12f)
                    EqManager.setBandGain(1, 10f)
                }) {
                    Text(stringResource(R.string.eq_test), fontSize = 13.sp)
                }
                TextButton(onClick = { showPresets = true }) {
                    Text(getPresetName(state.currentPreset), fontSize = 13.sp)
                }
                IconButton(onClick = { EqManager.resetToFlat() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.eq_reset))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        )

        // 内容（延伸到小白条下方）
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Enable toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.eq_enabled),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Switch(
                    checked = state.isEnabled,
                    onCheckedChange = { EqManager.setEnabled(it) }
                )
            }

            Spacer(Modifier.height(8.dp))

            // Bass slider
            EqSlider(
                label = stringResource(R.string.eq_bass),
                value = state.bassDb,
                valueRange = -12f..12f,
                onValueChange = { EqManager.setBassDb(it) }
            )

            Spacer(Modifier.height(16.dp))

            // 10-band EQ sliders
            Text(
                stringResource(R.string.eq_bands),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            )

            for (i in 0 until DspEngine.EQ_BAND_COUNT) {
                EqSlider(
                    label = formatFrequency(DspEngine.CENTER_FREQUENCIES[i]),
                    value = state.bandGains[i],
                    valueRange = -12f..12f,
                    onValueChange = { EqManager.setBandGain(i, it) }
                )
            }

            Spacer(Modifier.height(16.dp))

            // Treble slider
            EqSlider(
                label = stringResource(R.string.eq_treble),
                value = state.trebleDb,
                valueRange = -12f..12f,
                onValueChange = { EqManager.setTrebleDb(it) }
            )

            Spacer(Modifier.height(24.dp))

            // Status indicator
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (state.isEnabled)
                        MaterialTheme.colorScheme.primaryContainer
                    else
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (state.isEnabled) stringResource(R.string.eq_status_active)
                               else stringResource(R.string.eq_status_inactive),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (state.isEnabled)
                            MaterialTheme.colorScheme.onPrimaryContainer
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (state.isEnabled && state.currentPreset != "custom") {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(R.string.eq_current_preset, getPresetName(state.currentPreset)),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.isEnabled)
                                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            else
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // Preset dialog
    if (showPresets) {
        PresetDialog(
            currentPreset = state.currentPreset,
            onSelect = { EqManager.applyPreset(it); showPresets = false },
            onDismiss = { showPresets = false }
        )
    }
}

@Composable
private fun EqSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var lastStep by remember { mutableFloatStateOf(value) }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(56.dp),
            textAlign = TextAlign.End
        )
        Slider(
            value = value,
            onValueChange = { newValue ->
                val newStep = (newValue * 2).toInt() / 2f  // 0.5dB 步进
                if (newStep != lastStep) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    lastStep = newStep
                }
                onValueChange(newValue)
            },
            valueRange = valueRange,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            steps = 23  // 0.5 dB steps: -12 to +12 = 24 steps
        )
        Text(
            text = formatDb(value),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(40.dp),
            textAlign = TextAlign.Start
        )
    }
}

@Composable
private fun PresetDialog(
    currentPreset: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(R.string.eq_preset_title),
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        },
        text = {
            Column {
                EqManager.presets.keys.forEach { name ->
                    TextButton(
                        onClick = { onSelect(name) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            getPresetName(name),
                            color = if (name == currentPreset)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Start
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {}
    )
}

private fun formatFrequency(hz: Int): String {
    return if (hz >= 1000) "${hz / 1000}k" else "$hz"
}

private fun formatDb(db: Float): String {
    return if (db >= 0) "+%.1f".format(db) else "%.1f".format(db)
}
