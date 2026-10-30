package com.fwy13.signbridge.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fwy13.signbridge.BridgeViewModel
import com.fwy13.signbridge.data.LibraryCatalog
import com.fwy13.signbridge.data.LibraryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: BridgeViewModel) {
    var alphabet by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var region by rememberSaveable { mutableStateOf("Tất cả") }
    var descending by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<LibraryEntry?>(null) }
    val entries by produceState<List<LibraryEntry>>(
        emptyList(),
        alphabet,
        query,
        region,
        descending
    ) {
        value = withContext(Dispatchers.Default) {
            LibraryCatalog.filter(
                alphabet,
                query,
                region,
                descending
            )
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !alphabet,
                onClick = { alphabet = false; query = "" },
                label = { Text(tr("library.vocabulary")) })
            FilterChip(
                selected = alphabet,
                onClick = { alphabet = true; query = "" },
                label = { Text(tr("library.alphabet")) })
        }
        OutlinedTextField(
            query,
            { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(tr(if (alphabet) "library.searchAlphabet" else "library.searchVocabulary")) },
            leadingIcon = { Icon(Icons.Default.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
                    Icon(
                        Icons.Default.Close,
                        tr("library.clearSearch")
                    )
                }
            })
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = descending,
                onClick = { descending = !descending },
                label = { Text(if (descending) "Z → A" else "A → Z") })
            if (!alphabet) listOf(
                "Tất cả" to "all",
                "Bắc" to "north",
                "Trung" to "central",
                "Nam" to "south",
                "Chung" to "common"
            ).forEach { (value, label) ->
                FilterChip(
                    selected = region == value,
                    onClick = { region = value },
                    label = { Text(tr("library.regions.$label")) })
            }
        }
        Text(
            if (alphabet) tr(
                "library.alphabetCount",
                "count" to entries.size,
                "ready" to LibraryCatalog.alphabet.count { it.videoUrl != null },
                "total" to LibraryCatalog.alphabet.size
            )
            else tr("library.vocabularyCount", "count" to entries.size),
            style = MaterialTheme.typography.labelLarge
        )
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (entries.isEmpty()) item { Text(vm.texts("library.noResults")) }
            items(entries, key = { it.id }) { item ->
                OutlinedCard(onClick = { selected = item }, modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        headlineContent = { Text(item.word) },
                        supportingContent = { Text(tr(if (item.videoUrl != null) "library.available" else "library.unavailable")) },
                        trailingContent = { Icon(Icons.Default.PlayCircle, null) })
                }
            }
        }
    }
    selected?.let { entry ->
        ModalBottomSheet(
            onDismissRequest = { selected = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(entry.word, style = MaterialTheme.typography.headlineSmall)
                if (entry.videoUrl != null) VideoPlayer(
                    entry.videoUrl,
                    vm.videos,
                    Modifier
                        .fillMaxWidth()
                        .height(430.dp)
                )
                else Text(tr("library.pendingVideo", "letter" to entry.word))
                TextButton(onClick = { selected = null }) { Text(tr("conversation.close")) }
            }
        }
    }
}
