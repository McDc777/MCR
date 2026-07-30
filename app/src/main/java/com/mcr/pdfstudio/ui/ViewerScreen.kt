package com.mcr.pdfstudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * Continuous vertical reader.
 *
 * Scrolling is the primary gesture here, so zoom lives in a separate full-screen
 * view opened by tapping a page. Trying to do both in one surface makes pinch
 * and scroll fight each other.
 */
@Composable
fun ViewerScreen(vm: EditorViewModel, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    var zoomPage by remember { mutableStateOf<Int?>(null) }

    // Keep the toolbar's page counter in step with what is on screen.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { index ->
                if (index in 0 until vm.pageCount) vm.currentPage = index
            }
    }

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 10.dp, vertical = 12.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            items(vm.pageCount) { index ->
                Card(
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { zoomPage = index }
                ) {
                    Column {
                        PageImage(vm, index, Modifier.fillMaxWidth())
                        Text(
                            "Page ${index + 1} of ${vm.pageCount}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }

        // Floating rotate controls for the page currently in view.
        Surface(
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 4.dp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.rotatePage(-90) }) {
                    Icon(Icons.Filled.RotateLeft, contentDescription = "Rotate left")
                }
                IconButton(onClick = { vm.rotatePage(90) }) {
                    Icon(Icons.Filled.RotateRight, contentDescription = "Rotate right")
                }
            }
        }
    }

    zoomPage?.let { index ->
        Dialog(onDismissRequest = { zoomPage = null }) {
            Surface(color = Color.Black) {
                Box(Modifier.fillMaxSize()) {
                    ZoomablePage(vm, index, Modifier.fillMaxSize())
                    IconButton(
                        onClick = { zoomPage = null },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close",
                            tint = Color.White
                        )
                    }
                    Text(
                        "Page ${index + 1} · pinch to zoom",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(16.dp)
                    )
                }
            }
        }
    }
}
