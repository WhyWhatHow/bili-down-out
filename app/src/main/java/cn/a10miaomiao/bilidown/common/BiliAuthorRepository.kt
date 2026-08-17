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
 *
 * 另有**负缓存**：会员/付费等视频 view API 永远返回错误码，
 * 对这类 key 记录失败时间戳，有效期内（24h）不再重复请求，
 * 避免每次刷新都白跑网络把并发预算和超时吃光。
 */
object BiliAuthorRepository {

    @Serializable
    data class Author(
        val name: String,
        val face: String? = null,
    )

    /** 磁盘缓存文件结构：UP主映射 + 负缓存（失败时间戳） */
    @Serializable
    data class CacheData(
        val authors: Map<String, Author> = emptyMap(),
        val failed: Map<String, Long> = emptyMap(),
    )

    private const val VIEW_API = "https://api.bilibili.com/x/web-interface/view"
    private const val CACHE_FILE = "bili_author_cache.json"

    /** 负缓存有效期：失败后该时长内不再请求同一视频 */
    internal const val NEGATIVE_CACHE_TTL_MS: Long = 24L * 60 * 60 * 1000

    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    // 内存缓存：key -> UP主信息，避免重复请求
    private val cache = ConcurrentHashMap<String, Author>()

    // 负缓存：key -> 上次失败时间戳（Unix 毫秒）
    private val negativeCache = ConcurrentHashMap<String, Long>()

    private var cacheFile: File? = null

    /** 在 Application.onCreate 中调用，加载磁盘缓存 */
    fun init(context: Context) {
        init(File(context.filesDir, CACHE_FILE))
    }

    /** 以指定文件初始化（加载磁盘缓存），便于单元测试注入临时文件 */
    fun init(file: File) {
        cacheFile = file
        cache.clear()
        negativeCache.clear()
        try {
            if (file.exists()) {
                val root = json.parseToJsonElement(file.readText()).jsonObject
                if (root.containsKey("authors")) {
                    // 新格式：含负缓存
                    val data = json.decodeFromJsonElement(CacheData.serializer(), root)
                    cache.putAll(data.authors)
                    negativeCache.putAll(data.failed)
                } else {
                    // 旧格式兼容：纯 Map<String, Author>
                    val serializer = MapSerializer(String.serializer(), Author.serializer())
                    cache.putAll(json.decodeFromJsonElement(serializer, root))
                }
            }
        } catch (e: Exception) {
            // 缓存损坏时静默丢弃，下次成功请求后重建
        }
    }

    /** 持久化当前缓存到磁盘（原子写：临时文件 + rename） */
    suspend fun persist() {
        val file = cacheFile ?: return
        val data = CacheData(cache.toMap(), negativeCache.toMap())
        withContext(Dispatchers.IO) {
            writeCache(file, data)
        }
    }

    /** 原子写实现：先写 .tmp 再 rename，防止写一半被杀丢整份缓存 */
    @Synchronized
    internal fun writeCache(file: File, data: CacheData) {
        try {
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(CacheData.serializer(), data))
            if (!tmp.renameTo(file)) {
                // rename 失败（罕见）退回复制
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            // 写入失败不影响主流程
        }
    }

    /** 负缓存是否仍在有效期 */
    internal fun isNegativeCacheActive(failedAt: Long, now: Long): Boolean {
        return now in failedAt until (failedAt + NEGATIVE_CACHE_TTL_MS)
    }

    /** 网络请求结果：author 非空即成功；apiCode 为 B 站业务码；networkError 为网络层异常 */
    internal data class RequestResult(
        val author: Author? = null,
        val apiCode: Int? = null,
        val networkError: Boolean = false,
    )

    /**
     * 失败是否记入长负缓存：只有 B 站 API 明确返回业务错误码（-404 不存在、
     * -403 无权限等，重试也不会成功）才记 24h；-412 属临时风控、网络异常属
     * 暂态故障，重试可能恢复，不记——否则一次风控会让刷新 24h 内都"查不到"。
     */
    internal fun shouldNegativeCache(apiCode: Int?, networkError: Boolean): Boolean {
        if (networkError) return false
        if (apiCode == null) return false
        return apiCode != 0 && apiCode != -412
    }

    /** 从响应体提取 B 站业务码，异常结构返回 null */
    fun parseResponseCode(responseBody: String): Int? {
        return try {
            json.parseToJsonElement(responseBody).jsonObject["code"]
                ?.jsonPrimitive?.intOrNull
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 只读查询内存/磁盘已缓存的 UP 主信息，**不发网络请求**。
     * 供缓存优先路径（UP 主页兜底等）毫秒级补全，避免等待网络超时。
     */
    fun peekAuthor(avid: Long? = null, bvid: String? = null): Author? {
        val key = buildKey(avid, bvid) ?: return null
        return cache[key]
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

    private fun requestAuthor(avid: Long?, bvid: String?): RequestResult {
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
                val body = response.body?.string()
                    ?: return@use RequestResult(networkError = true)
                RequestResult(
                    author = parseAuthor(body),
                    apiCode = parseResponseCode(body),
                )
            }
        } catch (e: Exception) {
            RequestResult(networkError = true)
        }
    }

    /**
     * 获取 UP 主信息（内存缓存优先，网络失败自动重试一次）。
     * 仅业务错误码（如会员视频 -403/-404）记入负缓存并在有效期内跳过；
     * -412 风控与网络异常属暂态失败，不记，下次刷新照常重试。
     * 注意：结果只进内存，需在批量调用完成后调用 [persist] 落盘。
     */
    suspend fun getAuthor(
        avid: Long? = null,
        bvid: String? = null,
    ): Author? {
        val key = buildKey(avid, bvid) ?: return null
        cache[key]?.let { return it }
        val now = System.currentTimeMillis()
        negativeCache[key]?.let { failedAt ->
            if (isNegativeCacheActive(failedAt, now)) {
                // 有效期内的已知业务失败（会员视频等），直接跳过
                return null
            }
        }
        var result = withContext(Dispatchers.IO) { requestAuthor(avid, bvid) }
        if (result.author == null) {
            // 偶发失败（风控/网络抖动），退避后重试一次
            delay(300)
            result = withContext(Dispatchers.IO) { requestAuthor(avid, bvid) }
        }
        result.author?.let { author ->
            cache[key] = author
            negativeCache.remove(key)
        } ?: run {
            if (shouldNegativeCache(result.apiCode, result.networkError)) {
                // 记录业务失败时间戳，有效期内不再请求
                negativeCache[key] = now
            }
        }
        return result.author
    }
}
