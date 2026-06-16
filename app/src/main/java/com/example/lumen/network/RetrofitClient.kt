package com.example.lumen.network

import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

object RetrofitClient {

    private const val BASE_URL = "https://content.guardianapis.com/"

    val guardianApi: GuardianApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://content.guardianapis.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GuardianApiService::class.java)
    }

    val nytApi: NytApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.nytimes.com/svc/search/v2/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(NytApiService::class.java)
    }
}