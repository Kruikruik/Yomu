package com.yomu.reader.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.yomu.reader.source.Source
import com.yomu.reader.source.SourceManager
import com.yomu.reader.source.langName

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(onOpen: (Source) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Explorer") }) }) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            items(SourceManager.all, key = { it.id }) { source ->
                ListItem(
                    headlineContent = { Text(source.name) },
                    supportingContent = { Text(langName(source.lang)) },
                    leadingContent = {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                source.lang.uppercase(),
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    },
                    modifier = Modifier.clickable { onOpen(source) },
                )
                HorizontalDivider()
            }
        }
    }
}
