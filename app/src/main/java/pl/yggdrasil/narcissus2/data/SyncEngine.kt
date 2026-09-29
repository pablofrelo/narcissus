package pl.yggdrasil.narcissus2.data

import org.json.JSONArray
import pl.yggdrasil.narcissus2.domain.Session

/**
 * Scalanie stanu telefonu i serwera.
 *
 * Zasada jest jedna i celowo prosta: wygrywa nowszy [Session.updatedAt].
 * Bardziej wyrafinowane rozstrzyganie konfliktów nie ma tu sensu, bo
 * sesja po zapisaniu praktycznie się nie zmienia — jedyne realne zdarzenia
 * to skasowanie i doliczenie przewyższenia przez serwer.
 *
 * KLUCZOWE: skasowanie to nagrobek, czyli rekord z niepustym
 * [Session.deletedAt], a nie brak rekordu. Dzięki temu serwer potrafi
 * odróżnić "skasowane" od "urządzenie jeszcze tego nie ma" i nie przywraca
 * usuniętych sesji przy następnym połączeniu. To był ten błąd z pierwszej
 * budowy, przez który testy wracały w kółko.
 */
class SyncEngine(
    private val store: SessionStore,
    private val client: SyncClient,
    private val settings: SyncSettings,
) {

    data class Report(
        val sent: Int,
        val received: Int,
        val tracksUp: Int,
        val tracksDown: Int,
    )

    suspend fun sync(): Result<Report> = runCatching {
        val since = settings.lastSyncAt
        val local = store.all(includeDeleted = true)

        // Wysyłamy tylko to, co zmieniło się od ostatniej wymiany.
        // Nagrobki jadą tak samo jak żywe rekordy.
        val outgoing = local.filter { it.updatedAt > since || !it.synced }

        val payload = JSONArray()
        outgoing.forEach { payload.put(store.sessionToJson(it)) }

        val response = client.exchange(payload, since).getOrThrow()

        // --- przyjmujemy zmiany z serwera ---
        var received = 0
        var tracksDown = 0
        val byId = local.associateBy { it.id }
        val receivedIds = mutableSetOf<String>()

        for (i in 0 until response.sessions.length()) {
            val remote = runCatching {
                store.sessionFromJson(response.sessions.getJSONObject(i))
            }.getOrNull() ?: continue

            val current = byId[remote.id]

            // Starszy rekord z serwera nie nadpisuje świeższego lokalnego.
            if (current != null && current.updatedAt >= remote.updatedAt) continue

            store.save(remote.copy(synced = true))
            receivedIds += remote.id
            received++

            if (remote.deletedAt != null) {
                // Nagrobek z serwera kasuje też ślad lokalnie.
                store.trackFile(remote.id).delete()
                continue
            }

            // Ślad ściągamy tylko dla sesji, których jeszcze nie mamy.
            if (remote.pointCount > 0 && !store.trackFile(remote.id).exists()) {
                client.downloadTrack(remote.id, store.trackFile(remote.id))
                    .onSuccess { tracksDown++ }
            }
        }

        // --- dosyłamy ślady, o które poprosił serwer ---
        var tracksUp = 0
        for (i in 0 until response.wantTracks.length()) {
            val id = response.wantTracks.optString(i) ?: continue
            client.uploadTrack(id, store.trackFile(id)).onSuccess { tracksUp++ }
        }

        // Dopiero teraz oznaczamy wysłane jako zsynchronizowane — gdyby
        // wymiana padła w połowie, próbujemy ich jeszcze raz następnym razem.
        //
        // Z pominięciem tego, co serwer właśnie odesłał: to ta sama sesja
        // w nowszej wersji (np. z przewyższeniem), a zapis starej kopii
        // z [outgoing] by ją nadpisał. Tak Moto gubił przewyższenie
        // własnej przejażdżki przy każdej wymianie.
        outgoing.filterNot { it.id in receivedIds }.forEach { store.save(it.copy(synced = true)) }

        // Zegar bierzemy od SERWERA. Zegary telefonów potrafią się rozjechać
        // o minuty, a rozjechany znacznik "since" oznacza zgubione zmiany.
        settings.lastSyncAt = response.serverNow

        // Nagrobki, które obeszły już wszystkie urządzenia, można sprzątnąć.
        store.purgeTombstones()

        Report(
            sent = outgoing.size,
            received = received,
            tracksUp = tracksUp,
            tracksDown = tracksDown,
        )
    }
}
