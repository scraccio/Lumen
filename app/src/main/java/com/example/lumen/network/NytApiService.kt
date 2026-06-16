package com.example.lumen.network

import retrofit2.http.GET
import retrofit2.http.Query

interface NytApiService {

    @GET("articlesearch.json")
    suspend fun getRecentArticles(
        @Query("api-key") apiKey: String,
        @Query("begin_date") beginDate: String,
        @Query("sort") sort: String = "newest",
        @Query("fl") fields: String = "headline,web_url,abstract,pub_date,section_name,multimedia",
        @Query("q") query: String = "Gaza ceasefire",
    ): NytSearchResponse

    @GET("articlesearch.json")
    suspend fun getArticleByUrl(
        @Query("api-key") apiKey: String,
        @Query("fq") filterQuery: String,
        @Query("fl") fields: String = "headline,web_url,abstract,lead_paragraph,snippet"
    ): NytSearchResponse
}