# Obsługa RPG Maker XP / VX / VX Ace (RGSS) w PadPort

Stan na 29.09.2026. Dokument opisuje, co zostało zrobione, jak to działa,
co jest sprawdzone i co zostało do zrobienia. Wersja eksperymentalna.

## Punkt wyjścia

- PadPort uruchamiał tylko gry **MV/MZ** (JavaScript w WebView).
- Gry XP/VX/VX Ace działają na **RGSS**: skryptach Ruby i natywnym silniku
  graficznym, bez przeglądarki.
- Gra testowa: **To the Moon** (Steam), `E:\SteamLibrary\steamapps\common\To the Moon\To the Moon`.
  - To RPG Maker XP (RGSS1): `RGSS102E.dll`, `Data\Scripts.rxdata` wewnątrz
    zaszyfrowanego `To the Moon.rgssad` (RGSSAD v1), RTP w `lang.dat` (ZIP).
  - Twórcy dołączyli port na **mkxp**: `mkxp.conf`, `mkxp-console.exe`
    z Ruby 2.1 oraz `preload/ruby18_comp.rb` i `preload/win32_wrap.rb`.
    `To the Moon.exe` to oryginalny RGSS Player z Ruby 1.8.

## Wybrany silnik

- **mkxp-z** (fork mkxp, GPL-2.0-or-later) w wersji na Androida:
  - port: `thehatkid/mkxp-z-android`;
  - gotowe biblioteki: `BookerRues9/mkxp-z-android-reworked`, wydanie v1.0.0
    zbudowane przez GitHub Actions z commita `0828373e0ad65402eced54e6b45645fceacf6bea`.
- Plik APK wydania ma SHA-256 `e8bc82724dcb822c7f00e11df0b932c4a8d3a7b9167e28e129fc34f3d246b600`.
- W środku: Ruby 3.1, SDL 2.26.3, OpenGL ES, OpenAL Soft. Tylko `arm64-v8a`;
  emulator x86_64 uruchamia to przez tłumaczenie ARM.
- Silnika nie budujemy lokalnie: kompilacja wymaga Linuksa (autotools, Ruby
  hosta, NDK 23), a tu jest Windows bez WSL i Dockera.
- Klasy Java SDL 2.26.3 skopiowano z tego samego commita do `org.libsdl.app`.

### `tools/fetch_rgss_engine.py`

- Pobiera APK wydania, sprawdza SHA-256 i wypakowuje 8 bibliotek `.so`
  do `app/src/main/jniLibs/arm64-v8a/`. Ten katalog jest w `.gitignore`.
- Nakłada na `libmkxp-z.so` poprawki osadzonych shaderów GLSL (`PATCHES`,
  wersja `shaders-1`). Poprawki nie zmieniają długości pliku, a liczba trafień
  jest sprawdzana:
  - `precision mediump float;` → `precision highp   float;`. Na Adreno
    `mediump` ma 16 bitów, więc współrzędne w dużych atlasach kafelków traciły
    precyzję i kafelki mapy znikały lub migały. SwiftShader w emulatorze liczy
    w 32 bitach i tego nie pokazywał.
  - `tilemap.vert`: indeks autotile ograniczony do 0..6 (wcześniej odczyt
    poza tablicą), typy `lowp int` → domyślna precyzja.
- Wywoływany z `tools/build.py`. Gradle przerywa build bez bibliotek
  (zadanie `checkRgssEngine`).

## Architektura w aplikacji

| Plik | Rola |
|---|---|
| `RgssGame.java` | Wykrywanie gry, tłumaczenie `mkxp.conf` → `mkxp.json`, JSON5, czytanie archiwów RGSSAD v1/v3, kodowanie tekstu INI. Czysta logika, testy JVM. |
| `GameSource.java` | Po nieudanym szukaniu `index.html` sprawdza RGSS. W metadanych zapisuje `exec` (nazwę .ini/.rgssad) i `rgss` (1/2/3). Karta pokazuje „RPG Maker XP/VX/VX Ace”. |
| `RgssRuntime.java` | Kopia gry z SAF do `files/rgss/<id>/game`, manifest `files.json`, generowanie `mkxp.json`, eksport/import zapisów (ZIP), sprzątanie przy usunięciu. |
| `RgssActivity.java` | Odtwarzacz: rozszerza `SDLActivity`, proces `:rgss`, `singleTask`. Nakładka postępu kopiowania, menu, pad, pełny ekran. |
| `RgssKeys.java` | Mapowanie pada → klawisze RGSS. |
| `RgssArtwork.java` | Grafika karty z `Graphics/Titles` (luźne pliki lub wnętrze RGSSAD). |
| `SteamArtwork.java` | Zapasowa grafika karty ze sklepu Steam (wszystkie silniki). |
| `assets/rgss/padport_rgss.rb` | Warstwa zgodności Ruby, ładowana jako pierwszy skrypt preload. |
| `assets/rgss/NOTICE.txt` | Licencje i pochodzenie binariów. |
| `org/libsdl/app/*` | Java SDL 2.26.3. Jedna poprawka: flaga `RECEIVER_EXPORTED` dla Androida 14. |
| `app/lint.xml` | Lint pomija dołączony kod SDL. |

