# narcissus-2 — spójność odczytów

Rozpakuj NA projekcie (wymaga wcześniejszej poprawki zbiorczej):

    cd ~/android-dev/narcissus-2
    tar xzf ~/Pobrane/narcissus-2-spojnosc.tar.gz --strip-components=1
    ./gradlew installDebug

## Co się zmieniło

- `MetricRow` zniknął. Został JEDEN wzór odczytu — `Readout` — używany
  zarówno przez dużą cyfrę, jak i przez małe metryki. Etykieta nad
  wartością, jednostka pod wartością, wszystko do prawej. Duży i małe
  różnią się wyłącznie parametrem `valueSize`.
- Duży odczyt dostał własny panel na pełną szerokość.
- Wskaźniki GPS/GSM/PWR przeniesione do dolnego panelu, lewa kolumna,
  rozłożone przez SpaceEvenly w tym samym rytmie co odczyty obok.

## Pliki

- `ui/components/Primitives.kt`
- `ui/components/Indicators.kt`
- `ui/TrackingScreen.kt`
