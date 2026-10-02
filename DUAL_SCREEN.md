# Dual screen — wytyczne dodawania obsługi gier

Drugi ekran ma pokazywać **treść działającej gry**: np. notatnik, drużynę,
ekwipunek albo mapę. Profil projektujemy dla konkretnego tytułu, jego mechanik
i skryptów. Wspólne elementy PadPort zapewniają wyświetlacz, ustawienie i transport.

**Jedna rozgrywka, jeden stan i jeden system zapisów.** Na dolnym ekranie działa
lekki panel, który czyta stan pierwszego silnika i ewentualnie przekazuje mu
polecenia. Nie uruchamiamy tam drugiej kopii gry.

## 1. Co można dodawać

Poniższe pozycje to pomysły na profile, nie lista gotowych funkcji każdej gry.

| Element | Zastosowanie | Co trzeba ustalić w grze |
|---|---|---|
| Drużyna | Portrety, zdrowie, zasoby, widoczne statusy, wyposażenie | Aktualny skład i sposób odczytu statystyk; jakie dane gra pokazuje graczowi |
| Notatnik / dziennik | Zebrane notatki, aktualne cele, opisy postaci | Warunki zdobycia/odblokowania wpisów, treść w używanym języku |
| Przedmioty | Lista, liczby sztuk, opisy, wybór celu użycia | Oryginalna obsługa przedmiotu, dostępność menu, zużycie i efekty |
| Mapa | Bieżący obszar, odwiedzone pokoje, odkryte przejścia | Współrzędne, połączenia obszarów, odkrycie lokacji; nie zawsze istnieje gotowa minimapa |
| Kodeks / wskazówki | Poznane postacie, odkryte informacje, zdobyte wskazówki | Flagi odkrycia; sama obecność tekstu w bazie nie oznacza, że gracz go zna |
| Historia dialogu / walki | Już wyświetlone kwestie lub komunikaty | Moment faktycznego pokazania tekstu, mówca, czyszczenie historii po nowej grze/wczytaniu |
| Komendy walki | Umiejętności, przedmioty, wybór przeciwnika/celu | Aktywna tura, koszty, legalne cele i oryginalne zatwierdzanie akcji |
| Panel zagadki | Odkryte wskazówki, elementy aktualnej łamigłówki | Aktywna zagadka, dostępne elementy i jej rzeczywista logika |

Najprościej zacząć od **podglądu informacji**. Akcje dodajemy po sprawdzeniu,
która funkcja gry obsługuje je w całości. Mapa, walka czy zagadka mogą wymagać
osobnego adaptera; nie zakładamy, że standardowe API RPG Makera opisuje wszystko.

## 2. Obecne profile jako wzorce

### Look Outside — MZ

- Stan drużyny, portrety, HP/stamina, statusy i wyposażenie.
- Wyposażenie ma nazwę i małą ikonę po jej lewej stronie (`iconIndex` z gry).
  Bez ikony pozostaje nazwa; zmiana HP nie odtwarza tych elementów od nowa.
- Ekwipunek na dole otwiera **wyłącznie przycisk dolnego panelu**. Zwykła pozycja
  w menu gry nadal otwiera ekwipunek na głównym ekranie.
- Adapter oznacza konkretną instancję `Scene_Item` utworzoną swoim poleceniem.
  Nie przechwytuje wszystkich scen tej klasy.
- Wybór i użycie przedmiotu trafiają do oryginalnych okien i metod gry,
  w tym nadpisanego przez Look Outside `Scene_Item.onItemOk`.
- W walce na dole jest podgląd drużyny. Komendy walki i zmiana wyposażenia
  nie zostały przeniesione.
- Nazwa przycisku ekwipunku pochodzi z `TextManager.item`; font z bieżącej
  konfiguracji gry. Tło jest czarne, zawartość i niepełne rzędy kart wyśrodkowane.
- Logo jest opcjonalne, półprzezroczyste; przy braku obrazka pozostaje tytuł.

Pliki: `app/src/main/assets/look-outside-dual.js`, `look-outside-panel.html`,
`look-outside-panel.js`.

