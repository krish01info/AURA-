package com.aura.di

import com.aura.BuildConfig
import com.aura.llm.ApiKeyStore
import com.aura.llm.GroqClient
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * Hilt module providing all network dependencies.
 *
 * Groq is the primary LLM (model cascade inside GroqClient). GeminiClient builds its
 * own client (different host) and is used by TaskManager when Groq fails.
 * The Groq key is read from [ApiKeyStore] on every request, so a key typed in
 * Settings works immediately without restarting the app.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false   // omit null fields (e.g. reasoning_effort) from requests
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG)
                HttpLoggingInterceptor.Level.BODY
            else
                HttpLoggingInterceptor.Level.NONE
            redactHeader("Authorization")   // never print API keys to logcat
        }
        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Provides
    @Singleton
    fun provideGroqRetrofit(okHttpClient: OkHttpClient, apiKeyStore: ApiKeyStore): Retrofit {
        val groqClient = okHttpClient.newBuilder()
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Authorization", "Bearer ${apiKeyStore.groqKey()}")
                        .header("Content-Type", "application/json")
                        .build()
                )
            }
            .build()

        return Retrofit.Builder()
            .baseUrl("https://api.groq.com/")
            .client(groqClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
    }

    @Provides
    @Singleton
    fun provideGroqClient(retrofit: Retrofit): GroqClient {
        return GroqClient(retrofit)
    }
}
