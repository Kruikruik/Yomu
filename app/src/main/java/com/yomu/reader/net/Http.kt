package com.yomu.reader.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    const val USER_AGENT = "Yomu/1.0 (Android)"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val req = chain.request()
                val builder = req.newBuilder()
                if (req.header("User-Agent") == null) builder.header("User-Agent", USER_AGENT)
                // Les images de Webtoon (pstatic.net) exigent le Referer du site.
                if (req.url.host.endsWith("pstatic.net") && req.header("Referer") == null) {
                    builder.header("Referer", "https://www.webtoons.com/")
                }
                chain.proceed(builder.build())
            }
            .build()
    }

    suspend fun getString(url: HttpUrl, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()
            client.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw IOException("Erreur HTTP ${resp.code}")
                body
            }
        }

    suspend fun getJson(url: HttpUrl): JSONObject = JSONObject(getString(url))
}
