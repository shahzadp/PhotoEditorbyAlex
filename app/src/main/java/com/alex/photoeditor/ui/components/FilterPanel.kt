package com.alex.photoeditor.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.alex.photoeditor.core.FilterCategory
import com.alex.photoeditor.core.FilterEngine
import com.alex.photoeditor.core.FilterLibrary
import com.alex.photoeditor.core.PhotoFilter
import com.alex.photoeditor.ui.EditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Filter tool: category tabs with a grid of live-rendered previews. */
@Composable
fun FilterPanel(vm: EditorViewModel) {
    val groups = remember { FilterLibrary.byCategory() }
    var category by remember { mutableStateOf(FilterCategory.BASIC) }
    val filters = groups[category].orEmpty()
    val baseBitmap = vm.preview.value.original

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(FilterCategory.entries.toList()) { cat ->
                ExpressiveChip(
                    label = cat.label,
                    selected = cat == category,
                    onClick = { category = cat }
                )
            }
        }

        val listState = rememberLazyListState()
        LaunchedEffect(category) { listState.scrollToItem(0) }

        LazyColumn(
            state = listState,
            modifier = Modifier.height(220.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filters.chunked(3)) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { filter ->
                        FilterCell(
                            filter = filter,
                            base = baseBitmap,
                            isActive = filter.id == vm.activeFilterId,
                            onClick = { vm.selectFilter(filter.id) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    // Keep the last row aligned when it is not full.
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun FilterCell(
    filter: PhotoFilter,
    base: android.graphics.Bitmap?,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Thumbnails must be generated from a small source. Rendering 30 filters
    // off a full-resolution photo allocates ~30 large bitmaps and will OOM on
    // mid-range devices, so we downscale once and share it.
    val tinySource = remember(base) {
        base?.let {
            val maxEdge = 160
            val longEdge = maxOf(it.width, it.height)
            if (longEdge <= maxEdge) it
            else {
                val k = maxEdge.toFloat() / longEdge
                android.graphics.Bitmap.createScaledBitmap(
                    it,
                    (it.width * k).toInt().coerceAtLeast(1),
                    (it.height * k).toInt().coerceAtLeast(1),
                    true
                )
            }
        }
    }

    val thumb by produceState<android.graphics.Bitmap?>(
        initialValue = null, key1 = tinySource, key2 = filter.id
    ) {
        value = if (tinySource == null) null
        else withContext(Dispatchers.Default) {
            runCatching { FilterEngine().apply(tinySource, filter) }.getOrNull()
        }
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "${filter.name} filter"
                this.selected = isActive
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(
                    if (isActive) Modifier.border(
                        2.dp,
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(8.dp)
                    ) else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            val t = thumb
            if (t != null) {
                Image(
                    bitmap = t.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                CircularProgressIndicator(
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            filter.name,
            style = MaterialTheme.typography.labelSmall,
            color = if (isActive) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}