### Wykrywanie gry (`RgssGame.detect`)

- Kolejność kandydatów na nazwę gry: `execName` z `mkxp.conf`, nazwy archiwów
  `.rgssad`/`.rgss2a`/`.rgss3a`, `Game`, pozostałe pliki `.ini`.
- Wymagane: sekcja `[Game]` z `Library=RGSS1/2/3…` albo rozszerzeniem
  `Scripts=.rxdata/.rvdata/.rvdata2`, plus archiwum albo luźny plik skryptów.
- Tytuł bierzemy z `Title=`. Gdy jest pusty lub `Untitled`, używamy nazwy
  pliku wykonywalnego. Kodowanie: UTF-8, potem Shift_JIS, potem Windows-1252.
- Dla To the Moon: `exec="To the Moon"`, tytuł „To the Moon”, RGSS1 (XP).

### Uruchomienie

1. Przy dodawaniu gry pojawia się okno „Wersja eksperymentalna… może działać
   niestabilnie. Czy chcesz kontynuować?”. Gra trafia do biblioteki dopiero
   po „Kontynuuj”.
2. „Graj” uruchamia `RgssActivity` w osobnym procesie. Maszyny Ruby w mkxp-z
   nie da się zainicjować drugi raz, więc po wyjściu proces kończy się
   `System.exit(0)`, a biblioteka działa dalej.
3. Kopiowanie: mkxp-z potrzebuje ścieżek POSIX, a SAF daje tylko strumienie.
   - Kopiowane są tylko nowe lub zmienione pliki; pomijane są `.exe`, `.dll`,
     `.DS_Store`, `Thumbs.db`, `desktop.ini`.
   - Przed kopiowaniem sprawdzane jest wolne miejsce.
   - To the Moon: ok. 112 MB, ok. 17 s w emulatorze.
4. `RgssRuntime.configure` zapisuje `files/rgss/<id>/mkxp.json`:
   - źródła, po kolei: wartości domyślne PadPort, `mkxp.conf` gry, `mkxp.json`
     gry;
   - na końcu wymuszane: `gameFolder`, `execName`, `fullscreen=true`,
     `anyAltToggleFS=false`, `enableSettings=false`, `pathCache=true`;
   - skrypty preload: `padport_rgss.rb`, potem skrypty gry. Gdy `mkxp.conf`
     ich nie wymienia, dołączane są `preload/*.rb`, bo tak robi build mkxp
     Freebird Games.
5. Katalog roboczy przekazujemy przez statyczne pole `GAME_PATH`, które
   biblioteka czyta przez JNI. Zmienne środowiskowe: `PADPORT_SAVE_DIR`
   i `SDL_JOYSTICK_HIDAPI=0`.
6. Metody wywoływane z natywnego kodu: `getSystemLanguage`, `hasVibrator`,
   `vibrate`, `vibrateStop`, `inMultiWindow`. Uprawnienie `VIBRATE` jest
   w manifeście.

### Zapisy

- Katalog `files/rgss/<id>/saves` jest ustawiany jako `APPDATA`,
  `LOCALAPPDATA`, `AV_APPDATA`, `USERPROFILE`, `HOME`, `TEMP` i `TMP`.
- To the Moon zapisuje w `saves/To the Moon - Freebird Games/Save*.rxdata`.
- Pliki utworzone przez grę w jej własnym folderze (typowe `Save1.rxdata`
  w XP) też są traktowane jako zapisy.
- Menu gry (Wstecz, ⋮, Guide): powrót do gry, eksport i import zapisów (ZIP),
  log diagnostyczny (`logcat` procesu), zakończenie gry.
