/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package app.gyrolet.mpvrx.ui.browser.networkstreaming

import app.gyrolet.mpvrx.ui.utils.NavigationBackHandler as BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.gyrolet.mpvrx.R
import app.gyrolet.mpvrx.domain.network.NetworkConnection
import app.gyrolet.mpvrx.domain.network.NetworkFile
import app.gyrolet.mpvrx.domain.network.NetworkPath
import app.gyrolet.mpvrx.preferences.BrowserPreferences
import app.gyrolet.mpvrx.preferences.MediaLayoutMode
import app.gyrolet.mpvrx.preferences.NetworkBookmarkPreferences
import app.gyrolet.mpvrx.preferences.NetworkFolderBookmark
import app.gyrolet.mpvrx.preferences.NetworkSortType
import app.gyrolet.mpvrx.preferences.SortOrder
import app.gyrolet.mpvrx.preferences.preference.collectAsState
import app.gyrolet.mpvrx.presentation.Screen
import app.gyrolet.mpvrx.presentation.components.pullrefresh.PullRefreshBox
import app.gyrolet.mpvrx.ui.browser.cards.NetworkFolderCard
import app.gyrolet.mpvrx.ui.browser.cards.NetworkVideoCard
import app.gyrolet.mpvrx.ui.browser.components.BrowserTopBar
import app.gyrolet.mpvrx.ui.browser.components.ExpressiveScrollBar
import app.gyrolet.mpvrx.ui.browser.components.fastScrollGlyph
import app.gyrolet.mpvrx.ui.browser.dialogs.NetworkSortDialog
import app.gyrolet.mpvrx.ui.browser.playlist.PlaylistDetailScreen
import app.gyrolet.mpvrx.ui.browser.states.EmptyState
import app.gyrolet.mpvrx.ui.components.InlineSearchBar
import app.gyrolet.mpvrx.ui.icons.Icon
import app.gyrolet.mpvrx.ui.icons.Icons
import app.gyrolet.mpvrx.ui.preferences.PreferencesScreen
import app.gyrolet.mpvrx.ui.utils.LocalBackStack
import app.gyrolet.mpvrx.ui.utils.calculateResponsiveGridSpans
import app.gyrolet.mpvrx.ui.utils.navigateTo
import app.gyrolet.mpvrx.ui.utils.popSafely
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import app.gyrolet.mpvrx.utils.history.RecentlyPlayedOps
import org.koin.compose.koinInject

