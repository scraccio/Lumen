package com.example.lumen.network

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface GuardianApiService {

    @GET("search")
    suspend fun getArticles(
        @Query("api-key") apiKey: String,
        @Query("from-date") fromDate: String,
        @Query("show-fields") showFields: String = "thumbnail,trailText",
        @Query("page-size") pageSize: Int = 20,
        @Query("order-by") orderBy: String = "newest",
        @Query("q") query: String = ""
    ): GuardianResponse
}