### To the Moon — XP/RGSS

- Notatnik z zakładkami Postacie / Notatki / Przedmioty.
- Biogramy tylko aktualnych postaci, wpisy tylko z posiadanych przedmiotów.
  Klasyfikacja notatek korzysta z `NoteText` gry, także ze spolszczenia.
- Panel jest podglądem; przeglądanie nie zużywa przedmiotów ani nie zmienia postępu.
- Początek i tytuł są rozpoznawane również przez `$ttm_title_screen` i mapę
  startową. Dane poprzedniej rozgrywki nie są wyświetlane na planszy tytułowej.
- Stan odczytuje preload Ruby w wątku silnika, przez hook `Graphics.update`.

Pliki: `app/src/main/assets/rgss/padport_ttm_dual.rb`, `to-the-moon-panel.html`,
`to-the-moon-panel.js`. Szczegóły odtwarzacza: [RGSS.md](RGSS.md).

### Fear & Hunger — MV (tylko podgląd)

- Rozpoznanie: silnik MV i tytuł dokładnie „Fear & Hunger” (nie Termina).
- Drużyna z portretami 96 × 96 z `faceName/faceIndex` — gra podmienia plik
  twarzy przy utracie kończyny (`Actor1` → `Actor1L`/`R`), więc klucz miniatury
  zawiera nazwę pliku. Paski z nazwami `TextManager.hpA/mpA` (w grze: Body / Mind).
- Utracone kończyny to stany bez ikony: 3 *Arm cut*, 14 *Leg cut*, 31 *Headless*
  (nazwy z bazy gry, także ze spolszczenia). Pozostałe stany tylko z `iconIndex > 0`
  (głód, strach, krwawienie, zakażenia...); techniczne stany bez ikony są pomijane.
- Font MV: `GameFont` z `fonts/gamefont.css` (Eczar), odczytany z `document.styleSheets`;
  w MZ nadal `FontManager._urls`.
- `Scene_Boot`, `Scene_Title`, mapa przed tytułem (`Scene_PretitleMap` z
  HIME_PreTitleEvents) i `Scene_Gameover` → tryb `waiting`, bez kart drużyny.
  W walce ten sam podgląd (nazwa mapy, bez dodatkowego napisu). Spadek Ciała
  w tej samej walce (`epoch` + numer sceny `scene`) potrząsa kartą postaci jak
  w Look Outside (200 ms, pomijane przy „ogranicz ruch”); nowa walka, leczenie
  i obrażenia na mapie nie animują karty.
- Adapter ignoruje polecenia z panelu; panel ich nie wysyła.

Pliki: `app/src/main/assets/fear-and-hunger-dual.js`, `fear-and-hunger-panel.html`,
`fear-and-hunger-panel.js`; testy `tests/fear-and-hunger-dual.test.cjs`
(`FEAR_AND_HUNGER_GAME` sprawdza bazę prawdziwej gry).

### Welcome to Elderfield — MZ (karta, HUD, menu przedmiotów)

- Rozpoznanie: MZ i tytuł „Welcome to Elderfield”; ta sama gra ma profil
  zgodności `GameCompat` (bez `require`, FOSSIL, tabele, radio).
- Karta jedynej postaci: portret z kreatora (`KC_CompositeBitmaps` — nowa bitmapa
  po zmianie wyglądu, więc w kluczu jest jej numer), poziom, HP/MP, energia (V1475),
  stany z ikonami, wyposażenie. Drżenie przy spadku HP w tej samej walce.
- HUD: V1618 godzina, V1619 dzień/pora roku, złoto (animowane „+N/−N”).
- Niebo: V123 pora dnia (1 świt, 2 dzień, 3 zmierzch, 4 noc), V125 pora roku
  (0–3), pogoda V243 (2/6/7/8 deszcz, 4 burza, 3 pochmurno) i przełączniki
  1121/1123 (śnieg), 1122 (burza). Płynne przejścia przez `@property`;
  animacje deszczu/śniegu wyłączane przy „ogranicz ruch”.
