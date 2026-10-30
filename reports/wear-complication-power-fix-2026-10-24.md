# Fix consum complicații — Pixel Watch 4

**Stare curentă:** corecția suplimentară pentru cazul fără complicații active este
instalată. După verificarea inițială a sistemului, glicemiile următoare nu mai
produc interogări sau wakelock-uri pentru complicațiile inactive. Măsurătorile de
aproximativ 270 ms de mai jos descriu prima versiune a fixului, nu această corecție.

24 octombrie 2026. Implementat și instalat pe ceas prin actualizare ADB a APK-ului
**debug**, cu aceeași semnătură ca versiunea existentă; datele și permisiunile au
fost păstrate. APK-ul anterior a fost salvat temporar pentru revenire.

## Schimbare

- Fiecare valoare nouă, locală BLE sau reluată de pe telefon, solicită în continuare
  actualizarea imediată a ambelor surse glicemice active. Nu există debounce,
  temporizator suplimentar de actualizare sau reducere a frecvenței glicemiei.
- Data și bateria nu mai sunt solicitate la fiecare valoare. Actualizările lor
  periodice din manifest și cele cerute la pornire/schimbarea aspectului rămân.
- Wakelock-ul se achiziționează încă sincron, din callback-ul care a trezit ceasul.
  Se eliberează după răspunsurile tuturor instanțelor active și 250 ms pentru
  predarea asincronă către Wear SDK, în loc să expire obligatoriu după 3 secunde.
  Limita de siguranță de 3 secunde rămâne pentru răspunsuri lipsă sau blocate.
- Pe API 36+ se citesc instanțele active din sistem, inclusiv după restart sau
  schimbarea cadranului; sursele inactive nu produc așteptări fără răspuns.
  Dacă interogarea eșuează/expiră, rămâne calea compatibilă de solicitare.
- Generațiile de actualizare împiedică răspunsurile întârziate și eliberările
  deja programate să elibereze blocarea unei valori mai noi.
- Reafișarea datelor expirate și a stărilor de eroare ale senzorului rămâne activă.

Cod principal: `wear/.../wear/complication/LibreComplicationUpdater.kt`,
`ComplicationUpdateCompletion.kt`, `WearSdkActiveComplications.kt` și
`TrackedComplicationDataSourceService.kt`. Apelanții pentru glicemie/stare/expirare
folosesc acum `requestGlucose`; schimbările de aspect și pornirea folosesc
`requestAll`.

