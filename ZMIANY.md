# narcissus-2 — ekran biegu i skalowanie

    cd ~/android-dev/narcissus-2
    git add -A && git commit -m "stan przed ekranem biegu"   # najpierw zapisz to, co masz
    tar xzf ~/Pobrane/narcissus-2-bieg.tar.gz --strip-components=1
    git diff          # <-- zerknij, co się zmieniło
    ./gradlew installDebug

Nie budowałem tego u siebie (brak dostępu do repozytoriów Androida),
więc jeśli kompilator coś zgłosi — wklej, poprawię.

## Ekran BIEG

Cztery odczyty jednym rozmiarem, bez dużej cyfry — telefon w ręce,
nie na kierownicy:

    DYSTANS          KM
    TEMPO KM 3       MIN/KM   tempo bieżącego kilometra
    TEMPO ŚREDNIE    MIN/KM   cały trening, liczony z czasu ruchu
    KADENCJA         KR/MIN   z licznika kroków

ROWER i PIESZO bez zmian w układzie (duża cyfra zostaje).
Tryb decyduje o tym polem `hero` w `ActivityMode`.

## Silnik (`domain/TelemetryEngine.kt`)

- **Tempo wygładzone** — z dystansu z ostatnich 20 s, zamiast z dopplera
  co sekundę. Cyfra przestaje tańczyć. Używa go też TEMPO w trybie PIESZO.
- **Tempo kilometra** — liczone z czasu RUCHU, więc postój na światłach
  go nie psuje. Moment przekroczenia pełnego kilometra jest interpolowany.
  Przez pierwsze 100 m kilometra pokazuje tempo wygładzone, bo dzielenie
  przez kilkadziesiąt metrów daje bzdury.
- **Kadencja** — wcześniej pole istniało, ale zawsze było puste.
  Teraz: przyrost kroków z ostatnich 20 s na minutę. Licznik kroków oddaje
  zdarzenia paczkami, krótsze okno skakałoby między zerem a dwustoma.
  Bez nowych kroków przez 6 s → 0.
- Pojedynczy odrzucony fix nie zeruje już tempa na ekranie.
- `@Synchronized` na metodach silnika: kroki i pozycja przychodzą
  z różnych wątków (Dispatchers.Default) i zmieniają ten sam stan.
- Kroki i kadencja odświeżają się co sekundę także bez nowego fixa.

## Skalowanie (`ui/theme/Palette.kt`)

- Rozmiary z `Grid` mnożone przez szerokość ekranu względem 411 dp
  (S20 FE). Węższy telefon — mniejsze cyfry, proporcje te same.
- Wynik zaokrąglany do pełnych 11 pikseli fizycznych — Departure Mono
  jest wtedy ostry. Na S20 FE rozmiary zmieniają się minimalnie
  (etykiety odrobinę większe), za to są ostre.
- Systemowe powiększenie tekstu jest ignorowane — nie rozsadzi układu.
- Wszystkie `fontSize` idą teraz przez `gridSp(...)`.

## Test na innym telefonie bez innego telefonu

    adb shell wm size 720x1600 && adb shell wm density 320
    adb shell wm size reset && adb shell wm density reset

## Pliki

- `domain/Metrics.kt`, `domain/TelemetryEngine.kt`
- `service/TrackingController.kt`
- `ui/theme/Palette.kt`, `ui/TrackingScreen.kt`, `ui/ArchiveScreen.kt`
- `ui/components/Primitives.kt`, `ui/components/Indicators.kt`
