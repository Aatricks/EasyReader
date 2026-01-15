package io.aatricks.novelscraper.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import io.aatricks.novelscraper.data.local.PreferencesManager
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttpClient(preferencesManager: PreferencesManager): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .addInterceptor(HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BODY
            })

        try {
            val trustManagerFactory = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm()
            )
            trustManagerFactory.init(null as java.security.KeyStore?)
            val defaultTrustManager = trustManagerFactory.trustManagers.first { tm -> tm is X509TrustManager } as X509TrustManager

            val dynamicTrustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    if (!preferencesManager.ignoreSslErrors) {
                        defaultTrustManager.checkClientTrusted(chain, authType)
                    }
                }

                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    if (!preferencesManager.ignoreSslErrors) {
                        defaultTrustManager.checkServerTrusted(chain, authType)
                    }
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> = defaultTrustManager.getAcceptedIssuers()
            }

            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, arrayOf(dynamicTrustManager), SecureRandom())
            
            builder.sslSocketFactory(sslContext.socketFactory, dynamicTrustManager)
            
            val defaultHostnameVerifier = okhttp3.internal.tls.OkHostnameVerifier
            builder.hostnameVerifier { hostname, session -> 
                if (preferencesManager.ignoreSslErrors) true 
                else defaultHostnameVerifier.verify(hostname, session)
            }
        } catch (e: Exception) {
            // Fallback to default if something goes wrong
        }

        return builder.build()
    }

    @Provides
    @Singleton
    fun provideKtorClient(okHttpClient: OkHttpClient): HttpClient {
        return HttpClient(OkHttp) {
            engine {
                preconfigured = okHttpClient
            }
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                })
            }
        }
    }
}
