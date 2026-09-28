# narcissus-2 — martwy strumień pozycji

    cd ~/android-dev/narcissus-2
    tar xzf ~/Pobrane/narcissus-2-fix-fix.tar.gz --strip-components=1
    git diff          # <-- ZERKNIJ, te pliki są nadpisane w całości
    ./gradlew installDebug

## Co zepsułem

Dwie rzeczy, obie moje.

### catch KOŃCZY strumień

W kontrolerze strumień pozycji miał na końcu `catch`. To nie jest
"obsłuż i jedź dalej" — catch kończy strumień na dobre. Jeden wyjątek
i odbiornik jest martwy aż do restartu aplikacji.

Teraz `retryWhen`: przy błędzie odczekanie i ponowna próba. Przyznanie
uprawnienia albo włączenie GPS-u w ustawieniach podnosi pomiar samo.

### Zła kolejność okien

Okno o wyjątek od optymalizacji baterii wstawiłem PRZED prośbą
o uprawnienia. Przejmowało ekran, aplikacja szła w tło, a strumień
pozycji startował, zanim uprawnienie zostało przyznane — i umierał
na tym pierwszym wyjątku.

Teraz o wyjątek pytamy dopiero po przyznaniu dostępu do pozycji.

## Przy okazji

LocationSource rozróżnia trzy przyczyny i mówi wprost którą:
BRAK UPRAWNIENIA DO POZYCJI, GPS WYŁĄCZONY W SYSTEMIE, BRAK USŁUGI
LOKALIZACJI. Trafia to do pola `error` w stanie.

To pole NIE JEST jeszcze nigdzie wyświetlane. Jeśli po tej poprawce
nadal nie łapie fixa, powiedz — dorzucę je do linii stanu, żeby
telefon sam mówił, co jest nie tak, zamiast milczeć.

## Pliki (nadpisane w całości)

- `service/TrackingController.kt`
- `system/LocationSource.kt`
- `MainActivity.kt`
