package re.abbot.librecr.app.ui.sensor

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import re.abbot.librecr.app.LibreCR
import re.abbot.librecr.app.data.ImportedSession
import re.abbot.librecr.app.libreview.LibreViewAccountClient
import re.abbot.librecr.app.nfc.AndroidLibre3NfcReader
import re.abbot.librecr.app.service.SensorForegroundService
import re.abbot.librecr.app.ui.common.InfoRow
import re.abbot.librecr.app.ui.common.LocalAppSettings
import re.abbot.librecr.app.ui.common.MessageBox
import re.abbot.librecr.app.ui.common.SectionCard
import re.abbot.librecr.app.wear.WearDataSync
import re.abbot.librecr.protocol.pairing.Libre3NfcPatchInfo
import re.abbot.librecr.protocol.pairing.Libre3NfcScanMode
import re.abbot.librecr.protocol.pairing.Libre3NfcScanResult
import re.abbot.librecr.protocol.pairing.Libre3ReceiverID
import re.abbot.librecr.protocol.pairing.NfcActivationCommandCode
import re.abbot.librecr.protocol.toHex
import java.util.Locale
import java.util.UUID

@Composable
fun SensorScreen(nfcReader: AndroidLibre3NfcReader, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val savedSession by LibreCR.store.sessionFlow.collectAsState(initial = null)
    val appSettings = LocalAppSettings.current

    val accountlessUniqueId = remember { libreCrAccountlessUniqueId(ctx) }
    val defaultReceiverId = remember(accountlessUniqueId) { Libre3ReceiverID.accountless(accountlessUniqueId) }
    var receiverIdText by rememberSaveable { mutableStateOf(defaultReceiverId.unsignedValue.toString()) }
    var connectAfterScan by rememberSaveable { mutableStateOf(false) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var nfcMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var nfcError by rememberSaveable { mutableStateOf<String?>(null) }
    var patchInfo by remember { mutableStateOf<Libre3NfcPatchInfo?>(null) }
    var phase5RawKeyText by rememberSaveable { mutableStateOf("") }
    var libreViewEmail by rememberSaveable(appSettings.cloud.email) {
        mutableStateOf(appSettings.cloud.email)
    }
    var libreViewPassword by remember(appSettings.cloud.password) {
        mutableStateOf(appSettings.cloud.password)
    }
    var libreViewLoading by rememberSaveable { mutableStateOf(false) }
    var libreViewMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var libreViewError by rememberSaveable { mutableStateOf<String?>(null) }
    var wearMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var wearError by rememberSaveable { mutableStateOf<String?>(null) }
    var wearBusy by rememberSaveable { mutableStateOf(false) }
    val libreViewClient = remember { LibreViewAccountClient() }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    fun startService(allowCandidateFirstPair: Boolean = false) {
        permLauncher.launch(requiredPermissions())
        scope.launch { LibreCR.store.setAutoConnectEnabled(true) }
        SensorForegroundService.start(ctx, allowCandidateFirstPair)
    }

    fun stopService() {
        scope.launch { LibreCR.store.setAutoConnectEnabled(false) }
        SensorForegroundService.stop(ctx)
    }

    fun savePhase5RawKey() {
        libreViewMessage = null
        libreViewError = "Importul/cache-ul Phase 5 este dezactivat. Reconnect-ul derivează local material nou la fiecare conexiune."
    }

    fun runCandidateFirstPair() {
        if (savedSession == null) {
            libreViewMessage = null
            libreViewError = "Salvează întâi BLE/PIN pentru senzor."
            return
        }
        libreViewError = null
        libreViewMessage = "Pornesc derivarea Phase 5 pe dispozitiv."
        startService(allowCandidateFirstPair = true)
    }

    fun sendWearConfigOnly() {
        val session = savedSession
        if (session == null) {
            wearMessage = null
            wearError = "Nu există o sesiune salvată de trimis către ceas."
            return
        }
        WearDataSync.sendSession(ctx, session, startOnWatch = false)
        wearError = null
        wearMessage = "Configurarea a fost trimisă către ceas. Ceasul o salvează fără să pornească BLE."
    }

    fun handoffToWatch() {
        val session = savedSession
        if (session == null || wearBusy) {
            wearMessage = null
            wearError = if (session == null) "Nu există o sesiune salvată pentru ceas." else "Handoff deja în curs."
            return
        }
        scope.launch {
            wearBusy = true
            wearError = null
            wearMessage = "Trimit configurarea către ceas..."
            WearDataSync.sendSession(ctx, session, startOnWatch = false)
            delay(1_500L)
            wearMessage = "Telefonul cedează conexiunea BLE..."
            LibreCR.store.setAutoConnectEnabled(false)
            val stopped = LibreCR.manager.stopAndJoin()
            SensorForegroundService.stop(ctx)
            if (!stopped) {
                wearBusy = false
                wearMessage = null
                wearError = "Telefonul nu a confirmat închiderea completă a sesiunii BLE."
                return@launch
            }
            delay(500L)
            wearMessage = "Pornesc conexiunea pe ceas."
            WearDataSync.sendSession(ctx, session, startOnWatch = true)
            wearBusy = false
        }
    }

    fun returnToPhone() {
        val session = savedSession
        if (session == null || wearBusy) {
            wearMessage = null
            wearError = if (session == null) "Nu există o sesiune salvată pe telefon." else "Handoff deja în curs."
            return
        }
        scope.launch {
            wearBusy = true
            wearError = null
            wearMessage = "Oprire conexiune pe ceas..."
            val stopped = WearDataSync.requestStopAndWait(ctx)
            if (!stopped) {
                wearBusy = false
                wearMessage = null
                wearError = "Ceasul nu a confirmat închiderea sesiunii BLE; telefonul nu pornește încă."
                return@launch
            }
            wearMessage = "Telefonul reia conexiunea BLE."
            LibreCR.store.setAutoConnectEnabled(true)
            startService(allowCandidateFirstPair = true)
            wearBusy = false
        }
    }

    fun saveActivation(result: Libre3NfcScanResult, replaceExistingSession: Boolean = false) {
        val activation = result.activationResponse ?: return
        val receiverID = Libre3ReceiverID.fromNfcIdentityInput(receiverIdText)
        val session = ImportedSession(
            bleAddress = activation.bleAddressDisplay,
            bleDeviceName = activation.bleAddressDisplay,
            blePin = activation.blePin,
            phase5RawKey = null,
            phoneCert = null,
            receiverId = receiverID?.value,
            serial = result.patchInfo.serialNumber,
            warmupMinutes = result.patchInfo.warmupMinutes,
            wearMinutes = result.patchInfo.wearDurationMinutes,
            sensorProductType = result.patchInfo.productType,
            sensorGeneration = result.patchInfo.generation,
            sensorFirmwareVersion = result.patchInfo.firmwareVersion,
        )
        scope.launch {
            if (replaceExistingSession) {
                SensorForegroundService.stop(ctx)
                LibreCR.manager.stop()
                LibreCR.store.clearSession()
            }
            LibreCR.store.saveActivatedSession(
                session = session,
                startsWarmup = result.commandCode == NfcActivationCommandCode.ACTIVATE && result.patchInfo.isStorageState,
            )
            WearDataSync.sendSession(ctx, session, startOnWatch = false)
            nfcMessage = "Senzor salvat: ${session.bleAddress}, PIN ${session.blePin.toHex()}"
            nfcError = null
            patchInfo = result.patchInfo
            if (connectAfterScan) {
                nfcMessage += "; pornesc derivarea locală."
                startService(allowCandidateFirstPair = true)
            }
        }
    }

    fun startNfc(modeFactory: (Int) -> Libre3NfcScanMode, replaceExistingSession: Boolean = false) {
        val receiverID = Libre3ReceiverID.fromNfcIdentityInput(receiverIdText)
        if (receiverID == null) {
            nfcError = "Account ID invalid. Folosește număr decimal sau 4 bytes hex little-endian."
            return
        }
        scanning = true
        nfcMessage = "Apropie telefonul de senzor..."
        nfcError = null
        nfcReader.scan(modeFactory(receiverID.value)) { result ->
            scanning = false
            result.onSuccess { scan ->
                patchInfo = scan.patchInfo
                if (scan.activationResponse != null) {
                    saveActivation(scan, replaceExistingSession)
                } else if (scan.activationError != null) {
                    nfcMessage = null
                    nfcError = "NFC error=0x${"%02x".format(requireNotNull(scan.activationError).errorCode)}"
                } else {
                    nfcMessage = "Patch citit: ${scan.patchInfo.serialNumber}"
                }
            }.onFailure {
                nfcMessage = null
                nfcError = it.message ?: it.toString()
            }
        }
    }

    fun fetchLibreViewAccountId() {
        if (libreViewLoading) return
        libreViewLoading = true
        libreViewError = null
        libreViewMessage = "Mă conectez la LibreView..."
        scope.launch {
            LibreCR.settings.setCloudCredentials(libreViewEmail.trim(), libreViewPassword)
            runCatching {
                libreViewClient.fetchAccountIdentity(
                    username = libreViewEmail.trim(),
                    password = libreViewPassword,
                    deviceId = accountlessUniqueId,
                )
            }.onSuccess { identity ->
                receiverIdText = identity.accountNumber.unsignedValue.toString()
                libreViewMessage = "Account ID: ${nfcIdentityDisplay(identity.accountNumber)}"
            }.onFailure {
                libreViewMessage = null
                libreViewError = it.message ?: it.toString()
            }
            libreViewLoading = false
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        SectionCard("Conexiune") {
            Text(
                if (savedSession == null) "Nu există sesiune BLE/PIN salvată." else "Sesiune salvată pentru ${savedSession?.serial ?: savedSession?.bleAddress}.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { startService() }, enabled = savedSession != null) { Text("Start BLE") }
                FilledTonalButton(onClick = { stopService() }) { Text("Stop") }
                OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_NFC_SETTINGS)) }) { Text("NFC") }
            }
        }

        NfcCard(
            nfcReader = nfcReader,
            receiverIdText = receiverIdText,
            onReceiverIdText = { receiverIdText = it },
            accountStatus = receiverIdStatus(receiverIdText, accountlessUniqueId),
            connectAfterScan = connectAfterScan,
            onConnectAfterScan = { connectAfterScan = it },
            scanning = scanning,
            patchInfo = patchInfo,
            message = nfcMessage,
            error = nfcError,
            onReadPatch = { startNfc({ Libre3NfcScanMode.ReadPatchInfo }) },
            onActivateOrSwitch = { startNfc({ Libre3NfcScanMode.ActivateOrSwitchReceiver(it) }) },
            onRecoverPin = { startNfc({ Libre3NfcScanMode.ActivateOrSwitchReceiver(it) }, replaceExistingSession = true) },
            onForceA0 = { startNfc({ Libre3NfcScanMode.ForceActivationCommand(NfcActivationCommandCode.ACTIVATE, it) }) },
            onForceA8 = { startNfc({ Libre3NfcScanMode.ForceActivationCommand(NfcActivationCommandCode.SWITCH_RECEIVER, it) }) },
            onCancel = { nfcReader.cancel(); scanning = false },
        )

        SavedSensorCard(savedSession, patchInfo)

        RecoveryCard(
            email = libreViewEmail,
            password = libreViewPassword,
            onEmail = { value ->
                libreViewEmail = value
                scope.launch {
                    LibreCR.settings.setCloudCredentials(value.trim(), libreViewPassword)
                }
            },
            onPassword = { value ->
                libreViewPassword = value
                scope.launch {
                    LibreCR.settings.setCloudCredentials(libreViewEmail.trim(), value)
                }
            },
            loading = libreViewLoading,
            message = libreViewMessage,
            error = libreViewError,
            phase5RawKey = phase5RawKeyText,
            onPhase5RawKey = { phase5RawKeyText = it },
            hasSavedSession = savedSession != null,
            onFetchAccount = ::fetchLibreViewAccountId,
            onSavePhase5 = ::savePhase5RawKey,
            onCandidate = ::runCandidateFirstPair,
        )

        WearHandoffCard(
            session = savedSession,
            busy = wearBusy,
            message = wearMessage,
            error = wearError,
            onSendConfig = ::sendWearConfigOnly,
            onHandoff = ::handoffToWatch,
            onReturnToPhone = ::returnToPhone,
        )
    }
}

