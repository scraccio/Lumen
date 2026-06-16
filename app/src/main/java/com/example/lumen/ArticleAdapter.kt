package com.example.lumen.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.lumen.R
import com.example.lumen.data.model.Article
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ArticleAdapter(
    private var articles: List<Article>,
    private val onArticleClick: (Article) -> Unit
) : RecyclerView.Adapter<ArticleAdapter.ArticleViewHolder>() {

    inner class ArticleViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val thumbnail: ImageView = itemView.findViewById(R.id.iv_thumbnail)
        val source: TextView = itemView.findViewById(R.id.tv_source)
        val title: TextView = itemView.findViewById(R.id.tv_title)
        val description: TextView = itemView.findViewById(R.id.tv_description)
        val date: TextView = itemView.findViewById(R.id.tv_date)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ArticleViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_article, parent, false)
        return ArticleViewHolder(view)
    }

    override fun onBindViewHolder(holder: ArticleViewHolder, position: Int) {
        val article = articles[position]

        holder.source.text = article.source
        holder.title.text = article.title
        holder.date.text = formatDate(article.publishedAt)

        if (article.imageUrl != null) {
            holder.thumbnail.visibility = View.VISIBLE
            Glide.with(holder.itemView.context)
                .load(article.imageUrl)
                .centerCrop()
                .into(holder.thumbnail)
        } else {
            holder.thumbnail.visibility = View.GONE
        }

        holder.itemView.setOnClickListener { onArticleClick(article) }
    }

    override fun getItemCount() = articles.size

    fun updateArticles(newArticles: List<Article>) {
        articles = newArticles
        notifyDataSetChanged()
    }

    private fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}