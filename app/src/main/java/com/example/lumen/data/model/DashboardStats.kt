package com.example.lumen.data.model

/** Aggregated reading statistics shown on the Stats (Dashboard) screen. */
data class DashboardStats(
    val articlesRead: Int,
    val storiesFollowed: Int,
    val sourcesUsed: Int,
    val biasLabel: String,          // "left" / "center" / "right" / "—"
    val biasTopPct: Int,            // percentage of the dominant bias label
    val topics: List<TopicShare>,   // top topics by reads, descending
    val biasLeftPct: Int,
    val biasCenterPct: Int,
    val biasRightPct: Int,
    val dailyActivity: List<DayActivity>,  // 7 entries, oldest → today
    val topSources: List<SourceShare>      // top read sources this week
)

data class TopicShare(val topic: String, val pct: Int)

data class DayActivity(val label: String, val count: Int)

data class SourceShare(val source: String, val count: Int)
