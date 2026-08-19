# narcissus-2

Licznik rowerowo-biegowy. Druga budowa, pisana od zera z wnioskami z pierwszej.

## Co jest zrobione

- **Rejestr metryk** (`domain/Metrics.kt`) — metryka jest daną, tryb deklaruje
  listę, ekran renderuje listę. Dodanie czwartego trybu nie dotyka UI.
- **Motywy noc/dzień** (`ui/theme/Palette.kt`) — kolor nie mieszka w komponencie,
  komponent zna tylko rolę. Przełącznik `[NOC]/[DZIEŃ]` w nagłówku.
- **Wskaźniki GPS/GSM/PWR** (`system/`, `ui/components/Indicators.kt`) — trzy
  niezależne strumienie scalone w jeden, kolumna po lewej od dużego odczytu.
- **Pozycja na żądanie** — dotknięcie wskaźnika GPS rozwija panel ze
  współrzędnymi w stopniach dziesiętnych i DMS.
- **Szkielet serwisu** (`service/TrackingService.kt`) — foreground z typem
  `location`, poprawnie wystartowany.

## Czego nie ma jeszcze

- Silnika pomiarowego: filtrowania fixów, liczenia dystansu, czujnika kroków.
  Miejsce na to jest w `TrackingService`, a punkt wejścia do UI to
  `TrackingViewModel.onTelemetry()`.
- Dziennika sesji i synchronizacji z heimdallem.
- Sekwencera.

## Wysokość

S20 FE nie ma barometru. Jedyne źródło w telefonie to GNSS, a to wysokość
elipsoidalna z szumem rzędu kilkunastu metrów — sumowanie takiego sygnału daje
setki metrów podjazdu na płaskim parkingu.

Dlatego telefon zapisuje **wyłącznie lat/lon**, a przewyższenie dolicza serwer
przy synchronizacji, z modelu terenu (NMT z GUGiK dla Polski). Wynik jest
powtarzalny: ten sam ślad zawsze da tę samą liczbę. Pole `Telemetry.ascentM`
zostaje `null` do czasu synchronizacji i metryka pokazuje wtedy `----`.

## Pierwsze uruchomienie

W paczce nie ma binarki `gradle-wrapper.jar` (nie da się jej przesłać
tekstem). Wygeneruj wrapper przed pierwszym buildem:

    cd narcissus-2
    gradle wrapper

albo po prostu otwórz katalog w Android Studio — zaproponuje to samo.

Potem:

    ./gradlew assembleDebug
    ./gradlew installDebug

## Czcionka

Docelowo Spleen, ten sam co na TTY. Wrzuć TTF do `app/src/main/res/font/`
i podmień `Type.Readout` w `ui/theme/Palette.kt` na
`FontFamily(Font(R.font.spleen))`. Na razie `FontFamily.Monospace` — kluczowa
jest stała szerokość cyfry, bo bez niej liczby skaczą przy każdej zmianie.

## Uprawnienia — uwaga

Siła sygnału GSM przez `TelephonyCallback.SignalStrengthsListener` **nie**
wymaga `READ_PHONE_STATE`. Nie dodawaj tego uprawnienia, dopóki nie okaże się,
że na Twoim urządzeniu naprawdę jest potrzebne — kod jest zabezpieczony
`try/catch` na `SecurityException`.