- Menu przedmiotów: przycisk otwiera `Scene_Item` z mapy (jak skrót gry, CE 313).
  Górne menu zostaje widoczne — okna akcji, celu i wyrzucania są poza warstwą
  okien i reagują na dotyk, więc ich nie ukrywamy. Panel jest pilotem:
  kategoria → `select` + `setCategory` (bez `processOk`, który tu nic nie robi),
  przedmiot → najpierw `deactivate()` kategorii, potem `activate/select` listy,
  „Użyj” → `processOk()` listy (DM_ItemActions: Use/Eat, okno akcji, zdarzenie
  wspólne, wyrzucenie). Otwarte okno gry (`_confirmationCommands`,
  `_customItemActionWindow`, `_itemActionWindow`, `_actorWindow`) przejmuje
  wejście i jest pokazywane jako przyciski (`select(i)` + `processOk()`,
  „Wstecz” = `processCancel()`). Etykieta „Użyj” z notetagu `<actions>`
  (np. „Eat”), puste `<actions>` = nieużywalne. Zaślepka `isDummyItem` pomijana.
- Nazwy, opisy i kategorie przez `WTE_Translate` (tłumaczenia gry),
  kody `\c[n]`/`\i[n]` usuwane, `<br>` → nowa linia.

Pliki: `app/src/main/assets/elderfield-dual.js`, `elderfield-panel.html`,
`elderfield-panel.js`; testy `tests/elderfield-dual.test.cjs` (`ELDERFIELD_GAME`).

## 3. Ustawienie i zachowanie wspólne

1. Tryb jest opcjonalny, domyślnie wyłączony i przypisany do konkretnej gry.
2. Włącznik jest w menu karty oraz menu podczas grania. Używaj `Ui.menuDialog`:
   akcje, checkboxy i opis są w jednej przewijanej kolumnie, a `Ui.dialogContent`
   zapewnia wspólne marginesy. Nie łącz `AlertDialog.setItems()` z osobnym
   `setView(settings)` — na niskim ekranie oba panele konkurują o wysokość,
   a ustawienia mogą zostać ucięte bez możliwości przewinięcia.
3. Przy **pierwszym dodawaniu obsługiwanej gry**, gdy dostępny jest drugi
   wyświetlacz, pytamy o aktywację. W RGSS pytanie następuje po zgodzie na
   eksperymentalny silnik. Odświeżenie indeksu nie pyta ponownie.
4. Ikona dwóch ekranów na karcie oznacza zapisane włączenie opcji. Nie jest
   potwierdzeniem, że dodatkowy ekran jest w danej chwili podłączony.
5. Ustawienie przechodzi przez `Library.setDualScreen` / `DualScreenPreference`.
   Plik `dual-screen-<id>.enabled` jest odczytywany przez proces główny i `:rgss`.
   Nie zapisuj osobnego przełącznika w cache SharedPreferences procesu RGSS.
6. Wyłączenie, utrata wyświetlacza i przejście aplikacji w tło kończą interakcje
   panelu. Przeniesione okna gry i ich obsługa wejścia muszą wrócić do normalnego stanu.
7. Nie wpisujemy na sztywno identyfikatora drugiego ekranu ani nazwy modelu
   urządzenia. Korzystamy z `SecondaryDisplays` i `DisplayManager`.

## 4. Przed implementacją nowego profilu

Sprawdź w plikach i kodzie gry:

- silnik, rzeczywiste sceny/menu, pluginy i nadpisane metody;
- źródło danych potrzebnych panelowi i to, kiedy stają się dostępne;
- znaczenie pól — np. „MP” może oznaczać staminę, a przedmioty mogą być notatkami;
- start, intro, ekran tytułowy, nową grę, wczytywanie, mapę, rozmowę, walkę,
  menu i minigry. Ekran tytułowy bywa zwykłą mapą ze zdarzeniami;
- oryginalne blokady menu i obsługę akcji. Testuj na wersji gry ze zmienionymi
  skryptami/spolszczeniem, jeżeli taką wersję ma użytkownik;
- skąd pochodzą nazwy, opisy, portrety, ikony i używany krój pisma.

