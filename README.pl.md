# NARCISSUS

Licznik rowerowy, biegowy i pieszy na Androida, wyglądający jak terminal
na *Nostromo*: zielony fosfor na czerni, scanlines, konsolowy krój Spleen.

*[English](README.md)*

## Co robi

- **Trzy tryby**: ROWER, BIEG, PIESZO. Każdy tryb ma własny zestaw
  odczytów i własne progi dla GNSS.
- **Surowy GNSS**, nie Fused. W testach na dwóch telefonach naraz surowy
  `GPS_PROVIDER` wypadał lepiej poza miastem, gdzie Fused potrafił zgubić
  kilka minut śladu.
- **Dystans bez wirtualnych kilometrów**: fixy odpadają przy słabej
  dokładności i przy nierealnych przeskokach, a dystans rośnie dopiero,
  gdy pozycja odejdzie od punktu zaczepienia dalej niż szum. Stanie na
  światłach nic nie nabija.
- **Tempo**: wygładzone z ostatnich 20 s oraz tempo bieżącego kilometra.
  **Kadencja** z licznika kroków.
- **Dziennik sesji** w telefonie, z mapką każdego śladu.
- **Karta relacji**: obraz 1080×1920 z sesji (twoje zdjęcie przechodzące
  w czerń, liczby, mapka), udostępniany systemowym „Udostępnij”.
- **Start i stop przytrzymaniem** (2 s), żeby przypadkowe muśnięcie nie
  skończyło treningu w połowie.
- **Ochrona przed wypaleniem**: układ powoli wędruje po matrycy AMOLED.

## Serwer synchronizacji (opcjonalny)

W `server/` jest mała usługa FastAPI, która trzyma sesje w SQLite
i dolicza **przewyższenie** z Numerycznego Modelu Terenu GUGiK (siatka
1 m). Telefon zapisuje tylko szerokość i długość: wysokość z GNSS szumi
za bardzo, żeby ją sumować, a model terenu dla tego samego śladu zawsze
daje tę samą liczbę. Przewyższenie działa tylko w Polsce.

Serwer **nie ma uwierzytelniania**. Ma nasłuchiwać w sieci WireGuard
i to ona jest granicą zaufania. Nie wystawiaj go publicznie.

    cd server
    docker compose up -d

Aplikacja łączy się z nim po zwykłym HTTP. Adres `10.8.0.1:8765` występuje
w trzech miejscach, zmień wszystkie na swój:

- `server/compose.yml` (adres nasłuchu)
- `app/src/main/java/pl/yggdrasil/narcissus2/data/SyncSettings.kt` (`DEFAULT_URL`)
- `app/src/main/res/xml/network_security_config.xml` (jedyny host, dla
  którego dozwolony jest ruch nieszyfrowany)

## Budowanie

Android 11 (API 30) lub nowszy.

    ./gradlew installDebug

## O kodzie

Kod NARCISSUS napisało AI (Claude od Anthropic) pod moim kierunkiem:
ja wyznaczałem cel i wygląd, testowałem na swoim telefonie na spacerach
i na rowerze i zgłaszałem, co nie gra, runda po rundzie. Sam nie napisałem
ani linijki i chcę, żeby to było jasne od początku.

## Licencja

[MIT](LICENSE). Krój Spleen autorstwa Frederica Cambusa jest na
[licencji BSD 2-Clause](licenses/SPLEEN.txt).