- Usunięcie gry z biblioteki kasuje tylko pliki skopiowane z oryginału
  (według manifestu) i wygenerowaną konfigurację. Zapisy zostają.

### Warstwa zgodności `padport_rgss.rb`

Działa po zdekodowaniu `$RGSS_SCRIPTS`, przed wykonaniem skryptów gry.
Plików gry nie zmienia, wszystko dzieje się w pamięci.

- **Składnia Ruby 1.8 → 3.1:** tylko sekcje, które się nie kompilują.
  Tokeny z Ripper (część C z `libruby`), przepisywanie powtarzane do skutku
  i sprawdzane przez `RubyVM::InstructionSequence.compile`.
  - `{a, b, c, d}` → `{a => b, c => d}` (listy haszy);
  - `when x:` → `when x then`;
  - `foo (a, b)` → `foo(a, b)`.
- **Semantyka Ruby 1.8:** `foo (x).bar` → `foo(x).bar` we wszystkich sekcjach
  gier XP/VX. Naprawia błąd `undefined method 'width' for "Technik":String`
  w skrypcie „modern algebra para formater” (linie 180 i 229).
- **Metody z Ruby 1.8** (tylko RGSS1/2): `NilClass#id` (=4), `Object#id`,
  `Object#type`, `Hash#index`, `Array#to_s` (join), `Array#nitems`,
  `String#each`.
