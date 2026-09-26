package us.wangxy.voicebook.screens.list

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import us.wangxy.voicebook.theme.AppSkin
import us.wangxy.voicebook.theme.LocalThemeController
import us.wangxy.voicebook.theme.ThemeMode
import us.wangxy.voicebook.theme.label
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import us.wangxy.voicebook.data.MuseumObject
import us.wangxy.voicebook.screens.EmptyScreenContent
import us.wangxy.voicebook.screens.placeholderColor
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ListScreen(
    navigateToDetails: (objectId: Int) -> Unit,
    navigateToVoice: () -> Unit,
    navigateToLibrary: () -> Unit = {},
) {
    val viewModel = koinViewModel<ListViewModel>()
    val objects by viewModel.objects.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        ThemeBar()
        Box(Modifier.fillMaxSize()) {
        AnimatedContent(objects.isNotEmpty()) { objectsAvailable ->
            if (objectsAvailable) {
                ObjectGrid(
                    objects = objects,
                    onObjectClick = navigateToDetails,
                )
            } else {
                EmptyScreenContent(Modifier.fillMaxSize())
            }
        }
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExtendedFloatingActionButton(
                onClick = navigateToLibrary,
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            ) {
                Text("书城")
            }
            ExtendedFloatingActionButton(
                onClick = navigateToVoice,
            ) {
                Text("语音")
            }
        }
        }
    }
}

@Composable
private fun ThemeBar() {
    val controller = LocalThemeController.current
    val preference by controller.preference.collectAsStateWithLifecycle()
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ChipRow {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = preference.mode == mode,
                    onClick = { controller.setMode(mode) },
                    label = { Text(mode.label) },
                )
            }
        }
        ChipRow {
            AppSkin.entries.forEach { skin ->
                FilterChip(
                    selected = preference.skin == skin,
                    onClick = { controller.setSkin(skin) },
                    label = { Text(skin.label) },
                )
            }
        }
    }
}

@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = { content() },
    )
}

@Composable
private fun ObjectGrid(
    objects: List<MuseumObject>,
    onObjectClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(180.dp),
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 88.dp),
    ) {
        items(objects, key = { it.objectID }) { obj ->
            ObjectFrame(
                obj = obj,
                onClick = { onObjectClick(obj.objectID) },
            )
        }
    }
}

@Composable
private fun ObjectFrame(
    obj: MuseumObject,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .padding(8.dp)
            .clickable { onClick() }
    ) {
        AsyncImage(
            model = obj.primaryImageSmall,
            contentDescription = obj.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.medium)
                .background(placeholderColor(obj.objectID)),
        )

        Spacer(Modifier.height(2.dp))

        Text(obj.title, style = MaterialTheme.typography.titleMedium)
        Text(obj.artistDisplayName, style = MaterialTheme.typography.bodyMedium)
        Text(obj.objectDate, style = MaterialTheme.typography.bodySmall)
    }
}
