package com.example.lumen.network

data class NytSearchResponse(val response: NytSearchBody)
data class NytSearchBody(val docs: List<NytArticle>?)
data class NytHeadline(val main: String)
data class NytArticle(
    val headline: NytHeadline,
    val web_url: String,
    val abstract: String?,
    val lead_paragraph: String?,
    val snippet: String?,
    val pub_date: String,
    val section_name: String?,
    val multimedia: NytMultimediaContainer?
)

data class NytMultimediaContainer(
    val default: NytImage?,
    val thumbnail: NytImage?
)

data class NytImage(
    val url: String?
)