- **Środowisko Windows:**
  - zmienne środowiskowe jak wyżej;
  - ścieżki `\` → `/` w `File`/`Dir`/`FileTest`/`IO`, `open`, `load_data`,
    `save_data`;
  - aliasy `File.exists?` i `Dir.exists?`.
- **Zapasowy `Win32API`:** zamiast przerywać grę błędem `dlopen`, dostarcza:
  - klawiaturę: `GetAsyncKeyState`, `GetKeyState`, `GetKeyboardState`
    (z `Input.raw_key_states`);
  - mysz i okno: `GetCursorPos`, `GetClientRect`, `GetWindowRect`,
    `GetSystemMetrics`, `ShowCursor`, `FindWindow`;
  - pliki INI: `GetPrivateProfileString`, `GetPrivateProfileInt`;
  - pozostałe znane funkcje: `MessageBox` (do logu), `GetTickCount`, język;
  - nieznane funkcje zwracają 1 (sukces). Tak robi wrapper mkxp; To the Moon
    kończy grę, gdy `SteamAPI_Init != 1`.
  - Wrappery gry, np. `win32_wrap.rb`, nadal mają pierwszeństwo.
- **API `MKXP`** z mkxp (Ancurio) dla skryptów preload gier:
  `MKXP.puts`, `MKXP.raw_key_states` (jako String bajtów), `data_directory`.
- **Pełny ekran:** `Graphics.fullscreen=` zawsze ustawia `true`. To the Moon
  wysyła przy starcie Alt+Enter, co wyrzucało grę do okna z paskiem
  powiadomień Androida.

### Pad, dotyk, mysz

- Pady idą przez `ControllerHub`, z tymi samymi profilami i nauczonymi
  klawiszami co w MV/MZ. Stan jest zamieniany na klawisze domyślnego układu
  RGSS przez `SDLActivity.onNativeKeyDown/Up`. Klawiatura, a nie pad SDL,
  bo skrypty XP często czytają klawiaturę przez `Win32API`.

  | Pad | Klawisz | RGSS |
  |---|---|---|
  | A | Enter | C (zatwierdź) |
  | B, Start | Esc | B (anuluj/menu) |
  | X | Shift | A |
  | Y | A | X |
  | L1 / R1 | Q / W | L / R |
  | L2 / R2 | S / D | Y / Z |
  | krzyżak, lewa gałka | strzałki | kierunki |
  | Guide | — | menu PadPort |

- HIDAPI SDL jest wyłączone, żeby pady USB nie były czytane podwójnie.
- Dotyk działa jako mysz: SDL zamienia go na ruch i lewy przycisk. Gry XP
  ze skryptem myszy, np. To the Moon, powinny reagować na stuknięcia.
  **Na urządzeniu jeszcze niesprawdzone.** Fizyczna mysz idzie tą samą drogą,
  ale prawy przycisk może zostać zamieniony przez Androida na Wstecz i otworzyć
  menu PadPort.
- Druga sesja dodała wirtualny pad na ekranie (`VirtualController*`) i wpięła
  go w `RgssActivity`.

### Pełny ekran

- `RgssActivity` ukrywa pasek stanu i nawigacji (immersive sticky, obszar
  wycięcia ekranu):
  - przy starcie, po odzyskaniu fokusu i po starcie silnika;
  - ponownie, gdy SDL albo system je pokażą.

### Grafika kart

- XP: pierwszy obraz z `Graphics/Titles/`, luźny albo z archiwum RGSSAD.
  To the Moon ma tam tylko czarny obraz 640×480, bo jego ekran tytułowy jest
  mapą.
- Obraz jednolity (rozrzut jasności w siatce 16×16 próbek poniżej 12) jest
  traktowany jak brak grafiki. Taki obraz zapisany wcześniej w pamięci
  podręcznej jest usuwany przy następnym wczytaniu.
- Wszystkie silniki: gdy gra nie ma obrazu tytułowego, pobieramy grafikę
  ze Steam CDN (`library_hero.jpg`, potem `capsule_616x353.jpg`, `header.jpg`,
  potem `header_image` z `appdetails`).
  - Numer aplikacji z `steam_appid.txt`; bez niego wyszukiwarka Steam
    i wyłącznie identyczny tytuł.
  - „Nie ma na Steamie” jest zapamiętywane (`.none`), brak sieci nie.
  - Dla kart, które mają już zapisany brak grafiki: **⋮ → Grafika karty… →
    Przywróć grafikę z plików gry**.

## Testy i narzędzia

### To the Moon: opcjonalny drugi ekran (kod, bez nowego APK)

- `To the Moon — interfejs dwuekranowy` w menu karty i menu podczas grania.
  Pytanie przy dodawaniu jest po potwierdzeniu eksperymentalnego silnika,
  wyłącznie gdy drugi wyświetlacz jest dostępny. Włączoną opcję oznacza ikona.
- Na dole: Postacie (aktualni towarzysze i `CHARACTER_BIO`), Notatki oraz
  Przedmioty (nazwy/opisy posiadanych wpisów). Podział jak w `Window_Notes`
  i `Window_MenuItem`, na podstawie zlokalizowanego `NoteText`.
- Tytuł rozpoznaje także `$ttm_title_screen` i mapę startową z `System.rxdata`.
  Przed rozpoczęciem/wczytaniem gry panel ma tylko planszę tytułową. Po
  rozpoczęciu jest podglądem danych gry także w menu i podczas dialogów;
  przeglądanie nie zmienia przedmiotów, nie używa zapisu ani nie ujawnia
  niezdobytych wpisów. Górny ekran i jego menu działają normalnie.
- `padport_ttm_dual.rb` dodawany jako preload tylko dla TTM/XP. Hook
  `Graphics.update` odczytuje stan na wątku RGSS co 250 ms. JSON przez
  `HTTPLite::JSON`, z alternatywą standardowego `json`; błędy mostka są
  logowane i nie przerywają gry. Prywatne atomowe `companion/control.json`
  i `state.json` nie wchodzą do eksportu zapisów. Java wymienia heartbeat;
  stare sesje/generacje ekranu są odrzucane.
- Ustawienie ma osobny plik per gra, bo SharedPreferences procesu głównego
  i `:rgss` mają niezależne cache. Na Androidzie 9+ WebView w `:rgss` używa
  osobnego katalogu, aby nie zablokować WebView w procesie biblioteki.
- Hook `Graphics.update` to klasyczny łańcuch `alias_method`, **nie**
  `Module#prepend`. Preload działa przed skryptami gry, a gry same aliasują
  `Graphics.update` - w TTM sekcja 114 (pauza F12 Zeriaba:
  `alias_method(:zeriab_f12_pause_update, :update)` + nowe `update`). Alias
  zrobiony po `prepend` kopiuje metodę z doklejonego modułu, jej `super` trafia
  w nowe `update` gry, które znów woła alias: na urządzeniu kończyło się to
  `SystemStackError` w pierwszej klatce (`padport_ttm_dual.rb:111`). Test
  regresji w `tests/ttm-dual-test.rb`; sprawdzone też z oryginalną sekcją 114.
  Ta sama pułapka dotyczy `prepend` w `padport_rgss.rb` (`load_data`,
  `save_data`, `open`, metody `File`/`Dir`, `Graphics.fullscreen=`), gdyby
  któraś gra aliasowała te metody - TTM tego nie robi.
