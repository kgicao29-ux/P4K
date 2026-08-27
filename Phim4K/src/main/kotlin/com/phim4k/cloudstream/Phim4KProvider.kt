package com.phim4k.cloudstream

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addDuration
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class Phim4KProvider : MainAPI() {
    override var mainUrl = "https://apip4k.dpdns.org"
    override var name = "Phim4K"
    override var lang = "vi"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "movies" to "Phim lẻ mới cập nhật",
        "tvseries" to "Phim bộ mới cập nhật",
        "top_movie" to "Phim lẻ xem nhiều tuần này",
        "top_tvseries" to "Phim bộ xem nhiều tuần này",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val (path, params, type) = when (request.data) {
            "tvseries" -> Triple(
                "tvseries",
                mapOf("page" to page.toString()),
                TvType.TvSeries,
            )
            "top_movie" -> Triple(
                "top_views",
                mapOf(
                    "period" to "weekly",
                    "type" to "movie",
                    "limit" to "24",
                    "page" to page.toString(),
                ),
                TvType.Movie,
            )
            "top_tvseries" -> Triple(
                "top_views",
                mapOf(
                    "period" to "weekly",
                    "type" to "tvseries",
                    "limit" to "24",
                    "page" to page.toString(),
                ),
                TvType.TvSeries,
            )
            else -> Triple(
                "movies",
                mapOf("page" to page.toString()),
                TvType.Movie,
            )
        }

        val items = apiList(path, params)
        val results = items.mapNotNull { it.toSearchResponse(type) }
        return newHomePageResponse(
            HomePageList(request.name, results),
            hasNext = items.isNotEmpty(),
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        val common = mapOf("q" to query.trim(), "page" to "1")
        val movieResponse = runCatching {
            apiObject<SearchEnvelope>("search", common + ("type" to "movie"))
        }.getOrNull()
        val seriesResponse = runCatching {
            apiObject<SearchEnvelope>("search", common + ("type" to "tvseries"))
        }.getOrNull()

        val movies = movieResponse?.movie.orEmpty().mapNotNull {
            it.toSearchResponse(TvType.Movie)
        }
        val series = seriesResponse?.tvseries.orEmpty().mapNotNull {
            it.toSearchResponse(TvType.TvSeries)
        }
        return (movies + series).distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val match = LOAD_URL.find(url) ?: return null
        val requestedType = match.groupValues[1]
        val id = match.groupValues[2]
        val detail = apiObject<DetailResponse>(
            "single_details",
            mapOf("type" to requestedType, "id" to id),
        )
        val isSeries = detail.isTvSeries == "1" || requestedType == "tvseries"
        val poster = detail.posterUrl ?: detail.thumbnailUrl
        val year = yearFrom(detail.release, detail.title)
        val tags = detail.genre.orEmpty().mapNotNull { it.name }.distinct()
        val cast = detail.cast.orEmpty().mapNotNull { person ->
            person.name?.takeIf { it.isNotBlank() }?.let {
                ActorData(Actor(it, person.imageUrl))
            }
        }
        val recommendations = if (isSeries) {
            detail.relatedTvSeries.orEmpty().mapNotNull {
                it.toSearchResponse(TvType.TvSeries)
            }
        } else {
            detail.relatedMovie.orEmpty().mapNotNull {
                it.toSearchResponse(TvType.Movie)
            }
        }

        if (isSeries) {
            val episodes = detail.seasons.orEmpty().flatMapIndexed { seasonIndex, season ->
                val seasonNumber = seasonNumber(season.name, seasonIndex + 1)
                season.episodes.orEmpty().mapIndexedNotNull { episodeIndex, episode ->
                    val fileUrl = episode.fileUrl?.takeIf { it.isNotBlank() }
                        ?: return@mapIndexedNotNull null
                    val episodeNumber = episodeNumber(episode.name, episodeIndex + 1)
                    val payload = PlaybackPayload(
                        sources = listOf(
                            StreamSource(
                                label = episode.name ?: "Tập $episodeNumber",
                                fileUrl = fileUrl,
                                fileType = episode.fileType,
                                subtitles = episode.subtitles.orEmpty(),
                            )
                        )
                    )
                    newEpisode(payload) {
                        this.name = "Tập $episodeNumber"
                        this.season = seasonNumber
                        this.episode = episodeNumber
                        this.posterUrl = episode.imageUrl ?: poster
                    }
                }
            }

            return newTvSeriesLoadResponse(
                detail.title ?: "Phim4K",
                url,
                TvType.TvSeries,
                episodes,
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = detail.thumbnailUrl
                this.plot = detail.description
                this.year = year
                this.tags = tags
                this.actors = cast
                this.recommendations = recommendations
                addDuration(detail.runtime)
                addScore(detail.imdbRating)
                addTrailer(detail.trailerUrl)
            }
        }

        val sources = detail.videos.orEmpty().mapNotNull { video ->
            val fileUrl = video.fileUrl?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            StreamSource(
                label = video.label ?: detail.videoQuality ?: "Phim4K",
                fileUrl = fileUrl,
                fileType = video.fileType,
                subtitles = video.subtitles.orEmpty(),
            )
        }
        return newMovieLoadResponse(
            detail.title ?: "Phim4K",
            url,
            TvType.Movie,
            PlaybackPayload(sources),
        ) {
            this.posterUrl = poster
            this.backgroundPosterUrl = detail.thumbnailUrl
            this.plot = detail.description
            this.year = year
            this.tags = tags
            this.actors = cast
            this.recommendations = recommendations
            this.comingSoon = sources.isEmpty()
            addDuration(detail.runtime)
            addScore(detail.imdbRating)
            addTrailer(detail.trailerUrl)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val payload = runCatching { parseJson<PlaybackPayload>(data) }.getOrNull()
            ?: return false
        if (payload.sources.isEmpty()) return false

        val config = dynamicConfig()
        var emitted = 0
        payload.sources.forEach { source ->
            source.subtitles.distinctBy { it.url }.forEach { subtitle ->
                val subtitleUrl = absoluteUrl(subtitle.url, config.api)
                if (subtitleUrl != null) {
                    subtitleCallback(
                        newSubtitleFile(
                            subtitle.language ?: subtitle.srclang ?: "vi",
                            subtitleUrl,
                        )
                    )
                }
            }

            val original = source.fileUrl ?: return@forEach
            val streamUrl = resolveStreamUrl(original, config) ?: return@forEach
            val label = source.label?.trim()?.take(120)?.ifBlank { null } ?: "Phim4K"
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name • $label",
                    url = streamUrl,
                    type = extractorType(streamUrl, source.fileType),
                ) {
                    this.quality = qualityValue(label)
                }
            )
            emitted++
        }
        return emitted > 0
    }

    private suspend fun apiList(
        path: String,
        params: Map<String, String>,
    ): List<CatalogItem> {
        return parseJson(apiGet(path, params))
    }

    private suspend inline fun <reified T : Any> apiObject(
        path: String,
        params: Map<String, String>,
    ): T = parseJson(apiGet(path, params))

    private suspend fun apiGet(path: String, params: Map<String, String>): String {
        val config = dynamicConfig()
        val url = "https://${config.api}/rest-api/v130/$path"
        return app.get(
            url = url,
            headers = mapOf(
                "Accept" to "application/json",
                "API-KEY" to config.tokens,
                "Authorization" to BASIC_AUTH,
            ),
            params = params,
        ).text
    }

    private fun CatalogItem.toSearchResponse(forcedType: TvType): SearchResponse? {
        val id = videosId?.takeIf { it.isNotBlank() } ?: return null
        val displayTitle = title?.takeIf { it.isNotBlank() } ?: return null
        val actualType = if (isTvSeries == "1") TvType.TvSeries else forcedType
        val loadUrl = "$mainUrl/${if (actualType == TvType.TvSeries) "tvseries" else "movie"}/$id"
        val poster = posterUrl ?: thumbnailUrl
        val year = yearFrom(release, displayTitle)

        return if (actualType == TvType.TvSeries) {
            newTvSeriesSearchResponse(displayTitle, loadUrl, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                this.quality = searchQuality(videoQuality)
            }
        } else {
            newMovieSearchResponse(displayTitle, loadUrl, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                this.quality = searchQuality(videoQuality)
            }
        }
    }

    private suspend fun resolveStreamUrl(originalUrl: String, config: DynamicData): String? {
        val host = runCatching { URI(originalUrl).host.orEmpty().lowercase(Locale.US) }
            .getOrDefault("")
        val needsSigning = host == "cdn.phim4k.lol" ||
            host == config.cdn.lowercase(Locale.US) ||
            host.endsWith(".p4k.dpdns.org")
        if (!needsSigning) return originalUrl

        val filename = runCatching {
            URI(originalUrl).path.substringAfterLast('/').trim()
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val signedUrl = signedResolverUrl(filename, config.cdn)
        return runCatching {
            parseJson<SecureStreamResponse>(app.get(signedUrl).text).url
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun signedResolverUrl(filename: String, cdnHost: String): String {
        val signingSecret = dailySigningSecret(HMAC_SECRET)
        val timestamp = System.currentTimeMillis() / 1000L
        val mask = hmacSha256(signingSecret, "otp-ts-mask").copyOfRange(0, 4)
        val timestampBytes = ByteBuffer.allocate(4).putInt(timestamp.toInt()).array()
        for (index in timestampBytes.indices) {
            timestampBytes[index] = (timestampBytes[index].toInt() xor mask[index].toInt()).toByte()
        }
        val token = toHex(hmacSha256(signingSecret, "$filename:$timestamp"))
        return "https://$cdnHost/$filename?token=$token&ts=${toHex(timestampBytes)}"
    }

    private fun dailySigningSecret(secret: String): String {
        val dateFormat = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val date = dateFormat.format(Date())
        val key = sha256(secret.toByteArray(StandardCharsets.UTF_8))
        val iv = sha256("iv:$secret".toByteArray(StandardCharsets.UTF_8)).copyOfRange(0, 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, iv),
        )
        val encrypted = cipher.doFinal("$secret:$date".toByteArray(StandardCharsets.UTF_8))
        return "$date:${toHex(encrypted)}"
    }

    private fun hmacSha256(secret: String, value: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun sha256(value: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value)

    private fun toHex(value: ByteArray): String = buildString(value.size * 2) {
        value.forEach { byte -> append(String.format(Locale.US, "%02x", byte.toInt() and 0xff)) }
    }

    private suspend fun dynamicConfig(): DynamicData {
        val now = System.currentTimeMillis()
        cachedConfig?.takeIf { now - configLoadedAt < CONFIG_TTL_MS }?.let { return it }

        val loaded = runCatching {
            val encrypted = parseJson<EncryptedConfig>(app.get(CONFIG_URL).text)
            val clearText = decryptConfig(
                encrypted.iv ?: error("Missing config IV"),
                encrypted.ciphertext ?: error("Missing config ciphertext"),
            )
            parseJson<DynamicRoot>(clearText).data ?: error("Missing dynamic config data")
        }.getOrElse { cachedConfig ?: DEFAULT_CONFIG }

        cachedConfig = loaded
        configLoadedAt = now
        return loaded
    }

    private fun decryptConfig(ivText: String, ciphertextText: String): String {
        val flags = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        val iv = Base64.decode(ivText, flags)
        val ciphertext = Base64.decode(ciphertextText, flags)
        val key = sha256(CONFIG_SECRET.toByteArray(StandardCharsets.UTF_8))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(128, iv),
        )
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }

    private fun absoluteUrl(url: String?, apiHost: String): String? {
        val value = url?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return when {
            value.startsWith("https://") || value.startsWith("http://") -> value
            value.startsWith("/") -> "https://$apiHost$value"
            else -> "https://$apiHost/$value"
        }
    }

    private fun extractorType(url: String, fileType: String?): ExtractorLinkType = when {
        fileType.equals("m3u8", true) || url.substringBefore('?').endsWith(".m3u8", true) ->
            ExtractorLinkType.M3U8
        fileType.equals("mpd", true) || url.substringBefore('?').endsWith(".mpd", true) ->
            ExtractorLinkType.DASH
        else -> ExtractorLinkType.VIDEO
    }

    private fun qualityValue(label: String?): Int {
        val text = label.orEmpty()
        return when {
            text.contains("4K", true) || text.contains("2160", true) -> Qualities.P2160.value
            text.contains("1080", true) -> Qualities.P1080.value
            text.contains("720", true) -> Qualities.P720.value
            text.contains("480", true) -> Qualities.P480.value
            text.contains("360", true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun searchQuality(label: String?): SearchQuality? {
        val text = label.orEmpty()
        return when {
            text.contains("4K", true) || text.contains("2160", true) -> SearchQuality.FourK
            text.contains("1080", true) || text.contains("Full HD", true) -> SearchQuality.HD
            text.contains("720", true) || text.equals("HD", true) -> SearchQuality.HD
            text.contains("CAM", true) -> SearchQuality.Cam
            text.contains("SD", true) -> SearchQuality.SD
            else -> null
        }
    }

    private fun yearFrom(release: String?, title: String?): Int? =
        YEAR.find(release.orEmpty())?.value?.toIntOrNull()
            ?: YEAR.find(title.orEmpty())?.value?.toIntOrNull()

    private fun seasonNumber(name: String?, fallback: Int): Int =
        SEASON.find(name.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull() ?: fallback

    private fun episodeNumber(name: String?, fallback: Int): Int =
        EPISODE.find(name.orEmpty())?.groupValues?.getOrNull(1)?.toIntOrNull() ?: fallback

    companion object {
        private const val BASIC_AUTH = "Basic YWRtaW46MTIzNA=="
        private const val CONFIG_URL = "https://ltv.cryboiz.workers.dev/api/add"
        private const val CONFIG_SECRET = "8zP2mN7xR4vW9bQ1eC5yU0sI6tO3pA4f"
        private const val HMAC_SECRET = "5e8d1b4f9c2a6e730b1f8d4a92c5e3d1"
        private const val CONFIG_TTL_MS = 30L * 60L * 1000L

        private val DEFAULT_CONFIG = DynamicData(
            api = "apip4k.dpdns.org",
            cdn = "sv1.p4k.dpdns.org",
            tokens = "bbbb411dea44849",
        )

        @Volatile
        private var cachedConfig: DynamicData? = null

        @Volatile
        private var configLoadedAt: Long = 0L

        private val LOAD_URL = Regex("/(movie|tvseries)/(\\d+)(?:[/?#]|$)", RegexOption.IGNORE_CASE)
        private val YEAR = Regex("(?:19|20)\\d{2}")
        private val SEASON = Regex("(?i)(?:season|s)\\s*0*(\\d+)")
        private val EPISODE = Regex("(?i)(?:s\\d{1,3})?e\\s*0*(\\d+)")
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class CatalogItem(
    @JsonProperty("videos_id") val videosId: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("release") val release: String? = null,
    @JsonProperty("runtime") val runtime: String? = null,
    @JsonProperty("is_tvseries") val isTvSeries: String? = null,
    @JsonProperty("video_quality") val videoQuality: String? = null,
    @JsonProperty("imdb_rating") val imdbRating: String? = null,
    @JsonProperty("thumbnail_url") val thumbnailUrl: String? = null,
    @JsonProperty("poster_url") val posterUrl: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SearchEnvelope(
    @JsonProperty("movie") val movie: List<CatalogItem>? = null,
    @JsonProperty("tvseries") val tvseries: List<CatalogItem>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DetailResponse(
    @JsonProperty("videos_id") val videosId: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("slug") val slug: String? = null,
    @JsonProperty("release") val release: String? = null,
    @JsonProperty("runtime") val runtime: String? = null,
    @JsonProperty("video_quality") val videoQuality: String? = null,
    @JsonProperty("imdb_rating") val imdbRating: String? = null,
    @JsonProperty("is_tvseries") val isTvSeries: String? = null,
    @JsonProperty("thumbnail_url") val thumbnailUrl: String? = null,
    @JsonProperty("poster_url") val posterUrl: String? = null,
    @JsonProperty("videos") val videos: List<VideoSource>? = null,
    @JsonProperty("season") val seasons: List<SeasonSource>? = null,
    @JsonProperty("genre") val genre: List<NamedItem>? = null,
    @JsonProperty("country") val country: List<NamedItem>? = null,
    @JsonProperty("cast") val cast: List<PersonItem>? = null,
    @JsonProperty("director") val director: List<PersonItem>? = null,
    @JsonProperty("trailler_youtube_source") val trailerUrl: String? = null,
    @JsonProperty("related_movie") val relatedMovie: List<CatalogItem>? = null,
    @JsonProperty("related_tvseries") val relatedTvSeries: List<CatalogItem>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class NamedItem(
    @JsonProperty("name") val name: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PersonItem(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("image_url") val imageUrl: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VideoSource(
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("file_url") val fileUrl: String? = null,
    @JsonProperty("file_type") val fileType: String? = null,
    @JsonProperty("subtitle") val subtitles: List<SubtitleSource>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SeasonSource(
    @JsonProperty("seasons_name") val name: String? = null,
    @JsonProperty("episodes") val episodes: List<EpisodeSource>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EpisodeSource(
    @JsonProperty("episodes_name") val name: String? = null,
    @JsonProperty("file_url") val fileUrl: String? = null,
    @JsonProperty("file_type") val fileType: String? = null,
    @JsonProperty("image_url") val imageUrl: String? = null,
    @JsonProperty("subtitle") val subtitles: List<SubtitleSource>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SubtitleSource(
    @JsonProperty("language") val language: String? = null,
    @JsonProperty("srclang") val srclang: String? = null,
    @JsonProperty("url") val url: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PlaybackPayload(
    @JsonProperty("sources") val sources: List<StreamSource> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StreamSource(
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("fileUrl") val fileUrl: String? = null,
    @JsonProperty("fileType") val fileType: String? = null,
    @JsonProperty("subtitles") val subtitles: List<SubtitleSource> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EncryptedConfig(
    @JsonProperty("iv") val iv: String? = null,
    @JsonProperty("ciphertext") val ciphertext: String? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DynamicRoot(
    @JsonProperty("data") val data: DynamicData? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class DynamicData(
    @JsonProperty("api") val api: String = "apip4k.dpdns.org",
    @JsonProperty("cdn") val cdn: String = "sv1.p4k.dpdns.org",
    @JsonProperty("tokens") val tokens: String = "bbbb411dea44849",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SecureStreamResponse(
    @JsonProperty("url") val url: String? = null,
)
