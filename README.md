# Kurier Radar — MVP 0.2

Prywatna aplikacja Android do analizy własnej historii rozwożenia jedzenia.

## Co już działa

- **Start / Stop zmiany** — foreground service zapisuje GPS mniej więcej co 10 sekund i działa po zgaszeniu ekranu.
- **Import wielu screenshotów naraz** z Wolt Courier lub Uber Driver.
- **OCR na telefonie** przez Google ML Kit (model łaciński jest dołączony do APK; nie wymaga ChatGPT ani klucza API).
- Parser odczytuje z ekranów m.in. godzinę, kwotę, dystans, restaurację; dla Ubera również czas dostawy i adres dowozu, jeśli OCR go widzi.
- Godzina dostawy jest dopasowywana do najbliższego punktu GPS (maks. ±10 min), więc powstaje punkt „gdzie byłem, gdy dostałem/przyjąłem zlecenie”.
- **Heatmapa** na OpenStreetMap z filtrami: platforma, dzień tygodnia, godzina, liczba zleceń / kwota / zł/km.
- **Historyczne strefy** — ranking obszarów na podstawie własnych zleceń.
- **Dzień × godzina** — macierz średniej wartości zlecenia.
- **Ranking restauracji** — liczba zleceń, średnia kwota, zł/km.
- Historia zleceń z **zł/km dla każdego pojedynczego zamówienia**, eksport CSV i kasowanie danych.

## Prywatność

Baza zleceń i GPS jest zapisywana lokalnie w SQLite na urządzeniu. OCR jest wykonywany lokalnie przez ML Kit. Aplikacja nie ma własnego serwera i nie wysyła bazy do ChatGPT. Internet jest potrzebny do pobierania kafelków mapy OpenStreetMap.

## Ważne po instalacji na Xiaomi

Wejdź w ustawienia aplikacji **Kurier Radar → Oszczędzanie baterii → Bez ograniczeń**. MIUI/HyperOS potrafi ubijać usługi GPS działające w tle.

## Jak zbudować APK

### Android Studio

1. Otwórz ten folder jako projekt.
2. Poczekaj na Gradle Sync.
3. `Build → Build APK(s)`.
4. APK debug znajdziesz w `app/build/outputs/apk/debug/app-debug.apk`.

Projekt używa Android Gradle Plugin 9.3.0, Gradle 9.5.0, compileSdk 36 i JDK 17.

### GitHub Actions (bez instalowania Android Studio)

W repozytorium jest `.github/workflows/build-apk.yml`. Po wrzuceniu projektu na GitHub workflow **Build APK** sam zbuduje APK i wystawi je jako artifact `KurierRadar-debug-apk`.

## Jak używać

1. Przed rozpoczęciem pracy naciśnij **Start zmiany**.
2. Jeździj normalnie z Wolt/Uber.
3. Po pracy wejdź w historię aplikacji kurierskiej i zrób screenshoty listy zleceń tak, by na każdym były widoczne godziny/kwoty/dystanse.
4. W Kurier Radar wybierz **Wybierz screeny** i zaznacz wszystkie naraz.
5. Wejdź w **Mapa** i **Analiza**.

## Ograniczenia wersji 0.2

- OCR jest heurystyczny — interfejs Wolt/Uber może się zmienić i wtedy parser trzeba będzie dostroić.
- Heatmapa pokazuje historię **Twoich własnych zleceń**, nie realny popyt wszystkich kurierów.
- Przy bardzo agresywnym oszczędzaniu baterii telefon może tworzyć luki w GPS; dlatego aplikacja pokazuje liczbę zleceń „bez GPS”.
- W pierwszej wersji score stref opiera się głównie na wartości zlecenia i zł/km, nie na pełnym „prawdziwym zł/h”, bo Wolt w historii nie podaje czasu oczekiwania/dowozu.

## Następne sensowne funkcje

- automatyczne rozpoznawanie i scalanie długich screenów,
- ręczna korekta błędnie rozpoznanego zlecenia,
- porównanie „zostać po dowozie vs wrócić do hotspotu”,
- prawdopodobieństwo kolejnego zlecenia w 5/10/15 min,
- osobny score dla roweru elektrycznego (km, czas, powrót do strefy),
- backup/restore bazy.
