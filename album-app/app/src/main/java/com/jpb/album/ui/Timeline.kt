package com.jpb.album.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.jpb.album.data.Photo
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRANCE)

/**
 * Vue chronologique : les photos sont regroupées par jour, avec un en-tête par jour.
 * [photos] doit être trié du plus récent au plus ancien.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Timeline(
    photos: List<Photo>,
    onPhotoClick: (Photo) -> Unit = {},
) {
    // groupBy conserve l'ordre d'insertion, donc l'ordre récent -> ancien est préservé.
    val byDay = remember(photos) { photos.groupBy { it.day } }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(2.dp),
    ) {
        byDay.forEach { (day, dayPhotos) ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    text = day.format(dayFormatter).replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                )
            }

            items(dayPhotos, key = { it.id }) { photo ->
                AsyncImage(
                    model = photo.uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .aspectRatio(1f)
                        .padding(1.dp)
                        .clickable { onPhotoClick(photo) },
                )
            }
        }
    }
}
