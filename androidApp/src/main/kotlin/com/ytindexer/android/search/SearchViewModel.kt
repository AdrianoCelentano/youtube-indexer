package com.ytindexer.android.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ytindexer.shared.index.CategoryWithCount
import com.ytindexer.shared.index.VideoIndexStore
import com.ytindexer.shared.search.SearchEngine
import com.ytindexer.shared.search.SearchResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

data class SearchUiState(
    val prompt: String = "",
    val selectedCategoryId: String? = null,
    val categories: List<CategoryWithCount> = emptyList(),
    val results: List<SearchResult> = emptyList(),
    val searching: Boolean = false,
    /** Nothing has been indexed yet, so an empty result list means "sync first". */
    val indexEmpty: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(
    private val engine: SearchEngine,
    private val store: VideoIndexStore,
) : ViewModel() {
    private val query = MutableStateFlow(Query())
    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private data class Query(
        val prompt: String = "",
        val categoryId: String? = null,
    )

    init {
        viewModelScope.launch {
            refreshCategories()
        }
        viewModelScope.launch {
            query
                // Debounced so a search does not run on every keystroke. Results still
                // feel live because terms are prefix-matched.
                .debounce(DEBOUNCE_MS)
                .flatMapLatest { current ->
                    flow {
                        emit(engine.search(current.prompt, current.categoryId))
                    }
                }.collect { results ->
                    _uiState.value = _uiState.value.copy(results = results, searching = false)
                }
        }
    }

    private suspend fun refreshCategories() {
        _uiState.value =
            _uiState.value.copy(
                categories = store.populatedCategories(),
                indexEmpty = store.videoCount() == 0L,
            )
    }

    fun onPromptChange(prompt: String) {
        _uiState.value = _uiState.value.copy(prompt = prompt, searching = true)
        query.value = query.value.copy(prompt = prompt)
    }

    /** Passing the already-selected category clears it, so a chip toggles. */
    fun onCategoryClick(categoryId: String) {
        val next = if (_uiState.value.selectedCategoryId == categoryId) null else categoryId
        _uiState.value = _uiState.value.copy(selectedCategoryId = next, searching = true)
        query.value = query.value.copy(categoryId = next)
    }

    /** Called after a sync, since new videos change both results and the category list. */
    fun onIndexChanged() {
        viewModelScope.launch {
            refreshCategories()
            // Re-run the current query rather than clearing it, so the user does not lose
            // what they typed.
            _uiState.value =
                _uiState.value.copy(
                    results = engine.search(query.value.prompt, query.value.categoryId),
                )
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 250L
    }
}