Zapisz krótką specyfikację stanów: **co widać na każdym ekranie i które akcje
są dostępne w danej sytuacji**. Dla nieznanej sceny wybierz neutralny podgląd
bez aktywnych poleceń, zamiast zgadywać jej działanie.

## 5. Architektura i miejsca do podłączenia

Klasy Java są w `app/src/main/java/pl/padport/app/`.

| Element | Rola |
|---|---|
| `DualScreenProfile` | Rozpoznanie tytułu i silnika; czy istnieje adapter |
| `DualScreenOptions`, `MainActivity`, `GameCard` | Opis, przełącznik, pytanie przy dodaniu i oznaczenie karty |
| `DualScreenPreference`, `Library` | Zapamiętanie ustawienia także między procesami |
| `SecondaryDisplays`, `DualScreenSession` | Wybór wyświetlacza, `Presentation`, gotowość panelu, cykl życia |
| `DualScreenChannel` | Stan aktywności, generacja `epoch`, ograniczona kolejka poleceń |
| `PlayerActivity.Host` | Most JavaScript ↔ Java dla WebView |
| `GameHttpServer`, `gecko-host.js` | Odpowiednik mostka dla GeckoView |
| `CompanionFont` | Walidacja ścieżek i typów fontów z folderu gry |
| `RgssRuntime`, `RgssActivity`, `RgssCompanionBridge` | Preload Ruby, cykl życia RGSS i transport plikowy |

### Uwaga przy dodaniu trzeciego profilu

Obecny kod ma kilka rozgałęzień **To the Moon / Look Outside / Fear & Hunger**.
Strona panelu pochodzi już z `DualScreenProfile.panel(profile)`. Nie wystarczy
dopisać tytułu do `DualScreenProfile.identify()`:

- dodaj jawny wybór odpowiedniego `*-panel.html/js` w `DualScreenSession`;
- dodaj właściwy opis w `DualScreenOptions.description()`;
- podłącz skrypt adaptera w obu ścieżkach MV/MZ (`GameWebClient` i `GameHttpServer`)
  albo preload i transport w RGSS;
- sprawdź przekazanie źródła gry do sesji, jeśli panel korzysta z plików fontów;
- dodaj identyfikację negatywną: inna gra lub inny silnik nie mogą dostać tego adaptera.

Przy większej liczbie profili warto zastąpić te rozgałęzienia jawnym rejestrem
profil → panel/opis/adapter. Nie pozwól, aby nowy profil po cichu dostał panel Look Outside.

## 6. MV/MZ: adapter, stan i polecenia

Adapter jest dołączany **w pamięci przy ładowaniu strony**. Pliki źródłowe gry
pozostają czytane przez `GameSource`; nie wymagamy ich przepisywania.

Aktualny przepływ:

1. Panel dolnego ekranu ustawia `window.GameCompanionPanel.update(state)`
   i zgłasza `GameCompanionHost.ready()` po inicjalizacji.
2. Dopiero gotowy panel aktywuje kanał. Nie chowaj okien głównej gry, zanim
   drugi ekran będzie gotowy do ich obsługi.
3. Adapter cyklicznie czyta `PadPortHost.dualPoll()` i publikuje JSON przez
   `PadPortHost.dualSnapshot(payload)`. W Gecko te operacje są asynchroniczne.
4. Panel wysyła `GameCompanionHost.command(JSON.stringify(command))`.
5. Adapter sprawdza polecenie w aktualnym stanie silnika i wywołuje jego normalną logikę.

Przykład istniejącej komendy Look Outside:

```json
{"action":"inventory","epoch":17,"scene":42}
```

- `epoch` to generacja połączenia z dolnym ekranem. Zmiana aktywności odrzuca
  stare dane i kolejkę. Nie zastępuj jej stałą wartością.
- `scene` identyfikuje konkretny obiekt sceny w adapterze, nie sam numer mapy.
- Dla nowej akcji zaktualizuj listę `ACTIONS` w `DualScreenChannel` oraz
  weryfikację po stronie gry. Nie przesyłaj kodu do `eval`.
