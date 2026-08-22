# narcissus-2 — poprawka kompilacji

    cd ~/android-dev/narcissus-2
    tar xzf ~/Pobrane/narcissus-2-fix-archive.tar.gz --strip-components=1
    ./gradlew installDebug

Przycisk kasowania sesji testowych wstawiłem do `SessionList`, ale
`onDeleteTests` jest parametrem `ArchiveScreen` — funkcja go nie dostawała.
Teraz jest w sygnaturze i przekazany przy wywołaniu.

## Plik

- `ui/ArchiveScreen.kt`