@Composable
private fun NfcCard(
    nfcReader: AndroidLibre3NfcReader,
    receiverIdText: String,
    onReceiverIdText: (String) -> Unit,
    accountStatus: String,
    connectAfterScan: Boolean,
    onConnectAfterScan: (Boolean) -> Unit,
    scanning: Boolean,
    patchInfo: Libre3NfcPatchInfo?,
    message: String?,
    error: String?,
    onReadPatch: () -> Unit,
    onActivateOrSwitch: () -> Unit,
    onRecoverPin: () -> Unit,
    onForceA0: () -> Unit,
    onForceA8: () -> Unit,
    onCancel: () -> Unit,
) {
    SectionCard("Senzor / NFC") {
        OutlinedTextField(
            value = receiverIdText,
            onValueChange = onReceiverIdText,
            label = { Text("Account / receiver ID") },
            supportingText = { Text(accountStatus) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Pornește BLE după scanare")
                Text("Doar dacă există deja cheia Phase 5.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = connectAfterScan, onCheckedChange = onConnectAfterScan)
        }
        if (!nfcReader.isAvailable) MessageBox("NFC nu este disponibil pe acest telefon.", true)
        patchInfo?.let {
            InfoRow("serial", it.serialNumber)
            InfoRow("state", "0x${"%02x".format(it.stateByte)}")
            InfoRow("wear", "${it.wearDurationMinutes} min")
        }
        AnimatedVisibility(scanning) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Ține telefonul nemișcat peste senzor.", style = MaterialTheme.typography.bodySmall)
            }
        }
        message?.let { MessageBox(it, false) }
        error?.let { MessageBox(it, true) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onReadPatch, enabled = !scanning && nfcReader.isEnabled) { Text("Citește") }
            Button(onClick = onActivateOrSwitch, enabled = !scanning && nfcReader.isEnabled) { Text("Activează / preia") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onRecoverPin, enabled = !scanning && nfcReader.isEnabled) { Text("Recuperează PIN") }
            OutlinedButton(onClick = onForceA0, enabled = !scanning && nfcReader.isEnabled) { Text("A0") }
            OutlinedButton(onClick = onForceA8, enabled = !scanning && nfcReader.isEnabled) { Text("A8") }
        }
        AnimatedVisibility(scanning) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Anulează") }
        }
    }
}

