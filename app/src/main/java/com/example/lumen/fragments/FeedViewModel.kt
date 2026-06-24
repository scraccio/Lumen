package com.example.lumen.ui.fragments

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.lumen.data.NewsRepository
import com.example.lumen.data.model.ArticleCluster
import com.example.lumen.ml.ArticleClusterer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FeedViewModel(
    private val repository: NewsRepository,
    context: Context
) : ViewModel() {

    private val clusterer = ArticleClusterer(context)

    private val _clusters = MutableStateFlow<List<ArticleCluster>>(emptyList())
    val clusters: StateFlow<List<ArticleCluster>> = _clusters

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _selectedTopic = MutableStateFlow<String?>(null)
    val selectedTopic: StateFlow<String?> = _selectedTopic

    // Only one feed collector at a time — cancel the previous when the topic changes,
    // otherwise each chip tap would leave another Room-Flow collector running.
    private var loadJob: Job? = null

    fun loadArticles(topic: String? = null) {
        _selectedTopic.value = topic
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _isLoading.value = true
            // Re-run the API search scoped to this category (null = general feed).
            repository.fetchAndSaveArticles(topic)

            repository.getAllArticles(topic).collect { articles ->
                val clusters = withContext(Dispatchers.Default) {
                    clusterer.cluster(articles)
                        .map { group -> ArticleCluster(articles = group) }
                }
                _clusters.value = clusters
                _isLoading.value = false
            }
        }
    }

    fun filterByTopic(topic: String?) = loadArticles(topic)

    override fun onCleared() {
        super.onCleared()
        clusterer.close()
    }
}

class FeedViewModelFactory(
    private val repository: NewsRepository,
    private val context: Context
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return FeedViewModel(repository, context) as T
    }
}