- Limity bieżącego kanału: 16 oczekujących komend, 4096 znaków na komendę,
  512 × 1024 znaków na snapshot ścieżki JS. Wysyłaj potrzebne dane i małe miniatury,
  zamiast całej bazy gry lub pełnych atlasów.
- Jeden trwający odczyt na raz; wysyłaj nowy snapshot tylko po zmianie treści.
  Chroni to przed kolejką opóźnionych stanów i zbędną pracą renderera.

### Akcje muszą przechodzić przez grę

Przycisk „Użyj” ma wejść w istniejący wybór przedmiotu/celu i jego zatwierdzenie.
Nie implementuj osobno odejmowania sztuki, dodawania HP, kosztów umiejętności,
zdarzeń wspólnych ani skutków śmierci — gra i jej pluginy już to obsługują.

Przed wykonaniem ponownie sprawdź dostępność, aktualny przedmiot, cel, scenę
i generację. Kliknięcie mogło dotrzeć po rozpoczęciu rozmowy lub zmianie mapy.

### Ruch a dostępność menu

Chód i sprint nie powinny powodować migania przycisku. W Look Outside przycisk
jest dostępny podczas ruchu, a kliknięcie czeka na koniec kroku w
`Scene_Map.updateCallMenu`. To punkt aktualizacji gry, a nie losowy moment
rzadkiego odpytywania panelu. Rozmowa, walka, transfer, pauza i skryptowa blokada
anulują takie oczekiwanie. Zwykłe menu gry zachowuje pierwszeństwo.

Przycisk przygasa przy rzeczywistej niedostępności. Nie zastępuj poprawnej
klasyfikacji stanu arbitralnym opóźnieniem odblokowania.

### Przeniesienie interfejsu

Oznaczaj tylko scenę utworzoną z dolnego panelu. Zwykłe otwarcie menu gry ma
zachować dotychczasowy ekran. Zapamiętaj i odtwórz wszystkie zmienione właściwości
oraz podmienione funkcje, także po błędzie i rozłączeniu wyświetlacza.

W MZ `WindowLayer.render()` sprawdza `visible`, a nie `renderable`.
Ukrycie warstwy nie powinno wyłączać aktualizacji okien obsługiwanych padem.
Jednocześnie wyłącz obsługę dotyku niewidocznych okien na głównym ekranie —
dotknięcie tła nie może przypadkiem wybrać niewidocznego przedmiotu.

## 7. RGSS: odczyt w wątku Ruby

- Preload dodawaj tylko do rozpoznanego profilu, po wspólnej konfiguracji
  kompatybilności. Kopię adaptera umieszczaj w prywatnych danych PadPort.
- Odczyt obiektów Ruby rób w wątku silnika. TTM używa hooka `Graphics.update`
  i odświeżania co 250 ms; nie czytaj tych obiektów z wątku Androida.
- Bridge TTM wymienia atomowe `control.json` i `state.json` w
  `files/rgss/<id>/companion/`, poza `game/` i `saves/`.
- Kontrola obejmuje `epoch`, losowy identyfikator sesji i świeży heartbeat.
  Nie przyjmuj stanu pozostawionego przez poprzednie uruchomienie silnika.
- Błąd transportu nie powinien przerwać `Graphics.update` ani rozgrywki.
- W procesie `:rgss` skonfiguruj osobny katalog WebView przed jego utworzeniem
  (`rgss-companion` na Androidzie 9+).

**Obecny adapter TTM jest tylko do odczytu.** Samo umieszczenie `commands`
w nadpisywanym pliku kontrolnym nie tworzy niezawodnej obsługi akcji. Nowy
interaktywny profil RGSS wymaga kolejki z identyfikatorami, potwierdzeń odbioru
i ochrony przed powtórnym wykonaniem, a następnie walidacji w wątku Ruby.

## 8. Teksty, fonty, grafiki i stabilny widok

### Treść z gry

- Nazwy pozycji, przedmiotów, opisów i postaci pobieraj z działającej gry.
  Uwzględniaj spolszczenie, a nie wyłącznie język aplikacji.