@Composable
private fun SavedSensorCard(session: ImportedSession?, patchInfo: Libre3NfcPatchInfo?) {
    SectionCard("Senzor salvat") {
        if (session == null) {
            Text("Nicio sesiune salvată.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            InfoRow("BLE", session.bleAddress)
            InfoRow("BLE name", session.bleDeviceName ?: "--")
            InfoRow("PIN", session.blePin.toHex())
            InfoRow("serial", session.serial ?: "--")
            InfoRow("Phase 5", "nu se cache-uiește")
        }
        patchInfo?.let {
            HorizontalDivider()
            InfoRow("patch", it.serialNumber)
            InfoRow("fw", it.firmwareVersion)
            InfoRow("warmup", "${it.warmupMinutes} min")
        }
    }
}

@Composable
private fun RecoveryCard(
    email: String,
    password: String,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    loading: Boolean,
    message: String?,
    error: String?,
    phase5RawKey: String,
    onPhase5RawKey: (String) -> Unit,
    hasSavedSession: Boolean,
    onFetchAccount: () -> Unit,
    onSavePhase5: () -> Unit,
    onCandidate: () -> Unit,
) {
    SectionCard("Recuperare") {
        OutlinedTextField(email, onEmail, label = { Text("LibreView email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = password,
            onValueChange = onPassword,
            label = { Text("LibreView parolă") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        FilledTonalButton(onClick = onFetchAccount, enabled = !loading && email.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(if (loading) "Conectare..." else "Ia ID din LibreView")
        }
        HorizontalDivider()
        Text("Phase 5", fontWeight = FontWeight.Bold)
        Button(onClick = onCandidate, enabled = hasSavedSession, modifier = Modifier.fillMaxWidth()) {
            Text("Derivă Phase 5 pe dispozitiv")
        }
        OutlinedTextField(
            value = phase5RawKey,
            onValueChange = onPhase5RawKey,
            label = { Text("Debug: Phase 5 raw key") },
            supportingText = { Text("Opțional: 32 caractere hex.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        FilledTonalButton(onClick = onSavePhase5, enabled = hasSavedSession && phase5RawKey.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text("Import Phase 5 dezactivat")
        }
        message?.let { MessageBox(it, false) }
        error?.let { MessageBox(it, true) }
    }
}

@Composable
private fun WearHandoffCard(
    session: ImportedSession?,
    busy: Boolean,
    message: String?,
    error: String?,
    onSendConfig: () -> Unit,
    onHandoff: () -> Unit,
    onReturnToPhone: () -> Unit,
) {
    SectionCard("Wear OS") {
        Text(
            "Ceasul primește datele de conectare și stabilește singur BLE cu senzorul. " +
                "La preluare, telefonul oprește conexiunea înainte să pornească ceasul.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onSendConfig, enabled = session != null && !busy) { Text("Trimite configurarea") }
            Button(onClick = onHandoff, enabled = session != null && !busy) { Text("Ceasul preia") }
        }
        OutlinedButton(onClick = onReturnToPhone, enabled = session != null && !busy, modifier = Modifier.fillMaxWidth()) {
            Text("Revino pe telefon")
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        message?.let { MessageBox(it, false) }
        error?.let { MessageBox(it, true) }
    }
}

private fun requiredPermissions(): Array<String> {
    val perms = mutableListOf<String>()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        perms += Manifest.permission.BLUETOOTH_SCAN
        perms += Manifest.permission.BLUETOOTH_CONNECT
    } else {
        perms += Manifest.permission.ACCESS_FINE_LOCATION
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        perms += Manifest.permission.POST_NOTIFICATIONS
    }
    return perms.toTypedArray()
}

private fun receiverIdStatus(input: String, accountlessUniqueId: String): String {
    val identity = Libre3ReceiverID.fromNfcIdentityInput(input)
    return if (identity == null) {
        "ID fallback: ${Libre3ReceiverID.accountless(accountlessUniqueId).unsignedValue}"
    } else {
        "NFC: ${nfcIdentityDisplay(identity)}"
    }
}

private fun nfcIdentityDisplay(identity: Libre3ReceiverID): String =
    "${identity.unsignedValue} / LE ${identity.littleEndianHex}"

private fun libreCrAccountlessUniqueId(context: Context): String {
    val prefs = context.getSharedPreferences("librecr_identity", Context.MODE_PRIVATE)
    val existing = prefs.getString(LIBRECR_ACCOUNTLESS_UNIQUE_ID_KEY, null)
    if (!existing.isNullOrBlank()) return existing
    val created = UUID.randomUUID().toString().lowercase(Locale.US)
    prefs.edit().putString(LIBRECR_ACCOUNTLESS_UNIQUE_ID_KEY, created).apply()
    return created
}

private const val LIBRECR_ACCOUNTLESS_UNIQUE_ID_KEY = "LibreCRAccountlessUniqueID"
