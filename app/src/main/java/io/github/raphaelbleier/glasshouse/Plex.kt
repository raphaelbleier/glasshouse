package io.github.raphaelbleier.glasshouse

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import kotlin.random.Random

data class Media(
    val ratingKey: String,
    val title: String,
    val subtitle: String,
    val meta: String,
    val summary: String,
    val art: String?,
    val poster: String?,
    val still: String?,
    val progress: Float,
)

data class Shelf(val onDeck: List<Media>, val recent: List<Media>, val picks: List<Media>) {
    fun all() = onDeck + recent + picks

    // Persisted so the home screen is filled instantly on start, before Plex answers
    fun toJson(): String = JSONObject()
        .put("onDeck", JSONArray(onDeck.map(::mediaJson)))
        .put("recent", JSONArray(recent.map(::mediaJson)))
        .put("picks", JSONArray(picks.map(::mediaJson)))
        .toString()

    companion object {
        fun fromJson(text: String): Shelf {
            val o = JSONObject(text)
            fun list(k: String) = o.getJSONArray(k).let { a -> (0 until a.length()).map { mediaOf(a.getJSONObject(it)) } }
            return Shelf(list("onDeck"), list("recent"), list("picks"))
        }

        private fun mediaJson(m: Media) = JSONObject()
            .put("k", m.ratingKey).put("t", m.title).put("s", m.subtitle).put("m", m.meta).put("d", m.summary)
            .put("a", m.art).put("p", m.poster).put("st", m.still).put("pr", m.progress.toDouble())

        private fun mediaOf(o: JSONObject): Media {
            fun s(k: String) = o.optString(k).takeIf { it.isNotEmpty() && it != "null" }
            return Media(o.getString("k"), o.getString("t"), o.getString("s"), o.getString("m"), o.getString("d"),
                s("a"), s("p"), s("st"), o.optDouble("pr").toFloat())
        }
    }
}

class Plex(val server: String, private val token: String, private val labels: Labels, var machineId: String = "") {
    // Localized formats, taken from string resources
    class Labels(val episode: (Int, Int) -> String, val hours: (Long, Long) -> String, val minutes: (Long) -> String)


    // Plex's photo transcoder scales (and blurs) server-side, which keeps the stick fast
    fun image(path: String?, w: Int, h: Int, blur: Int = 0): String? = path?.let {
        "$server/photo/:/transcode?width=$w&height=$h&minSize=1&upscale=1" +
            (if (blur > 0) "&blur=$blur" else "") +
            "&url=" + URLEncoder.encode(it, "UTF-8") + "&X-Plex-Token=$token"
    }

    fun deepLink(m: Media) =
        "plex://server://$machineId/com.plexapp.plugins.library/library/metadata/${m.ratingKey}"

    fun load(): Shelf {
        machineId = get("/").getString("machineIdentifier")
        val sections = get("/library/sections").optJSONArray("Directory")
        val picks = mutableListOf<Media>()
        for (i in 0 until (sections?.length() ?: 0)) {
            val s = sections!!.getJSONObject(i)
            if (s.getString("type") == "movie" || s.getString("type") == "show") {
                picks += list("/library/sections/${s.getString("key")}/all?unwatched=1&sort=audienceRating:desc" + page(40))
            }
        }
        return Shelf(
            onDeck = list("/library/onDeck" + page(20).replaceFirst('&', '?')),
            recent = list("/library/recentlyAdded" + page(30).replaceFirst('&', '?')),
            // Same picks all day, so the row doesn't reshuffle on every refresh
            picks = picks.shuffled(Random(LocalDate.now().toEpochDay())).take(20),
        )
    }

    private fun page(size: Int) = "&X-Plex-Container-Start=0&X-Plex-Container-Size=$size"

    private fun get(path: String): JSONObject {
        val c = URL(server + path).openConnection() as HttpURLConnection
        c.connectTimeout = 5000
        c.readTimeout = 10000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("X-Plex-Token", token)
        try {
            return JSONObject(c.inputStream.bufferedReader().readText()).getJSONObject("MediaContainer")
        } finally {
            c.disconnect()
        }
    }

    private fun list(path: String): List<Media> {
        val arr = get(path).optJSONArray("Metadata") ?: return emptyList()
        return (0 until arr.length()).map { parse(arr.getJSONObject(it)) }
    }

    private fun parse(o: JSONObject): Media {
        fun s(k: String) = o.optString(k).takeIf { it.isNotEmpty() }
        val duration = o.optLong("duration")
        val progress = if (duration > 0) o.optLong("viewOffset") / duration.toFloat() else 0f
        val minutes = duration / 60000
        val length = when {
            minutes >= 60 -> labels.hours(minutes / 60, minutes % 60)
            minutes > 0 -> labels.minutes(minutes)
            else -> null
        }
        val rating = o.optDouble("audienceRating").takeIf { !it.isNaN() }?.let { "★ %.1f".format(it) }
        val meta = listOfNotNull(s("year"), length, s("contentRating"), rating).joinToString("  ·  ")
        val key = o.getString("ratingKey")
        return when (s("type")) {
            "episode" -> Media(
                key, s("grandparentTitle") ?: "",
                labels.episode(o.optInt("parentIndex"), o.optInt("index")) + "  " + (s("title") ?: ""),
                meta, s("summary") ?: "", s("grandparentArt") ?: s("art"), s("grandparentThumb"), s("thumb"), progress,
            )
            "season" -> Media(
                key, s("parentTitle") ?: "", s("title") ?: "", meta,
                s("summary") ?: s("parentSummary") ?: "", s("art") ?: s("parentArt"), s("thumb"), s("art"), progress,
            )
            else -> Media(key, s("title") ?: "", s("tagline") ?: "", meta, s("summary") ?: "", s("art"), s("thumb"), s("art"), progress)
        }
    }
}