- Użyj np. `TextManager.item` zamiast wpisywać na sztywno „Inventory”.
  Nowe etykiety, które należą do panelu i nie mają odpowiednika w grze,
  trafiają do katalogu EN/PL `ui-strings.js` lub natywnych zasobów.
- Formatuj tekst gry pod panel: rozwiąż obsługiwane zmienne/escape codes,
  zachowaj podział wierszy i przypisanie mówców tam, gdzie ma to znaczenie.
  Do DOM wstawiaj tekst jako `textContent`, nie jako HTML z opisu przedmiotu.

### Font

- W Look Outside odczytujemy rodzinę bieżącego okna / `mainFontFace()`
  i ścieżkę z `FontManager._urls`. Panel dostaje plik z tego samego folderu gry.
- Używaj `GameSource` i lokalnego endpointu `game-font` / `CompanionFont`.
  Nie otwieraj arbitralnych ścieżek z metadanych i nie kopiuj fontu do APK.
- Ładuj asynchronicznie, raz dla aktualnej konfiguracji. Zignoruj spóźniony
  wynik starego ładowania. Przy błędzie pozostaw czytelną czcionkę systemową.
- Ten mechanizm używa istniejącego fontu. Nie skanuje glifów i nie podejmuje
  za użytkownika decyzji o transliteracji lub naprawie kroju.

### Obrazki — jak uniknąć znikających portretów

1. Zachowuj kartę i elementy DOM według stabilnego ID postaci. Aktualizacja HP
   nie powinna usuwać karty, zmieniać `src` portretu ani tworzyć nowego `<img>`.
2. Przy zmianie kolejności drużyny przenoś istniejące elementy.
3. Ostatni załadowany portret zostaje widoczny, gdy chwilowo brakuje nowych pikseli.
4. Zmianę portretu przygotuj poza widokiem: `load` → `decode` → podmiana gotowego
   elementu. Błąd ładowania nie kasuje starego obrazu.
5. Callback musi sprawdzać generację żądania oraz istnienie tej samej karty.
   Późne zakończenie ładowania nie może przywrócić usuniętej postaci ani starej twarzy.
6. Cache gotowych miniatur sprawdzaj **przed** gotowością źródłowej bitmapy.
   `ImageManager` może przeładowywać plik, mimo że panel już ma jego poprawny PNG.
7. Ogranicz pamięć i utrzymuj często używane portrety w cache. Look Outside
   używa miniatur twarzy 64 × 64 i ikon 24 × 24, z limitem 512 wpisów cache.
8. Po wyjściu do tytułu/zmianie składu usuń nieaktualne karty. Zapamiętany portret
   nie może ujawniać postaci z poprzedniej rozgrywki.

Logo z sieci traktuj jako opcjonalne: panel startuje od razu z tytułem tekstowym,
a obraz zastępuje go po udanym wczytaniu. Nie wstrzymuj działania panelu dla logo.

### Układ i przewijanie

- Dopasuj wygląd do gry, zapewnij kontrast i rozsądny rozmiar celów dotykowych.
- Wyśrodkuj cały panel oraz niepełne rzędy kart. Sama wyśrodkowana dwukolumnowa
  siatka nadal zostawia pojedynczą kartę w lewej kolumnie.
- Długa treść musi dać się przewijać od początku; pionowe centrowanie nie może jej ucinać.
- Przewijaj do opisu po wyborze przedmiotu, także przy ponownym dotknięciu tego
  samego wpisu. Nie wymuszaj przewijania przy każdym snapshotcie lub zmianie kategorii.
- Zmiana karty w przycisk celu nie powinna przebudowywać portretu. Zachowaj
  działanie dotyku i klawiatury oraz właściwe role/stan niedostępności dla dostępności.

### Lekkie efekty zdarzeń

- Krótkie drżenie po obrażeniach może używać animacji `transform` istniejącej
  karty. Unikaj animowania rozmiarów/marginesów i przebudowywania obrazków.
- Obecnie Look Outside reaguje na spadek HP między snapshotami tej samej sceny
  walki: 200 ms, maksymalnie 4 px przesunięcia. Jest to detekcja spadku HP,
  nie osobnego zdarzenia trafienia — obejmuje też inne przyczyny utraty HP w walce.
