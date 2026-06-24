package com.example.lumen.fragments

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.lumen.MainActivity
import com.example.lumen.R
import com.example.lumen.StoryAdapter
import com.example.lumen.ui.StoryDetailActivity
import kotlinx.coroutines.launch

class StoryFragment : Fragment() {

    private lateinit var adapter: StoryAdapter
    private lateinit var tvEmpty: TextView

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_story, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        tvEmpty = view.findViewById(R.id.tv_empty)

        adapter = StoryAdapter { story ->
            val intent = Intent(requireContext(), StoryDetailActivity::class.java)
            intent.putExtra(StoryDetailActivity.EXTRA_STORY_ID, story.id)
            startActivity(intent)
        }

        view.findViewById<RecyclerView>(R.id.rv_stories).apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@StoryFragment.adapter
        }

        val repository = (requireActivity() as MainActivity).repository
        viewLifecycleOwner.lifecycleScope.launch {
            repository.getFollowedStories().collect { stories ->
                adapter.submitList(stories)
                tvEmpty.visibility = if (stories.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }
}
