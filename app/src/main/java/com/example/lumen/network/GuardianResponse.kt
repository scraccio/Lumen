package com.example.lumen.network

data class GuardianResponse(
    val response: GuardianResponseBody
)

data class GuardianResponseBody(
    val results: List<GuardianArticle>
)

data class GuardianArticle(
    val id: String,
    val webTitle: String,
    val webUrl: String,
    val sectionName: String,
    val webPublicationDate: String,
    val fields: GuardianFields?
)

data class GuardianFields(
    val thumbnail: String?,
    val trailText: String?,
    val bodyText: String?
)