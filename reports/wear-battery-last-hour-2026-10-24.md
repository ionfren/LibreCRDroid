# Consum în ultima oră — Pixel Watch 4

Interval investigat: **24 octombrie 2026, 11:11:27–12:11:27 CEST**. Istoricul
acoperă întreaga oră. Au fost făcute doar citiri ADB, fără resetarea statisticilor,
fără reinstalarea aplicației și fără schimbarea configurației ceasului.

## Rezultat

Bateria a scăzut de la **75% la 70%**, adică 5 puncte procentuale pe oră. Dovezile
nu indică LibreCR drept sursa dominantă a consumului excesiv în acest interval;
principalul indiciu observat este instabilitatea conectivității Wi-Fi. Datele
nu permit excluderea completă a consumului normal sau indirect al aplicației.

## Dovezi pentru intervalul exact

| Observație | Rezultat |
| --- | --- |
| Wakelock-uri LibreCR de complicații, reconectare sau handshake | Nu apar evenimente pentru acestea în istoricul orei. |
| Notificări LibreCR în câmpul primului wakelock activ | 55 activări, aproximativ 2,33 s însumate. |
| Monitor Wi-Fi `IpReachabilityMonitor.wlan0` în același câmp | 38 activări, aproximativ **268,44 s** însumate. |
| Autentificări Wi-Fi | **44** tranziții `4-way-handshake`, 44 `associated`. |
| Procesor în starea `running` | aproximativ 25 min 38 s din oră, **42,7%**. |
| Ecran în starea `screen` | aproximativ **49,6 s** din oră. |
| Repaus logic Doze complet | aproximativ 56 min 29 s; această stare nu garantează suspendarea CPU. |

Istoricul standard Batterystats prezintă primul wakelock activ, nu toate blocările
suprapuse per UID. Duratele și numerele reconstruite de mai sus descriu acest
câmp; nu sunt o măsurare completă a energiei pe aplicație. Absența unui eveniment
nu dovedește matematic absența oricărui efect indirect.

`dumpsys wifi` confirmă alternarea repetată între două BSSID-uri ale aceleiași
rețele, pe **2412 MHz (2,4 GHz)** și **5500 MHz (5 GHz)**. În jurnalul recent
păstrat între 12:05:13 și 12:09:18 apar 11 evenimente `ASSOCIATED_BSSID_EVENT`,
cu 10 alternări succesive între cele două puncte radio. Aceste evenimente coincid
cu reautentificările și blocările monitorului de conectivitate. Acesta este un
indiciu tehnic puternic pentru investigarea Wi-Fi, nu o măsurare izolată a tuturor
costurilor sale energetice și nici dovada că routerul sau firmware-ul este singurul
responsabil.

## Verificări suplimentare și limite

- În eșantionul CPU disponibil la 12:10:19–12:11:28, LibreCR apare cu aproximativ
  1%, comparativ cu 13% pentru `android.system.suspend-service`, 11% pentru
  `system_server` și 2,8% pentru `dhd_dpc`. Procentele sunt cele raportate de
  `cpuinfo`; **nu sunt media întregii ore**, iar intervalul raportat are 39% uptime.
- `netstats` nu conține istoric de trafic IP pentru UID-ul LibreCR 10011.
  Aplicația instalată nu solicită `INTERNET` sau `CHANGE_WIFI_STATE`. Transferurile
  indirecte prin servicii comune, precum Google Play services, nu sunt excluse.
- Serviciul BLE este activ în procesul 15307, aceeași instanță utilizată la
  validarea anterioară. Ultima instalare este la **10:58:04**, înaintea orei analizate.
- Statisticile energetice agregate sunt de la **09:31:43**, nu doar pentru ultima
  oră. Estimarea de 7,47 mAh pentru UID-ul LibreCR nu poate fi atribuită acestei
  ore și nu este folosită pentru a demonstra excluderea aplicației.
- Modelele din captură raportează 100 mAh descărcare totală, dar 192 mAh la
  componenta CPU; valorile componentelor nu trebuie adunate sau interpretate ca
  măsurători independente, exacte. Nu există în această sesiune o măsurare
  comparativă izolată cu LibreCR oprit.

Concluzia susținută este că vechile wakelock-uri excesive ale LibreCR nu se mai
observă în această oră, iar activitatea Wi-Fi prezintă anomalia dominantă dintre
mecanismele de menținere a CPU examinate. O atribuție de tipul „LibreCR nu consumă
nimic” sau „cei 5% provin integral din Wi-Fi” ar depăși dovezile disponibile.

Capturi brute și analiza reproductibilă:
`/tmp/pixel-watch-hour-audit-20261024/` — `context.txt`, `batterystats.txt`,
`last-hour-history.txt`, `power-process.txt`, `connectivity.txt`,
`hour-analysis.txt` și `analyze-hour.py`.