@Serializable
data class NetworkBrowserScreen(
  val connectionId: Long,
  val connectionName: String,
  val currentPath: String = "/",
) : Screen {
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  override fun Content() {
    val backstack = LocalBackStack.current
    val context = LocalContext.current
    val browserPreferences = koinInject<BrowserPreferences>()
    val bookmarkPreferences = koinInject<NetworkBookmarkPreferences>()

    val networkSortType by browserPreferences.networkSortType.collectAsState()
    val networkSortOrder by browserPreferences.networkSortOrder.collectAsState()
    val networkLayoutMode by browserPreferences.networkLayoutMode.collectAsState()
    val includeAudioInBrowser by browserPreferences.includeAudioBrowser.collectAsState()
    val autoScrollToLastPlayed by browserPreferences.autoScrollToLastPlayed.collectAsState()
    val bookmarks by bookmarkPreferences.bookmarks.collectAsState()
    val normalizedPath = remember(currentPath) { NetworkPath.from(currentPath) }
    val canBookmarkCurrentFolder = normalizedPath.segments.isNotEmpty()
    val isCurrentFolderBookmarked =
      remember(bookmarks, connectionId, normalizedPath.value) {
        bookmarkPreferences.contains(connectionId, normalizedPath.value)
      }

    val viewModel: NetworkBrowserViewModel =
      viewModel(
        key = "NetworkBrowser_${connectionId}_$currentPath",
        factory =
          NetworkBrowserViewModel.factory(
            context.applicationContext as android.app.Application,
            connectionId,
            currentPath,
          ),
      )

    val files by viewModel.files.collectAsState()
    val connection by viewModel.connection.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    // Resolve the last played media file name once, so the file list can scroll to it.
    var lastPlayedFileName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
      lastPlayedFileName =
        withContext(Dispatchers.IO) {
          val entity = RecentlyPlayedOps.getLastPlayedEntity()
          if (entity != null && entity.filePath.contains("://")) {
            entity.fileName.takeIf { it.isNotBlank() }
          } else {
            null
          }
        }
    }

    // Persist the last browsed network location so the app can restore it on next launch.
    LaunchedEffect(connection?.id, currentPath) {
      val conn = connection
      if (conn != null) {
        browserPreferences.lastNetworkConnectionId.set(conn.id)
        browserPreferences.lastNetworkConnectionName.set(conn.name)
        browserPreferences.lastNetworkPath.set(currentPath)
      }
    }

    // UI State
    val isRefreshing = remember { mutableStateOf(false) }
    val sortDialogOpen = rememberSaveable { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var isSearching by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(isSearching) {
      if (isSearching) focusRequester.requestFocus()
    }

    // Load files when connectionId or currentPath changes
    LaunchedEffect(connectionId, currentPath) {
      viewModel.loadFiles()
    }

    LaunchedEffect(viewModel) {
      viewModel.importedPlaylistId.collect { playlistId ->
        backstack.navigateTo(PlaylistDetailScreen(playlistId))
      }
    }

    BackHandler {
      if (isSearching) {
        isSearching = false
        searchQuery = ""
      } else {
        backstack.popSafely()
      }
    }

    Scaffold(
      containerColor = app.gyrolet.mpvrx.ui.theme.wallpaperAwareBackgroundColor(),
      topBar = {
        if (isSearching) {
          InlineSearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            onSearch = { },
            modifier =
              Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            inputFieldModifier = Modifier.focusRequester(focusRequester),
            placeholder = {
              Text(stringResource(R.string.settings_search_title))
            },
            leadingIcon = {
              Icon(
                imageVector = Icons.RoundedFilled.Search,
                contentDescription = stringResource(R.string.settings_search_title),
              )
            },
            trailingIcon = {
              IconButton(
                onClick = {
                  isSearching = false
                  searchQuery = ""
                },
              ) {
                Icon(
                  imageVector = Icons.RoundedFilled.Close,
                  contentDescription = stringResource(R.string.generic_cancel),
                )
              }
            },
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp,
          )
        } else {
          BrowserTopBar(
            title = connectionName,
            isInSelectionMode = false,
            selectedCount = 0,
            totalCount = files.size,
            onBackClick = { backstack.popSafely() },
            onCancelSelection = {},
            onSortClick = { sortDialogOpen.value = true },
            onSearchClick = { isSearching = true },
            onSettingsClick = {
              backstack.navigateTo(PreferencesScreen)
            },
            onDeleteClick = null,
            onRenameClick = null,
            isSingleSelection = false,
            onInfoClick = null,
            onShareClick = null,
            onPlayClick = null,
            onSelectAll = null,
            onInvertSelection = null,
            onDeselectAll = null,
            additionalActions = {
              if (canBookmarkCurrentFolder) {
                IconButton(
                  onClick = {
                    bookmarkPreferences.toggle(
                      NetworkFolderBookmark(
                        connectionId = connectionId,
                        path = normalizedPath.value,
                        folderName = normalizedPath.segments.last(),
                      ),
                    )
                  },
                ) {
                  Icon(
                    imageVector = Icons.RoundedFilled.Star,
                    contentDescription =
                      stringResource(
                        if (isCurrentFolderBookmarked) {
                          R.string.network_bookmark_remove
                        } else {
                          R.string.network_bookmark_add
                        },
                      ),
                    tint =
                      if (isCurrentFolderBookmarked) {
                        MaterialTheme.colorScheme.primary
                      } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                      },
                  )
                }
              }
            },
          )
        }
      },
    ) { padding ->
      NetworkBrowserContent(
        files = files,
        connection = connection,
        isLoading = isLoading && files.isEmpty(),
        isRefreshing = isRefreshing,
        error = error,
        networkSortType = networkSortType,
        networkSortOrder = networkSortOrder,
        networkLayoutMode = networkLayoutMode,
        includeAudio = includeAudioInBrowser,
        searchQuery = searchQuery,
        lastPlayedFileName = lastPlayedFileName,
        autoScrollToLastPlayed = autoScrollToLastPlayed,
        onRefresh = { viewModel.loadFiles() },
        onFolderClick = { folder ->
          backstack.navigateTo(
            NetworkBrowserScreen(
              connectionId = connectionId,
              connectionName = connectionName,
              currentPath = folder.path,
            ),
          )
        },
        onVideoClick = { video ->
          viewModel.openMedia(video)
        },
        modifier = Modifier.padding(padding),
      )

      NetworkSortDialog(
        isOpen = sortDialogOpen.value,
        onDismiss = { sortDialogOpen.value = false },
      )
    }
  }
}