- Sprawdzono logikę Ruby w JRuby 9.4/Ruby 3.1, filtrowanie wpisów względem
  skryptu menu gry, panel JS i Java. Poprawionego preloada nie uruchomiono
  jeszcze w silniku mkxp-z na urządzeniu; fizycznego dotyku na dolnym ekranie
  też nie. Bez buildu APK zgodnie z `AGENTS.md`.

Test: `ruby tests/ttm-dual-test.rb` (Ruby 3.1+ lub `java -jar jruby-complete.jar
tests/ttm-dual-test.rb`). Opcjonalna zmienna `TTM_MENU_SCRIPT` wskazuje
wyeksportowany skrypt zawierający `Window_Notes` i `Window_MenuItem`.

- Testy JVM (`.\gradlew.bat testDebugUnitTest`):
  - `RgssGameTest`: wykrywanie (To the Moon, VX Ace, luźne XP, MV → brak),
    `mkxp.conf`, JSON5, konfiguracja, domyślne preloady, kodowania INI,
    archiwum RGSSAD v1, mapowanie pada, pomijane pliki;
  - `SteamArtworkTest`: `steam_appid.txt`, dopasowanie tytułu, znak ™.
- `tools/android_rgss_smoke.py` (tylko `emulator-5580`): kopiuje To the Moon
  do `Download`, dodaje grę przez systemowy wybór folderu, uruchamia, naciska
  A na wirtualnym padzie USB i robi zrzuty ekranu.
- `tools/run_device.py`: instalacja i uruchomienie na urządzeniu przez adb.
  Opcje: `--build`, `--play "To the Moon"`, `--log`, `-s SERIAL`,
  `--no-install`. Domyślnie wybiera jedyne fizyczne urządzenie.
- Zasada z `AGENTS.md`: APK budujemy tylko na wyraźną prośbę.

## Co jest sprawdzone

- Silnik w emulatorze Androida 15 (x86_64, tłumaczenie ARM, SwiftShader):
  To the Moon od ekranu powitalnego przez ekran tytułowy do nowej gry.
  Działa zarówno z wrapperem gry, jak i wyłącznie z zapasowym `Win32API`
  PadPort.
- PadPort w emulatorze: dodanie przez SAF, karta XP, kopia gry, start procesu
  `:rgss` i skryptów.
- AYN Thor (Adreno 740): gra się uruchamia, zapis `Save4.rxdata` się utworzył,
  sprzątanie po usunięciu z biblioteki zadziałało. Wersja z poprawką pełnego
  ekranu: ekran tytułowy bez paska powiadomień.

## Otwarte sprawy

- **Nie sprawdzono na Thorze** (czeka na nowy build):
  - poprawka shaderów, czyli znikające i migające kafelki;
  - poprawka `foo (x).bar`;
  - okno ostrzeżenia przy dodawaniu gry;
  - grafika karty ze Steama;
  - dotyk i mysz;
  - poprawione sprzątanie.
- Brak MIDI: nie ma FluidSynth ani soundfontu.
- Brak RTP: gry wymagające zainstalowanego RTP nie mają jego grafik i dźwięków.
  Rozwiązanie: wskazanie folderu RTP.
- Brak odtwarzania filmów.
- Menu gry nie ma mapowania kontrolera: profile są w `SharedPreferences`,
  a gra działa w innym procesie.
- Inne gry XP/VX/VX Ace mocno korzystające z Win32API lub własnych DLL
  trzeba sprawdzać pojedynczo.
- Budowanie silnika ze źródeł (Linux lub GitHub Actions) zamiast gotowego APK
  z cudzego wydania.

## Licencja

- mkxp-z jest na GPL. APK z tym silnikiem trzeba rozpowszechniać
  na **GPL-3.0-or-later** wraz z kodem źródłowym.
- Komponenty Apache-2.0 (OpenSSL 3, libc++) są zgodne tylko z GPLv3.
- Szczegóły: `app/src/main/assets/rgss/NOTICE.txt`.
- Do repozytorium i APK nie trafiają: pliki gier, RTP, DLL Enterbrain /
  Gotcha Gotcha Games.
- „RPG Maker” jest znakiem towarowym jego właścicieli; PadPort nie jest z nimi
  powiązany.
