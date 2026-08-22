# narcissus-2 — ekran nie gaśnie

    cd ~/android-dev/narcissus-2
    tar xzf ~/Pobrane/narcissus-2-ekran.tar.gz --strip-components=1
    ./gradlew installDebug

## Co było

W MainActivity siedział komentarz mówiący, że ekran nie gaśnie w trakcie
przejazdu — ale kodu nigdy nie było. Komentarz opisywał zamiar, nie stan
faktyczny.

## Co jest

`FLAG_KEEP_SCREEN_ON` na oknie. Flaga działa wyłącznie na widocznym oknie,
więc po przejściu aplikacji w tło telefon zasypia normalnie i nie trzeba jej
samemu zdejmować.

## Wariant oszczędny

Teraz ekran świeci także w czuwaniu, gdy czekasz na fix przed startem.
Jeśli okaże się to zbyt kosztowne dla baterii, można związać flagę ze
stanem przejazdu — wtedy zamiast wywołania w onCreate wchodzi to do
setContent, obok NarcissusTheme:

    LaunchedEffect(state.active) {
        if (state.active) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

## Plik

- `MainActivity.kt`
