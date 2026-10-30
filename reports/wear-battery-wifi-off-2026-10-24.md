# Verificare după oprirea Wi-Fi — Pixel Watch 4

24 octombrie 2026. Ceasul a revenit în ADB după reactivarea Wi-Fi. Intervalul fără
Wi-Fi a fost identificat retrospectiv din istoricul păstrat, fără resetări,
reinstalări sau modificări ale configurației în această verificare.

## Intervalul real

- Wi-Fi oprit: **12:16:38.005 CEST** (`-wifi`).
- Wi-Fi reactivat: **12:25:45.630 CEST** (`+wifi`).
- Durată: **9 min 7,625 s**.
- Baterie la limitele intervalului: **70% → 69%**.
- La captura de după reconectare, 12:29:10, bateria era 68%; această citire
  include câteva minute cu Wi-Fi reactivat și nu este folosită ca sfârșit al testului.

## Comparație cu o fereastră egală înainte de prima colectare ADB

Pentru a reduce influența propriilor comenzi de diagnostic, referința principală
este 12:02:19.375–12:11:27.000, încheiată înaintea primei colectări ample ADB.
Ambele ferestre au aceeași durată, 547,625 s.

| Indicator reconstruit din Battery History | Wi-Fi pornit, referință | Wi-Fi oprit |
| --- | ---: | ---: |
| CPU în starea `running` | 236,645 s (**43,2%**) | 78,327 s (**14,3%**) |
| Ecran în starea `screen` | 31,149 s | 28,130 s |
| Tranziții `4-way-handshake` | 12 | **0** |
| Treziri raportate `220 bcmsdh_sdmmc` | 78 | **0** |
| `IpReachabilityMonitor.wlan0`, timpul primului wakelock afișat | 87,394 s | **0 s** |

În intervalul imediat precedent opririi, care include și colectarea ADB,
CPU era `running` 57,4% din timp. Această referință suplimentară este păstrată în
analiza brută, dar nu este folosită pentru comparația principală, deoarece
traficul de diagnostic poate menține Wi-Fi și procesorul active.

`running` măsoară menținerea CPU nesuspendat, nu utilizarea sa la capacitate
maximă sau energia totală. Câmpul wakelock din istoricul standard arată primul
wakelock activ și nu acoperă individual toate blocările suprapuse.

## Activitatea LibreCR în timpul testului

Istoricul detaliat al PowerManager din `dumpsys power` arată două blocări
`LibreCR:BleRecovery`: 12:20:58.894–12:21:18.925 (20,031 s) și
12:23:40.238–12:23:40.586 (0,348 s), în total **20,379 s**. Postarea notificărilor
LibreCR însumează 0,187 s în înregistrările detaliate analizate; mai există o
blocare de lansare de 0,222 s. Costul aplicației nu este zero și recuperarea BLE
nu trebuie ignorată când se interpretează intervalul.

## Concluzie și limite

Oprirea Wi-Fi coincide cu dispariția reautentificărilor și trezirilor WLAN și cu
reducerea puternică a timpului în care CPU rămâne treaz. După reactivare,
reautentificările repetate au revenit în istoricul 12:25–12:29. Aceste rezultate
susțin o contribuție importantă a conectivității Wi-Fi la activitatea excesivă.

**Cele aproximativ 9 minute și un singur punct procentual pierdut nu permit
calcularea unei rate fiabile de descărcare sau confirmarea economiei totale de
baterie.** Nu este un test controlat de laborator: înregistrările includ utilizare
scurtă a ecranului și recuperare BLE. Nu se poate afirma că LibreCR nu contribuie
deloc sau că întregul consum provine exclusiv din Wi-Fi; o comparație energetică
mai sigură necesită un interval fără Wi-Fi considerabil mai lung.

Surse locale: `/tmp/pixel-watch-wifi-toggle-audit-20261024/` — `context.txt`,
`batterystats.txt`, `current-state.txt`, `wifi-off-history.txt`,
`comparison.txt`, `compare-wifi.py`.