- Nie uruchamiaj efektu przy wejściu w scenę, zmianie generacji, leczeniu lub
  identycznym odświeżeniu. Kolejny efekt zastępuje poprzedni, zamiast je kolejkować.
  Usunięcie karty anuluje animację. Respektuj `prefers-reduced-motion`.

## 9. Workflow dodania gry

1. Rozpoznaj grę i spisz scenariusze z sekcji 4.
2. Wybierz pierwszą użyteczną funkcję i źródła danych; zacznij od podglądu.
3. Dodaj profil, opis, panel i właściwy adapter/transport — wszystkie miejsca
   z sekcji 5, z zachowaniem wspólnego ustawienia, ikony i pytania.
4. Sprawdź cykl życia i powrót do oryginalnego interfejsu przed dodaniem akcji.
5. Dodaj akcje przez oryginalne metody gry, z identyfikacją sceny i rewalidacją stanu.
6. Dodaj teksty EN/PL, obsługę treści/fontu z gry oraz stabilne obrazy.
7. Przetestuj logikę i kompatybilność z rzeczywistymi skryptami. Opisz zakres
   profilu i ograniczenia w DEVELOPMENT.md / README.md / instrukcjach.
8. Po wyraźnym zleceniu buildu sprawdź nowy APK na urządzeniu z dwoma ekranami.

## 10. Weryfikacja i uczciwy status

**APK budujemy wyłącznie na wyraźną prośbę użytkownika** — patrz [AGENTS.md](AGENTS.md).
Do pracy nad kodem używaj testów bez zadań `assemble*` i bez pakowania.

```powershell
node --test tests/dual-screen.test.cjs tests/companion-panels.test.cjs tests/fear-and-hunger-dual.test.cjs
& ".tools/gradle-8.9/bin/gradle.bat" --no-daemon --console=plain testDebugUnitTest lintDebug
python tests/test_localization.py
```

Testy logiki z rzeczywistymi skryptami (odczyt plików, bez uruchamiania gry):

```powershell
$env:LOOK_OUTSIDE_GAME="F:\DepotDownloaderMod\Look Outside"
node --test tests/dual-screen.test.cjs
$env:FEAR_AND_HUNGER_GAME="F:\DepotDownloaderMod\Fear & Hunger"
node --test tests/fear-and-hunger-dual.test.cjs
# Ruby 3.1+ lub java -jar jruby-complete.jar tests/ttm-dual-test.rb
ruby tests/ttm-dual-test.rb
```

`TTM_MENU_SCRIPT` może wskazywać wyeksportowany skrypt zawierający
`Window_Notes` i `Window_MenuItem`, aby porównać wybór wpisów z oryginałem.
Gradle wymaga skonfigurowanego JDK 17 i SDK projektu.

Scenariusze do sprawdzenia:

- pierwszy start, tytuł wykonany na mapie, intro, nowa gra i wczytanie;
- wejście przez menu głównej gry oraz oddzielnie przez przycisk dolnego panelu;
- chód/sprint, rozmowa, scenka, walka, zmiana mapy, blokada menu;
- zużyty/nieposiadany przedmiot, nieprawidłowy cel, powtórzony lub spóźniony dotyk;
- pauza, odłączenie/wyłączenie ekranu, powrót, zmiana generacji;
- aktualizacja HP bez odtwarzania portretu, zmiana twarzy, brak/awaria/dekodowanie
  obrazu, przestawienie/usunięcie członka drużyny, spóźnione callbacki;
- nazwy ze spolszczenia przy innym języku aplikacji, brak logo/fontu;
- tekst dłuższy niż ekran, przewijanie i wybór celu dotykiem.

Testy stanu i DOM nie potwierdzają fizycznego renderowania, wydajności GPU,
przydziału dotyku między ekranami ani zachowania `Presentation` na danym firmware.
W statusie profilu rozróżniaj **kod/testy automatyczne** od **próby na konkretnym
urządzeniu, buildzie APK i wersji gry**.
