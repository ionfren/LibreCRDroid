# LibreCRDroid

Kotlin/Android port of [LibreCRKit](../LibreCRKit) (Swift/iOS) — Libre 3 CGM
pairing, BLE transport, security handshake, and per-minute glucose decoding.

**Milestone 1 = reconnect-only.** Android reconnects to a sensor already paired
by the iOS app: it imports the Phase 5 raw key + BLE PIN + address, runs the BLE
security handshake (re-deriving `kEnc`/`ivEnc` via Phase 6 each connect), and
streams decrypted glucose every minute. First-pair-from-scratch (the 15K-line
white-box builder) and cached-kAuth import are deferred to later milestones.

The protocol layer is **byte-for-byte identical** to the Swift implementation,
proven by golden-vector tests (see Verification).

## Modules

- **`:protocol`** — pure Kotlin/JVM library (no Android deps): GATT framing,
  Abbott CRC16/NFC commands, AES-CCM, the table-driven white-box `LibAes`
  (Phase 5/6 block primitive), P-256 ECDH/ECDSA, Phase 5/6, certs, the
  `PairingFlow` state machine, and the data-plane decode + parsers. Being
  JVM-only, it runs as plain JUnit on a dev machine.
- **`:app`** — Android: `BluetoothGatt` transport with a serial operation queue,
  scanner, the connection-lifecycle state machine (self-healing reconnect +
  no-data watchdog), a `connectedDevice` foreground service, DataStore
  persistence, structured hex logging, and a minimal Compose UI.

Runtime tables (`runtime_tables/libaes_*.bin`) are byte-identical copies of the
Swift package resources, loaded from the classpath (works in JVM tests + on
Android).

## Build & test

```sh
# Protocol golden-vector tests (proves byte-perfect parity vs Swift):
./gradlew :protocol:test

# Android app:
./gradlew :app:assembleDebug
```

## Verification — "byte cu byte"

Parity is enforced by golden vectors exported from the authoritative Swift code:

1. In the Swift repo: `swift run librecr-vectors` writes
   `vectors/librecr_vectors.json` (canonical input→output pairs computed by
   `LibreCRKit`).
2. That JSON is copied to `protocol/src/test/resources/golden/`.
3. `:protocol` JUnit tests recompute every primitive with the Kotlin port and
   assert byte-equality: CRC16, NFC commands, framing, AES-CCM (M4/M8), `LibAes`
   key-setup/block/phase5-block, Phase 5 challenge, Phase 6 → session material,
   data-plane decrypt + glucose/patchStatus/historical/clinical parse, ECDH,
   ECDSA. Plus a `PairingFlow` loopback (cached + command-gated) that exercises
   the full handshake state machine offline.

On device, `BleLog` emits a timestamped hex dump of every GATT write/notify and
each handshake stage, so a live session can be diffed against an iOS capture.

## Learning a session over NFC

Press **Activează / preia** and hold the phone on the Libre 3 sensor. The app
reads patch info with `A1`, then sends the Juggluco-style command selection:
`A0` while the sensor is in storage state (`state == 1`), otherwise `A8`.

The activation response is parsed directly in Kotlin:
- bytes 3..8: BLE address, displayed in reverse byte order
- bytes 9..12: 4-byte BLE PIN
- bytes 13..16: activation time

The resulting `ImportedSession` is saved internally in DataStore, so there is no
manual JSON paste step in the app UI. Then grant Bluetooth (+ notifications)
permissions and press **Start**, or enable **Pornește BLE după scanare** before
the NFC scan.

## Status

- ✅ Protocol layer ported and byte-perfect (25 golden + loopback tests passing).
- ✅ Android BLE transport, connection state machine, foreground service, UI.
- ⏳ On-device validation with a real sensor (requires the hardware) — diff
  `BleLog` output against an iOS capture for the handshake.
