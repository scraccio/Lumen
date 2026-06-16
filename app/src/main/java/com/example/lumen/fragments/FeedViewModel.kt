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

    fun loadArticles(topic: String? = null) {
        _selectedTopic.value = topic
        viewModelScope.launch {
            _isLoading.value = true
            if (topic == null) repository.fetchAndSaveArticles()

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

    fun filterByTopic(topic: String?) {
        viewModelScope.launch {
            _isLoading.value = true
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