Comportamentul API-urilor a fost verificat în sursele AndroidX 1.3.0 utilizate de
proiect și în documentația oficială pentru
[ComplicationDataSourceUpdateRequester](https://developer.android.com/reference/androidx/wear/watchface/complications/datasource/ComplicationDataSourceUpdateRequester)
și [ComplicationsManager](https://developer.android.com/reference/com/google/wear/services/complications/ComplicationsManager).
Răspunsul sursei și predarea către sistem nu reprezintă confirmarea fiecărui pixel
randat pe cadran; perioada de 250 ms păstrează timp pentru această predare.

## Validarea primei versiuni

- 45 teste unitare Wear: trecute, inclusiv 9 cazuri noi pentru instanțe multiple,
  răspuns lipsă, schimbarea identificatorilor și concurența dintre valori.
- `assembleDebug`, `assembleRelease`, `lintRelease`: reușite.
- `lintDebug`: blocat de o eroare preexistentă în testul nelegat
  `WearLiveUpdatesSizingTest.kt:35` (`UseSdkSuppress`); sursele fixului nu au
  erori lint în varianta release.
- Serviciul foreground al senzorului a repornit automat după `MY_PACKAGE_REPLACED`.
  Nicio excepție nouă a aplicației nu apare în jurnalul de crash al verificării.
- La eșantionarea valorilor reale persistate au fost observate după instalare
  `lifeCount` 19332, 19333 și 19335 (înainte: 19331); valoarea s-a schimbat de la
  134 la 132 mg/dL. Nu au fost injectate valori sintetice.

Duratele de mai jos provin din diferențele câmpului **actual** al wakelock-ului
`LibreCR:ComplicationUpdate` din Batterystats, fără resetarea statisticilor:

| Captură (CEST) | Activări cumulate | Durată cumulată actual | Diferență |
| --- | ---: | ---: | --- |
| înainte de instalare | 31 | 93.162 ms | aproximativ 3.005 ms/activare |
| 10:05:33, după pornire | 33 | 94.428 ms | 1.266 ms pentru două activări de pornire |
| 10:06:13 | 34 | 94.701 ms | **273 ms** pentru următoarea activare |
| 10:07:16 | 35 | 94.963 ms | **262 ms** pentru următoarea activare |
| 10:08:52 | 37 | 95.507 ms | **544 ms** pentru încă două activări |

În ultimele trei capturi sistemul era `mState=IDLE`, `mLightState=OVERRIDE`;
în captura de la 10:06:13, `mWakefulness=Dozing`. Media celor patru actualizări
normale după captura de la 10:05:33 este 269,75 ms, aproximativ **91% mai puțin timp de wakelock** față de
mecanismul anterior. Aceasta nu este o reducere măsurată de 91% a consumului
bateriei întregului ceas.

## Clarificare: glicemia era afișată prin Live Updates

Utilizatorul a precizat ulterior că nu folosește de câteva zile complicațiile
glicemice. Verificarea suplimentară prin `dumpsys wear_service` confirmă situația
curentă: cadranul activ este `TubesWatchFaceService`, favoritul 199, cu doar două
configurații active: bateria sistemului (id 1045) și data sistemului (id 1046).
Niciuna nu aparține LibreCR. Configurațiile LibreCR din alte favorite/cache nu
reprezintă complicații active pe cadranul curent.

Același dump confirmă o activitate ongoing LibreCR, id 1, cu titlul
`LibreCR · glicemie live`; notificarea are indicatorul `PROMOTED_ONGOING`.
Prin urmare, recepția valorilor reale verificată după instalare validează fluxul
de glicemie/Live Updates în această configurație, nu randarea unor complicații
glicemice active. Interpretarea inițială a celor aproximativ 270 ms drept timp
de răspuns al complicațiilor active nu era demonstrată: în cazul fără instanțe
active, același timp provine din interogarea configurației și perioada de
siguranță de 250 ms. Randarea pe un cadran cu complicații LibreCR active nu a
fost validată pe dispozitiv în această sesiune.

Costul vechi rămâne demonstrat: `requestAll` achiziționa necondiționat wakelock-ul
de 3 secunde la fiecare valoare, chiar dacă nu exista nicio complicație LibreCR
activă. Fixul elimină cererile către sursele inactive după interogarea sistemului
și reduce această menținere inutilă a CPU. Codul Live Updates nu a fost modificat.
Nu există dovezi suficiente pentru a atribui întregul consum Live Updates sau
pentru a exclude costurile sale indirecte din sistem.

Capturi suplimentare: `/tmp/pixel-watch-battery-20261024/wear-service-followup.txt`
și `/tmp/pixel-watch-battery-20261024/live-followup.txt`. Constatarea despre
configurația curentă nu constituie singură o captură a configurației din noaptea
investigată; pentru aceasta există contextul comunicat de utilizator.

## Corecție suplimentară: zero wakelock recurent fără complicații active

Prima versiune aplica și configurației goale perioada de predare de 250 ms;
aceasta nu era necesară, fiindcă nu exista nicio predare de date. Corecția
ulterioară, instalată tot prin actualizare a APK-ului debug, face două lucruri:

- La o interogare care confirmă lipsa surselor active, blocarea inițială se
  eliberează imediat, fără cele 250 ms.
- Rezultatul negativ se păstrează doar în memoria procesului. Cererile următoare
  pentru acele surse se încheie fără coroutine, interogări ale sistemului sau
  wakelock-uri. Nu se persistă o dezactivare în setările utilizatorului.

`InactiveComplicationCache` se invalidează la activare, dezactivare și orice
cerere de date a sistemului. Un contor de versiune împiedică o interogare veche
care revine după activare să reactiveze rezultatul negativ. Repornirea procesului
determină o nouă verificare. Calea pentru complicațiile active păstrează protecția
de predare a datelor și limita de siguranță. Callback-urile au fost verificate
în [documentația ComplicationDataSourceService](https://developer.android.com/reference/androidx/wear/watchface/complications/datasource/ComplicationDataSourceService).
Codul Live Updates nu a fost schimbat.

Validare a corecției: **52 teste unitare trecute**, inclusiv 7 cazuri noi pentru
rezultate necunoscute, surse active/inactive diferite, activare și rezultate
întârziate; `assembleDebug`, `assembleRelease` și `lintRelease` reușite.

După instalare a existat o pauză de reconectare: dump-ul Bluetooth arată tentative
GATT închise, apoi mecanismul existent de recuperare/transfer a lăsat telefonul
să recepționeze. Captura de la 10:19:48 nu a fost tratată ca dovadă de recepție
nouă. Validarea funcțională folosește valorile reale observate ulterior pe ceas,
fără injectarea unor date sintetice:

| Captură (CEST) | Live Updates | Activări ComplicationUpdate | Durată actual cumulată |
| --- | --- | ---: | ---: |
| 10:19:48 | valoarea anterioară 105 mg/dL | 48 | 98.326 ms |
| 10:23:25 | valoare nouă 107 mg/dL, lifeCount 19349 | 48 | 98.326 ms |
| 10:24:44 | valoare nouă 103 mg/dL, lifeCount 19351 | 48 | 98.326 ms |

Sistemul era în Doze (`mState=IDLE`, `mLightState=OVERRIDE`) în ultima captură.
**Creștere măsurată a wakelock-ului pentru complicații: 0 activări, 0 ms**, în
timp ce valorile glicemice și activitatea ongoing afișată de Wear OS s-au
actualizat. Nu au apărut excepții noi în jurnalul de crash verificat. Există în
continuare costul necesar recepției și afișării glicemiei; rezultatul de zero se
referă strict la wakelock-ul suplimentar pentru complicațiile inactive.

Capturi: `no-complications-installed-1.txt`, `no-complications-installed-3.txt`
și `no-complications-installed-final.txt` din directorul temporar al investigației.

## Legătura cu actualizarea Wear OS

Apariția consumului după OTA este context relevant, dar nu există o captură
comparabilă anterioară actualizării care să demonstreze mecanismul regresiei.
Costul fix de 3 secunde este documentat și în auditul local din 15 octombrie;
optimizarea înlătură un cost verificat al aplicației fără a atribui întreaga
scurgere LibreCR sau actualizării OS. Efectul asupra consumului total de noapte
necesită o comparație în condiții similare după instalare.

Capturile ADB și APK-ul anterior sunt în directorul temporar
`/tmp/pixel-watch-battery-20261024`; APK-urile noi se află în
`wear/build/outputs/apk/debug/wear-debug.apk` și
`wear/build/outputs/apk/release/wear-release.apk`.