@Composable
private fun NetworkBrowserContent(
  files: List<NetworkFile>,
  connection: NetworkConnection?,
  isLoading: Boolean,
  isRefreshing: MutableState<Boolean>,
  error: String?,
  networkSortType: NetworkSortType,
  networkSortOrder: SortOrder,
  networkLayoutMode: MediaLayoutMode,
  includeAudio: Boolean,
  searchQuery: String,
  lastPlayedFileName: String? = null,
  autoScrollToLastPlayed: Boolean = false,
  onRefresh: suspend () -> Unit,
  onFolderClick: (NetworkFile) -> Unit,
  onVideoClick: (NetworkFile) -> Unit,
  modifier: Modifier = Modifier,
) {
  val sortedFiles =
    remember(files, networkSortType, networkSortOrder) {
      files.sortedForNetworkBrowser(networkSortType, networkSortOrder)
    }

  val filteredFiles =
    remember(sortedFiles, searchQuery) {
      if (searchQuery.isBlank()) {
        sortedFiles
      } else {
        sortedFiles.filter { it.name.contains(searchQuery, ignoreCase = true) }
      }
    }

  when {
    isLoading -> {
      Box(
        modifier =
          modifier
            .fillMaxSize()
            .padding(bottom = 80.dp),
        contentAlignment = Alignment.Center,
      ) {
        CircularProgressIndicator(
          modifier = Modifier.size(48.dp),
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }

    error != null -> {
      Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        EmptyState(
          icon = Icons.RoundedFilled.Folder,
          title = stringResource(R.string.ui_error_loading_files),
          message = error,
        )
      }
    }

    files.isEmpty() -> {
      Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        EmptyState(
          icon = Icons.RoundedFilled.Folder,
          title = stringResource(R.string.ui_empty_folder),
          message = "This folder contains no files or directories",
        )
      }
    }

    filteredFiles.isEmpty() -> {
      Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        EmptyState(
          icon = Icons.RoundedFilled.Search,
          title = stringResource(R.string.settings_search_title),
          message = "No items match '$searchQuery'",
        )
      }
    }

    else -> {
      val folders = remember(filteredFiles) { filteredFiles.filter { it.isDirectory } }
      val videos =
        remember(filteredFiles, includeAudio) {
          filteredFiles.filter { it.isPlayableNetworkMedia(includeAudio) || it.isNetworkPlaylistFile() }
        }
      val isGrid = networkLayoutMode == MediaLayoutMode.GRID

      val matchIndex =
        remember(videos, lastPlayedFileName) {
          if (lastPlayedFileName != null) videos.indexOfFirst { it.name == lastPlayedFileName } else -1
        }
      val folderSectionCount = if (folders.isNotEmpty()) 1 + folders.size else 0
      val videoHeaderCount = if (videos.isNotEmpty()) 1 else 0
      val targetFlatIndex =
        if (matchIndex >= 0) folderSectionCount + videoHeaderCount + matchIndex else 0
      val listState =
        rememberLazyListState(
          initialFirstVisibleItemIndex = if (autoScrollToLastPlayed && matchIndex >= 0) targetFlatIndex else 0,
        )
      val gridState =
        rememberLazyGridState(
          initialFirstVisibleItemIndex = if (autoScrollToLastPlayed && matchIndex >= 0) targetFlatIndex else 0,
        )
      val hasEnoughItems = (folders.size + videos.size) > 20

      LaunchedEffect(videos, matchIndex, autoScrollToLastPlayed) {
        if (autoScrollToLastPlayed && matchIndex >= 0 && targetFlatIndex >= 0) {
          if (isGrid) gridState.scrollToItem(targetFlatIndex) else listState.scrollToItem(targetFlatIndex)
        }
      }

      val scrollbarAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (hasEnoughItems) 1f else 0f,
        animationSpec =
          androidx.compose.animation.core.spring(
            dampingRatio = app.gyrolet.mpvrx.ui.theme.AppMotion.Effect.Alpha.dampingRatio,
            stiffness = app.gyrolet.mpvrx.ui.theme.AppMotion.Effect.Alpha.stiffness,
          ),
        label = "scrollbarAlpha",
      )

      PullRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        listState = listState,
        modifier = modifier.fillMaxSize(),
      ) {
        val scrollbarLabels =
          remember(folders, videos) {
            buildList<String?> {
              if (folders.isNotEmpty()) {
                add(null)
                addAll(folders.map { it.name })
              }
              if (videos.isNotEmpty()) {
                add(null)
                addAll(videos.map { it.name })
              }
            }
          }
        val navigationBarHeight = app.gyrolet.mpvrx.ui.browser.LocalNavigationBarHeight.current
        BoxWithConstraints(
          modifier =
            Modifier
              .fillMaxSize()
              .padding(bottom = navigationBarHeight),
        ) {
          if (isGrid) {
            val spansInfo = calculateResponsiveGridSpans(maxWidth = maxWidth, isGridMode = true)
            LazyVerticalGrid(
              columns = GridCells.Fixed(spansInfo.spans),
              state = gridState,
              modifier = Modifier.fillMaxSize(),
              contentPadding =
                PaddingValues(
                  start = 8.dp,
                  end = 8.dp,
                  top = 8.dp,
                  bottom = navigationBarHeight,
                ),
              horizontalArrangement = Arrangement.spacedBy(2.dp),
              verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
              if (folders.isNotEmpty()) {
                item(span = { GridItemSpan(spansInfo.spans) }) {
                  Text(
                    text = stringResource(R.string.pref_folders_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                  )
                }
                items(
                  items = folders,
                  key = { it.path },
                  span = { GridItemSpan(spansInfo.folderSpan) },
                ) { folder ->
                  NetworkFolderCard(
                    file = folder,
                    onClick = { onFolderClick(folder) },
                    isGridMode = true,
                    modifier = Modifier,
                  )
                }
              }

              if (videos.isNotEmpty()) {
                item(span = { GridItemSpan(spansInfo.spans) }) {
                  Text(
                    text = stringResource(R.string.ui_videos),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                  )
                }
                items(
                  items = videos,
                  key = { it.path },
                  span = { GridItemSpan(spansInfo.videoSpan) },
                ) { video ->
                  connection?.let { conn ->
                    NetworkVideoCard(
                      file = video,
                      connection = conn,
                      onClick = { onVideoClick(video) },
                      isGridMode = true,
                      modifier = Modifier,
                    )
                  }
                }
              }
            }
          } else {
            LazyColumn(
              state = listState,
              modifier = Modifier.fillMaxSize(),
              contentPadding =
                PaddingValues(
                  start = 8.dp,
                  end = 8.dp,
                  top = 8.dp,
                  bottom = navigationBarHeight,
                ),
            ) {
              if (folders.isNotEmpty()) {
                item {
                  Text(
                    text = stringResource(R.string.pref_folders_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                  )
                }
                items(
                  items = folders,
                  key = { it.path },
                ) { folder ->
                  NetworkFolderCard(
                    file = folder,
                    onClick = { onFolderClick(folder) },
                    isGridMode = false,
                    modifier = Modifier,
                  )
                }
              }

              if (videos.isNotEmpty()) {
                item {
                  Text(
                    text = stringResource(R.string.ui_videos),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                  )
                }
                items(
                  items = videos,
                  key = { it.path },
                ) { video ->
                  connection?.let { conn ->
                    NetworkVideoCard(
                      file = video,
                      connection = conn,
                      onClick = { onVideoClick(video) },
                      isGridMode = false,
                      modifier = Modifier,
                    )
                  }
                }
              }
            }
          }

          if (hasEnoughItems && scrollbarAlpha > 0.01f) {
            ExpressiveScrollBar(
              listState = if (!isGrid) listState else null,
              gridState = if (isGrid) gridState else null,
              dragLabelProvider = { index: Int ->
                fastScrollGlyph(scrollbarLabels.getOrNull(index))
              },
              modifier =
                Modifier
                  .align(Alignment.CenterEnd)
                  .padding(end = 4.dp)
                  .graphicsLayer { alpha = scrollbarAlpha },
            )
          }
        }
      }
    }
  }
}
