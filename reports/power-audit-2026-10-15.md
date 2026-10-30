# Verificarea consumului energetic — Pixel Watch 4

Data: 15 octombrie 2026. Aplicația instalată: LibreCR 0.1.5, versionCode 4,
actualizată pe ceas la 10 octombrie 2026, 10:46:04.

## Concluzie

Transferul automat este oprit și pe ceas (`enabled=false`), nu doar declarat
oprit pe telefon. Captura nu arată o buclă continuă de reconectare sau un wake
lock LibreCR rămas activ permanent. Nu există o măsurătoare comparabilă dinaintea
modificării, deci această verificare nu poate confirma sau exclude o creștere
istorică a consumului.

Cea mai mare durată de wake lock din LibreCR provine din actualizarea
complicațiilor cadranului, nu din reconectare. În cod există o perioadă fixă de
3 secunde pentru fiecare actualizare, iar serviciile de dată și baterie sunt
solicitate și ele la fiecare valoare glicemică. Jurnalizarea detaliată este pornită.
Acestea sunt ținte concrete pentru o optimizare ulterioară; contribuția exactă a
fiecăreia la consumul bateriei nu a fost măsurată separat.

## Captura inițială

Sursa: `adb shell dumpsys batterystats --charged re.abbot.librecr.app`,
`dumpsys battery`, `dumpsys power`, `dumpsys cpuinfo`, serviciile aplicației și
preferințele proprii ale aplicației. Rapoartele au fost citite fără resetarea
statisticilor, fără instalarea unui APK și fără oprirea conexiunii la senzor.

| Indicator | Rezultat |
| --- | --- |
| Interval de la ultima încărcare | 13 h 0 min 14,989 s |
| Capacitate estimată de Android | 479 mAh |
| Nivel curent | 63% |
| Scădere raportată de sistem | 36–37 puncte procentuale; 182 mAh |
| Ritm mediu al ceasului în acest interval | aproximativ 2,8 puncte procentuale/oră |
| Consum atribuit direct UID-ului LibreCR | aproximativ 8,75 mAh, estimare Android |
| Echivalent din capacitatea de 479 mAh | aproximativ 1,83 puncte procentuale |
| Transfer automat | oprit |
| Jurnal de diagnostic | pornit |
| CPU LibreCR în eșantionul `cpuinfo` | 0,8%; nu reprezintă media celor 13 ore |
| Wake lock LibreCR activ la captură | niciunul |

Estimările pe UID nu includ obligatoriu toate costurile indirecte din procesele
partajate Bluetooth, Google Play services și sistem. Valoarea de 8,75 mAh nu
reprezintă o măsurătoare izolată a întregului cost al aplicației. Estimările
componentelor și UID-urilor nu trebuie adunate ca măsurători independente.

## Wake lock-uri LibreCR în intervalul inițial

Duratele de mai jos folosesc câmpul `actual` din Batterystats, nu timpul
`blamed`, care repartizează perioadele suprapuse între UID-uri.

| Blocare | Număr | Durată efectivă cumulată |
| --- | ---: | ---: |
| `LibreCR:ComplicationUpdate` | 767 | 38 min 17,499 s |
| `LibreCR:BleRecovery` | 6 | 64,457 s |
| `LibreCR:BleHandshake` | 3 | 13,435 s |

Sunt durate de menținere a procesorului disponibil, nu durate de utilizare
continuă a CPU la putere maximă și nu măsurători directe de energie.

A doua captură a fost luată după 145,850 secunde. În acest interval:

- `ComplicationUpdate`: încă 3 activări și 9,017 secunde cumulate.
- `BleRecovery`: nicio activare suplimentară.
- `BleHandshake`: nicio activare suplimentară.

Acest eșantion confirmă mecanismul de aproximativ 3 secunde per actualizare
în timpul funcționării curente, fără reconectări în intervalul urmărit.

## Legătura cu implementarea

- `wear/.../wear/complication/LibreComplicationUpdater.kt`: wake lock de 3.000 ms,
  eliberat doar prin expirarea termenului; toate cele patru servicii sunt solicitate
  la fiecare apel `requestAll`. Codul exista deja în istoricul Git, înainte de
  implementarea transferului automat.
- Manifestul ceasului prevede actualizarea datei la 3.600 s și a bateriei la 300 s,
  însă apelurile la fiecare valoare le solicită aproximativ o dată pe minut.
- În cele 13 ore apar 780 lansări ale serviciului de dată și 773 ale serviciului
  bateriei, alături de actualizările glicemiei. Aceste numere descriu lansări de
  servicii, nu procese Android noi.
- Comutatorul oprit dezactivează termenul suplimentar de 20 s pentru reconectare,
  precum și solicitările automate de transfer de pe ceas. Urmărirea numărului de
  eșecuri rămâne în memorie și nu introduce singură un temporizator.
- Cu opțiunea pornită există două riscuri suplimentare identificate static:
  recrearea repetată a GATT după fiecare termen de 20 s și verificarea revenirii
  de pe telefon la fiecare 20 s. Acestea nu explică direct configurația actuală,
  cu opțiunea oprită.

## Următoarea verificare utilă

Pentru a demonstra o diferență de consum este necesară o comparație înainte/după
în condiții asemănătoare: același cadran, AOD, interval, semnal și jurnalizare.
Prima optimizare justificată de captură este reducerea actualizărilor inutile ale
datei și bateriei și a timpului de wake lock al complicațiilor, păstrând livrarea
valorilor glicemice în Doze. Jurnalizarea poate fi oprită din pagina Log a
telefonului după încheierea diagnosticării. Nu a fost schimbată în această verificare.

Documentație pentru interpretarea statisticilor:
[Android Batterystats](https://developer.android.com/tools/dumpsys),
[profilarea consumului](https://developer.android.com/topic/performance/power/setup-battery-historian).
