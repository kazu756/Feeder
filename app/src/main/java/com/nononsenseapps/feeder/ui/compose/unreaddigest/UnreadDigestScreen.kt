package com.nononsenseapps.feeder.ui.compose.unreaddigest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nononsenseapps.feeder.R
import com.nononsenseapps.feeder.ui.compose.theme.SensibleTopAppBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnreadDigestScreen(
    onNavigateUp: () -> Unit,
    onOpenArticle: (Long) -> Unit,
    viewModel: UnreadDigestViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier,
        topBar = {
            SensibleTopAppBar(
                scrollBehavior = scrollBehavior,
                title = stringResource(R.string.unread_ai_digest),
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.go_back))
                    }
                },
            )
        },
    ) { padding ->
        when (val current = state) {
            UnreadDigestState.Idle, UnreadDigestState.Loading ->
                CenteredContent(padding) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.creating_unread_digest))
                }
            UnreadDigestState.Empty ->
                CenteredContent(padding) { Text(stringResource(R.string.no_unread_articles)) }
            is UnreadDigestState.Error ->
                CenteredContent(padding) {
                    Text(current.message.ifBlank { stringResource(R.string.something_went_wrong) })
                    Button(onClick = viewModel::generate) { Text(stringResource(R.string.retry)) }
                }
            is UnreadDigestState.Success ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding =
                        PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = padding.calculateTopPadding() + 16.dp,
                            bottom = padding.calculateBottomPadding() + 16.dp,
                        ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(current.content) { item ->
                        when (item) {
                            is UnreadDigestDisplayContent.Paragraph -> Text(item.value)
                            is UnreadDigestDisplayContent.ArticleLink ->
                                TextButton(
                                    onClick = { onOpenArticle(item.value.feedItemId) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(stringResource(R.string.open_article), modifier = Modifier.fillMaxWidth())
                                }
                        }
                    }
                }
        }
    }
}

@Composable
private fun CenteredContent(
    padding: PaddingValues,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}
