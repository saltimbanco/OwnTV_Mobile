package tv.own.owntv.mobile.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tv.own.owntv.core.content.SearchIntent
import tv.own.owntv.core.content.SearchReader
import tv.own.owntv.core.content.SearchResults
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.ActiveProfileSources
import tv.own.owntv.core.repository.activeProfileSources
import tv.own.owntv.core.settings.SettingsRepository
import tv.own.owntv.mobile.ui.screens.ContentActions

/**
 * One field that searches channels, films and shows at once.
 *
 * The searching itself is core's [SearchReader] — the same rules the television searches by, so a
 * hidden title stays hidden and a renamed channel keeps its new name on both. What is here is the
 * phone's part: the typing delay, the recent terms, and the long-press actions.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(
    private val searchReader: SearchReader,
    private val settings: SettingsRepository,
    private val sourceDao: SourceDao,
    private val actions: ContentActions,
) : ViewModel() {

    private val ctx: StateFlow<ActiveProfileSources> = activeProfileSources(settings, sourceDao)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActiveProfileSources(-1L, emptyList()))

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _intent = MutableStateFlow<SearchIntent?>(null)
    val intent: StateFlow<SearchIntent?> = _intent.asStateFlow()

    /**
     * What the query found — every match, up to core's cap, in one go. Debounced, because every
     * keystroke otherwise costs three queries over a catalogue that can hold a quarter of a million rows.
     */
    val results: StateFlow<SearchResults> = combine(
        _query.map { it.trim() }.debounce(DEBOUNCE_MS).distinctUntilChanged(),
        ctx,
    ) { q, c -> q to c }
        .flatMapLatest { (q, c) ->
            if (q.length < MIN_QUERY) flowOf(SearchResults())
            else flow { emit(searchReader.search(c.profileId, c, q)) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    /** The list behind an empty-state chip: Continue watching, Unwatched favourites, or Channels. */
    val curated: StateFlow<SearchResults> = combine(_intent, ctx) { i, c -> i to c }
        .flatMapLatest { (i, c) ->
            if (i == null) flowOf(SearchResults()) else flow { emit(searchReader.curated(c.profileId, c, i)) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchResults())

    val recentSearches: StateFlow<List<String>> = settings.recentSearches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Playlist names, so a row can say which provider it came from when there is more than one. */
    val sourceNames: StateFlow<Map<Long, String>> = ctx
        // This screen only ever shows the active profile's rows; other profiles' sources are
        // none of its business.
        .flatMapLatest { c ->
            if (c.profileId < 0) flowOf(emptyMap())
            else sourceDao.observeForProfile(c.profileId)
                .map { list -> list.associate { it.id to it.name } }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val favoriteChannels: StateFlow<Set<Long>> = favoriteIds(MediaType.LIVE)
    val favoriteMovies: StateFlow<Set<Long>> = favoriteIds(MediaType.MOVIE)
    val favoriteSeries: StateFlow<Set<Long>> = favoriteIds(MediaType.SERIES)

    private fun favoriteIds(type: MediaType): StateFlow<Set<Long>> = actions.favoriteIds(type)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun setQuery(q: String) {
        _query.value = q
        // Typing is the user leaving the launcher behind; the curated list would otherwise stay up
        // underneath the results.
        if (q.isNotBlank()) _intent.value = null
    }

    /** Picking a chip clears the query, so the two never compete for the same list. */
    fun setIntent(i: SearchIntent?) {
        _intent.value = i
        if (i != null) _query.value = ""
    }

    /** Remembered only when a result is actually opened — a term nobody used is not a search. */
    fun rememberQuery() {
        viewModelScope.launch { settings.addRecentSearch(_query.value) }
    }

    fun clearRecentSearches() {
        viewModelScope.launch { settings.clearRecentSearches() }
    }

    // --- The long-press actions -------------------------------------------------------------------

    fun toggleFavorite(type: MediaType, itemId: Long) {
        viewModelScope.launch { actions.toggleFavorite(type, itemId) }
    }

    fun hide(type: MediaType, itemId: Long) {
        viewModelScope.launch { actions.hide(type, itemId) }
    }

    fun download(type: MediaType, itemId: Long) {
        viewModelScope.launch { actions.download(type, itemId) }
    }

    private companion object {
        const val DEBOUNCE_MS = 300L
        const val MIN_QUERY = 2
    }
}
