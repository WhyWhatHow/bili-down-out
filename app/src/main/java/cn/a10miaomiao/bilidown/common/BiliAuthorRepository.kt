package cn.a10miaomiao.bilidown.common

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 通过 B 站公开 API 补全 UP 主名称与头像。
 *
 * 新版哔哩哔哩客户端的离线缓存 entry.json 中不再包含 owner 对象，
 * 因此用 avid/bvid 调用视频信息接口获取 data.owner.name / data.owner.face。
 *
 * 三级缓存：内存 -> 磁盘(JSON 文件) -> 网络（带一次重试），
 * 保证已识别过的 UP 主在离线/重启后依然能立即显示。
 */
object BiliAuthorRepository {

    @Serializable
    data class Author(
        val name: String,
        val face: String? = null,
    )

    private const val VIEW_API = "https://api.bilibili.com/x/web-interface/view"
    private const val CACHE_FILE = "bili_author_cache.json"

    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    // 内存缓存：key -> UP主信息，避免重复请求
    private val cache = ConcurrentHashMap<String, Author>()

    private var cacheFile: File? = null

    /** 在 Application.onCreate 中调用，加载磁盘缓存 */
    fun init(context: Context) {
        val file = File(context.filesDir, CACHE_FILE)
        cacheFile = file
        try {
            if (file.exists()) {
                val serializer = MapSerializer(String.serializer(), Author.serializer())
                val map = json.decodeFromString(serializer, file.readText())
                map.forEach { (k, v) -> cache[k] = v }
            }
        } catch (e: Exception) {
            // 缓存损坏时静默丢弃，下次成功请求后重建
        }
    }

    /** 增量写回磁盘（仅在有新增条目时调用） */
    private suspend fun persistCache() {
        val file = cacheFile ?: return
        withContext(Dispatchers.IO) {
            try {
                val serializer = MapSerializer(String.serializer(), Author.serializer())
                file.writeText(json.encodeToString(serializer, cache.toMap()))
            } catch (e: Exception) {
                // 写入失败不影响主流程
            }
        }
    }

    /**
     * 解析 view 接口响应中的 UP 主信息。
     *
     * @return UP 主名称与头像；code 非 0、无 owner 或解析异常时返回 null
     */
    fun parseAuthor(responseBody: String): Author? {
        return try {
            val root = json.parseToJsonElement(responseBody).jsonObject
            val code = root["code"]?.jsonPrimitive?.intOrNull ?: return null
            if (code != 0) return null
            val owner = root["data"]
                ?.jsonObject
                ?.get("owner")
                ?.jsonObject
                ?: return null
            val name = owner["name"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() } ?: return null
            val face = owner["face"]?.jsonPrimitive?.contentOrNull
            Author(name, face)
        } catch (e: Exception) {
            null
        }
    }

    private fun buildKey(avid: Long?, bvid: String?): String? {
        return when {
            !bvid.isNullOrBlank() -> "bv:$bvid"
            avid != null && avid > 0 -> "av:$avid"
            else -> null
        }
    }

    private fun requestAuthor(avid: Long?, bvid: String?): Author? {
        val url = if (!bvid.isNullOrBlank()) {
            "$VIEW_API?bvid=$bvid"
        } else {
            "$VIEW_API?aid=$avid"
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 BiliDownOut/1.2")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return@use null
                parseAuthor(body)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取 UP 主信息（内存/磁盘缓存优先，网络失败自动重试一次）。
     * 任何失败均静默返回 null，不阻塞列表展示。
     */
    suspend fun getAuthor(
        avid: Long? = null,
        bvid: String? = null,
    ): Author? {
        val key = buildKey(avid, bvid) ?: return null
        cache[key]?.let { return it }

        var author = withContext(Dispatchers.IO) { requestAuthor(avid, bvid) }
        if (author == null) {
            // 风控偶发失败（-412 等），退避后重试一次
            delay(300)
            author = withContext(Dispatchers.IO) { requestAuthor(avid, bvid) }
        }
        if (author != null) {
            cache[key] = author
            persistCache()
        }
        return author
    }
}
