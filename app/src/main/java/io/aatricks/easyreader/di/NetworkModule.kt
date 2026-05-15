package io.aatricks.easyreader.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.aatricks.easyreader.util.SafeDns
import io.aatricks.easyreader.util.SafeRedirectInterceptor
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.CertificatePinner
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

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
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient {
        val cacheDir = File(context.cacheDir, "http_cache")
        val cache = Cache(cacheDir, 50L * 1024 * 1024) // 50 MB

        val builder = OkHttpClient.Builder()
            .cache(cache)
            .dns(SafeDns())
            .connectionPool(ConnectionPool(20, 5, TimeUnit.MINUTES))
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .followRedirects(false)
            .certificatePinner(provideCertificatePinner())
            .addInterceptor(SafeRedirectInterceptor())

        return builder.build()
    }

    /**
     * Certificate pinning scaffold. Empty by default so the build does not break
     * the day a scraper rotates its cert and ships before the pin is updated.
     *
     * To enable pinning for a host:
     *
     * 1. Capture the SHA-256 of the SubjectPublicKeyInfo for the leaf and at least
     *    one backup cert in the chain:
     *
     *    `echo | openssl s_client -showcerts -connect novelfire.net:443 2>/dev/null \
     *       | openssl x509 -pubkey -noout \
     *       | openssl pkey -pubin -outform DER \
     *       | openssl dgst -sha256 -binary \
     *       | openssl enc -base64`
     *
     * 2. Add a builder.add("host", "sha256/<base64>") call below.
     * 3. Document rotation procedure in SECURITY.md before shipping.
     *
     * Reference: https://square.github.io/okhttp/4.x/okhttp/okhttp3/-certificate-pinner/
     */
    private fun provideCertificatePinner(): CertificatePinner {
        return CertificatePinner.Builder()
            // Populate per-host pins here; see KDoc above for the procedure.
            .build()
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
