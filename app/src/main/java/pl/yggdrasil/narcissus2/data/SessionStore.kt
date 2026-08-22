package pl.yggdrasil.narcissus2.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import pl.yggdrasil.narcissus2.domain.ActivityMode
import pl.yggdrasil.narcissus2.domain.Session
import pl.yggdrasil.narcissus2.domain.TrackPoint
import java.io.File

/**
 * Magazyn sesji na plikach.
 *
 * Metadane wszystkich sesji siedzą w jednym pliku JSON, ślady w osobnych
 * plikach po jednym na sesję. Powód podziału: przejazd to tysiące punktów,
 * a punkty są jedyną rzeczą, której nigdy nie przeszukujesz — otwierasz
 * ślad i rysujesz go w całości. Trzymanie ich razem z metadanymi znaczyłoby
 * wczytywanie megabajtów przy każdym otwarciu dziennika.
 *
 * Ślad zapisujemy PRZYROSTOWO, w trakcie przejazdu. Padnięta bateria albo
 * ubita aplikacja zabierają wtedy ostatnie sekundy, a nie całą trasę.
 */
class SessionStore(context: Context) {

    private val root = File(context.filesDir, "sessions").apply { mkdirs() }
    private val tracks = File(root, "tracks").apply { mkdirs() }
    private val index = File(root, "index.json")

    // ----------------------------------------------------------------
    // METADANE
    // ----------------------------------------------------------------

    suspend fun all(includeDeleted: Boolean = false): List<Session> =
        withContext(Dispatchers.IO) {
            readIndex().filter { includeDeleted || it.alive }
                .sortedByDescending { it.startedAt }
        }

    suspend fun save(session: Session) = withContext(Dispatchers.IO) {
        val current = readIndex().filterNot { it.id == session.id }
        writeIndex(current + session)
    }

