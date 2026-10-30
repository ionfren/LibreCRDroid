# Eficiența Live Updates pe Wear OS

24 octombrie 2026. Optimizare a componentei Wear `WearLiveUpdatesNotifier`, cu
păstrarea recepției BLE, a valorilor noi, a stărilor NA/eroare și a expirării.

## Schimbări

| Situație | Procesarea afișării |
| --- | --- |
| Live Updates ascuns, fără recepție BLE locală | Fără abonamente la date și fără temporizator al acestei componente; notificarea precedentă se elimină o singură dată. |
| Live Updates ascuns, cu serviciu BLE local | Rămâne notificarea serviciului, actualizată la valori/stări noi și la expirare; fără bitmap Live Updates sau reîmprospătări în fiecare minut. Vârsta folosește timestampul afișat de sistem. |
| Live Updates activ | Valorile noi se afișează imediat; minutele continuă să avanseze și datele expiră la același termen. Actualizările duplicate se suprimă. |

- Schimbarea modului anulează și temporizatorul aflat în așteptare, nu doar
  afișarea notificării.
- Persistarea unei glicemii deja prezente în memorie nu mai produce o a doua
  notificare și nu mai repornește temporizatorul pentru aceeași stare efectivă.
- Înainte de notificare se compară câmpurile vizibile, marcajul temporal și modul
  de afișare. O valoare numerică identică, primită la un moment nou, rămâne o
  observație nouă și resetează vârsta imediat.
- Se ignoră schimbările setărilor de aspect care nu influențează notificarea;
  schimbarea unității rămâne observată.
- Canalul de notificări și PendingIntent-urile fixe se creează o singură dată pe
  proces. Generarea bitmapului nu se mai repetă pentru notificări identice.
- Callback-urile notificării folosesc setările din memorie; nu așteaptă citirea
  DataStore la pornire. Fluxurile au valori inițiale pentru a păstra calea live
  independentă de citirile din stocare.
- Nu s-au introdus wakelock-uri sau alarme noi. Alarma existentă de expirare
  rămâne necesară inclusiv pentru notificarea BLE în repaus.

## Particularitate observată pe ceas

În primul test de dezactivare, notificarea foreground nu mai avea marcajul
`PROMOTED_ONGOING`, însă `dumpsys wear_service` încă păstra vechea activitate
ongoing LibreCR, cu descrierea anterioară. Eliminarea metadatelor dintr-o simplă
actualizare de notificare nu a fost suficientă în acel test.

Corecția elimină notificarea foreground veche și înregistrează sincron notificarea
obișnuită pe aceeași instanță de serviciu, atunci când se dezactivează Live Updates.
Nu se oprește serviciul și nu se repornește managerul BLE. Referința necesară la
serviciu este slabă, pentru a nu păstra instanța după distrugere. În cazul fără
serviciu BLE local, eliminarea normală a cardului este suficientă.

## Validare

- 60 teste unitare Wear trecute, dintre care 8 noi pentru oprirea abonamentelor și
  a temporizatorului, reactivare, persistență duplicată, observații noi cu aceeași
  valoare, schimbarea proprietarului notificării și expirarea cu afișarea oprită.
- `assembleDebug`, `assembleRelease` și `lintRelease` reușite.
- APK-ul debug păstrează semnătura și datele aplicației instalate. APK-ul anterior
  verificării a fost salvat în directorul temporar al investigației.

Versiunea finală este **instalată pe ceas**. Testul comutatorului a folosit
interfața reală a aplicației și date glicemice reale, fără ștergerea sesiunii sau
injectarea unor valori sintetice:

| Captură (CEST) | Activități ongoing în Wear OS | Serviciu BLE / proces |
| --- | ---: | --- |
| 10:59:13, activ | 1 | SensorForegroundService, PID 15307 |
| 10:59:23, dezactivat | **0** | aceeași instanță și același PID |
| 10:59:33, reactivat | **1** | aceeași instanță și același PID |

La dezactivare rămâne notificarea obișnuită foreground a recepției BLE, fără
`PROMOTED_ONGOING`. Nu au apărut excepții noi în jurnalul de crash verificat.
După instalare au continuat valorile reale, inclusiv `lifeCount` 19386 și apoi 19389 (82 mg/dL) după încheierea testului;
activitatea ongoing reactivată a urmărit valorile primite.
Preferința Live Updates a fost restabilită la `enabled=true`, starea inițială.
Timeoutul ecranului, modificat temporar pentru testul UI, a fost restabilit la
valoarea originală de 10.000 ms.

Capturile sunt în `/tmp/pixel-watch-battery-20261024/live-final-before-off.txt`,
`live-final-off.txt`, `live-final-off-notifications.txt`, `live-final-on.txt`
și `live-efficiency-final.txt`. Aceste verificări validează separat păstrarea
serviciului BLE și eliminarea activității Live Updates din sistem.

## Fișiere

- `wear/.../live/WearLiveUpdatesNotifier.kt`: observare condiționată, deduplicare,
  resurse reutilizate și eliminarea activității vechi.
- `wear/.../live/WearLiveUpdateDelivery.kt`: modurile de afișare, compararea
  notificărilor și ciclul de observare testabil fără Android.
- `wear/.../live/WearLiveUpdateContent.kt`: termen de expirare fără ticker de minute
  pentru notificarea obișnuită a serviciului.
- `wear/.../data/WearAppearanceStore.kt`: acces sincron la setările deja memorate.

Această verificare stabilește lucrul eliminat și comportamentul aplicației;
nu reprezintă o măsurare izolată a economiei totale de baterie peste noapte.
