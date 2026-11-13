package com.yomu.reader.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yomu.reader.source.FilterGroup
import com.yomu.reader.source.FilterState

/** Panneau des filtres du catalogue (s'ouvre par le bas). */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    groups: List<FilterGroup>,
    initial: FilterState,
    onApply: (FilterState) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    fun valueOf(group: String, option: String): Int = draft[group]?.get(option) ?: 0

    fun set(group: FilterGroup, option: String, value: Int) {
        val current = draft[group.key].orEmpty()
        val updated = when {
            !group.multi -> if (value == 0) emptyMap() else mapOf(option to value)
            value == 0 -> current - option
            else -> current + (option to value)
        }
        draft = draft + (group.key to updated)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Filtres", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { draft = emptyMap() }) { Text("Réinitialiser") }
                Button(onClick = { onApply(draft.filterValues { it.isNotEmpty() }) }) { Text("Appliquer") }
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                groups.forEach { group ->
                    Spacer(Modifier.height(12.dp))
                    Text(group.label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        group.options.forEach { option ->
                            val v = valueOf(group.key, option.value)
                            // Pour un choix unique, l'option « vide » (Tous) est sélectionnée par défaut.
                            val selected = v != 0 || (!group.multi && option.value.isEmpty() && draft[group.key].isNullOrEmpty())
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    val next = when {
                                        group.triState -> when (v) { 0 -> 1; 1 -> -1; else -> 0 }
                                        option.value.isEmpty() -> 0
                                        else -> if (v == 0) 1 else 0
                                    }
                                    set(group, option.value, next)
                                },
                                label = {
                                    Text(
                                        when (v) {
                                            1 -> if (group.triState) "✓ ${option.label}" else option.label
                                            -1 -> "✕ ${option.label}"
                                            else -> option.label
                                        }
                                    )
                                },
                                colors = if (v == -1) {
                                    FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.errorContainer,
                                        selectedLabelColor = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                } else {
                                    FilterChipDefaults.filterChipColors()
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}