    /**
     * Kasowanie zostawia nagrobek. Rekord znika z listy, ale zostaje
     * w pliku, żeby sync mógł go rozesłać jako "skasowane".
     */
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val updated = readIndex().map {
            if (it.id == id) {
                it.copy(deletedAt = now, updatedAt = now, synced = false)
            } else {
                it
            }
        }
        writeIndex(updated)
        trackFile(id).delete()
    }

    suspend fun deleteAllTest() = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val updated = readIndex().map {
            if (it.test && it.alive) {
                trackFile(it.id).delete()
                it.copy(deletedAt = now, updatedAt = now, synced = false)
            } else {
                it
            }
        }
        writeIndex(updated)
    }

    /**
     * Sprzątanie nagrobków, które na pewno obeszły już wszystkie urządzenia.
     * Wywoływać po udanej synchronizacji, nie częściej.
     */
    suspend fun purgeTombstones(olderThanMs: Long = TOMBSTONE_TTL_MS) =
        withContext(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - olderThanMs
            val kept = readIndex().filterNot { s ->
                s.deletedAt != null && s.synced && s.deletedAt < cutoff
            }
            writeIndex(kept)
        }

    // ----------------------------------------------------------------
    // ŚLAD
    // ----------------------------------------------------------------

    fun trackFile(sessionId: String) = File(tracks, "$sessionId.jsonl")

    /**
     * Otwiera ślad do dopisywania. Jedna linia JSON na punkt — format
     * odporny na obcięcie: uszkodzony ogon to strata ostatniego punktu,
     * a nie całego pliku.
     */
    fun openTrack(sessionId: String): TrackWriter = TrackWriter(trackFile(sessionId))

    suspend fun readTrack(sessionId: String): List<TrackPoint> = withContext(Dispatchers.IO) {
        val f = trackFile(sessionId)
        if (!f.exists()) return@withContext emptyList()

        f.useLines { lines ->
            lines.mapNotNull { line ->
                runCatching {
                    val o = JSONObject(line)
                    TrackPoint(
                        timestamp = o.getLong("t"),
                        latitude = o.getDouble("lat"),
                        longitude = o.getDouble("lon"),
                        accuracyM = o.optDouble("acc", 0.0).toFloat(),
                        speedMps = o.optDouble("spd", 0.0).toFloat(),
                        altitudeM = if (o.has("alt")) o.getDouble("alt") else null,
                    )
                }.getOrNull()
            }.toList()
        }
    }

    class TrackWriter(file: File) {
        private val writer = file.bufferedWriter()
        private var count = 0

        fun append(p: TrackPoint) {
            val o = JSONObject()
                .put("t", p.timestamp)
                .put("lat", p.latitude)
                .put("lon", p.longitude)
                .put("acc", p.accuracyM.toDouble())
                .put("spd", p.speedMps.toDouble())
            p.altitudeM?.let { o.put("alt", it) }

            writer.write(o.toString())
            writer.newLine()
            count++

            // Co trzydzieści punktów wypychamy bufor na dysk. Kompromis
            // między liczbą zapisów a tym, ile tracimy przy padzie.
            if (count % 30 == 0) writer.flush()
        }

        fun close(): Int {
            runCatching { writer.flush(); writer.close() }
            return count
        }
    }

    // ----------------------------------------------------------------
    // SERIALIZACJA
    // ----------------------------------------------------------------

    private fun readIndex(): List<Session> {
        if (!index.exists()) return emptyList()

        return runCatching {
            val arr = JSONArray(index.readText())
            (0 until arr.length()).mapNotNull { i ->
                runCatching { sessionFromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        }.getOrElse { emptyList() }
    }

    private fun writeIndex(sessions: List<Session>) {
        val arr = JSONArray()
        sessions.forEach { arr.put(sessionToJson(it)) }

        // Zapis przez plik tymczasowy — przerwanie w trakcie nie zostawia
        // uciętego indeksu, czyli nie kasuje całej historii.
        val tmp = File(root, "index.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(index)
    }

    /** Publiczne, bo tej samej postaci używa wymiana z serwerem. */
    fun sessionToJson(s: Session): JSONObject = with(s) {
        JSONObject()
        .put("id", id)
        .put("mode", mode.name)
        .put("startedAt", startedAt)
        .put("endedAt", endedAt)
        .put("distanceM", distanceM)
        .put("elapsedMs", elapsedMs)
        .put("movingMs", movingMs)
        .put("avgMovingSpeedMps", avgMovingSpeedMps.toDouble())
        .put("maxSpeedMps", maxSpeedMps.toDouble())
        .put("totalSteps", totalSteps)
        .put("acceptedFixes", acceptedFixes)
        .put("rejectedFixes", rejectedFixes)
        .apply { ascentM?.let { put("ascentM", it) } }
        .put("pointCount", pointCount)
        .put("test", test)
        .put("updatedAt", updatedAt)
        .apply { deletedAt?.let { put("deletedAt", it) } }
        .put("synced", synced)
    }

    fun sessionFromJson(o: JSONObject): Session = with(o) {
        Session(
        id = getString("id"),
        mode = ActivityMode.valueOf(getString("mode")),
        startedAt = getLong("startedAt"),
        endedAt = getLong("endedAt"),
        distanceM = getDouble("distanceM"),
        elapsedMs = getLong("elapsedMs"),
        movingMs = getLong("movingMs"),
        avgMovingSpeedMps = getDouble("avgMovingSpeedMps").toFloat(),
        maxSpeedMps = getDouble("maxSpeedMps").toFloat(),
        totalSteps = getInt("totalSteps"),
        acceptedFixes = getInt("acceptedFixes"),
        rejectedFixes = getInt("rejectedFixes"),
        ascentM = if (has("ascentM")) getDouble("ascentM") else null,
        pointCount = optInt("pointCount", 0),
        test = optBoolean("test", false),
        updatedAt = optLong("updatedAt", getLong("endedAt")),
        deletedAt = if (has("deletedAt")) getLong("deletedAt") else null,
        synced = optBoolean("synced", false),
        )
    }

    companion object {
        /** Ile trzymamy nagrobki, zanim uznamy, że obeszły wszystkie urządzenia. */
        const val TOMBSTONE_TTL_MS = 30L * 24 * 60 * 60 * 1000
    }
}
