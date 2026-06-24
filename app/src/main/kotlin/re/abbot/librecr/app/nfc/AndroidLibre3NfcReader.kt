package re.abbot.librecr.app.nfc

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.NfcV
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import re.abbot.librecr.app.log.BleLog
import re.abbot.librecr.protocol.pairing.Libre3NfcActivationErrorResponse
import re.abbot.librecr.protocol.pairing.Libre3NfcActivationResponse
import re.abbot.librecr.protocol.pairing.Libre3NfcException
import re.abbot.librecr.protocol.pairing.Libre3NfcPatchInfo
import re.abbot.librecr.protocol.pairing.Libre3NfcScanMode
import re.abbot.librecr.protocol.pairing.Libre3NfcScanResult
import re.abbot.librecr.protocol.pairing.NfcActivationCommand
import re.abbot.librecr.protocol.pairing.NfcActivationCommandCode
import re.abbot.librecr.protocol.toHex

class AndroidLibre3NfcReader(private val activity: Activity) {
    private val lock = Any()
    private val adapter: NfcAdapter? get() = NfcAdapter.getDefaultAdapter(activity)

    private var activeMode: Libre3NfcScanMode? = null
    private var completion: ((Result<Libre3NfcScanResult>) -> Unit)? = null

    val isAvailable: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    fun scan(
        mode: Libre3NfcScanMode,
        onComplete: (Result<Libre3NfcScanResult>) -> Unit,
    ) {
        val nfc = adapter
        when {
            nfc == null -> {
                onComplete(Result.failure(Libre3NfcException.ReaderUnavailable))
                return
            }
            !nfc.isEnabled -> {
                onComplete(Result.failure(Libre3NfcException.ReaderDisabled))
                return
            }
        }

        synchronized(lock) {
            if (completion != null) {
                onComplete(Result.failure(Libre3NfcException.SessionAlreadyActive))
                return
            }
            activeMode = mode
            completion = onComplete
        }

        val extras = Bundle().apply {
            putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY, 250)
        }
        val flags = NfcAdapter.FLAG_READER_NFC_V or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or
            NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS

        BleLog.log("nfc: reader mode enabled")
        nfc.enableReaderMode(activity, ::onTagDiscovered, flags, extras)
    }

    fun cancel() {
        val callback = synchronized(lock) {
            val current = completion
            completion = null
            activeMode = null
            current
        }
        adapter?.disableReaderMode(activity)
        callback?.invoke(Result.failure(Libre3NfcException.NoTag))
    }

    private fun onTagDiscovered(tag: Tag) {
        val mode = synchronized(lock) { activeMode }
        if (mode == null) {
            finish(Result.failure(Libre3NfcException.NoTag))
            return
        }
        val result = runCatching { read(tag, mode) }
        finish(result)
    }

    private fun read(tag: Tag, mode: Libre3NfcScanMode): Libre3NfcScanResult {
        val nfcv = NfcV.get(tag) ?: throw Libre3NfcException.NonIso15693Tag
        try {
            nfcv.connect()
            BleLog.log("nfc: read patch info command=${NfcActivationCommand.readPatchInfo.toHex()}")
            val patchRaw = nfcv.transceive(NfcActivationCommand.readPatchInfo)
            BleLog.log("nfc: patch info raw=${patchRaw.toHex()}")
            val patchInfo = Libre3NfcPatchInfo(patchRaw)

            val request = activationRequest(mode, patchInfo)
                ?: return Libre3NfcScanResult(patchInfo = patchInfo)

            val activation = runActivationCommand(nfcv, request, patchInfo)
                .let { first ->
                    if (first.shouldTryAlternate && mode is Libre3NfcScanMode.ActivateOrSwitchReceiver) {
                        val fallback = request(
                            commandCode = request.commandCode.alternate(),
                            receiverId = request.receiverId,
                            timeSeconds = request.timeSeconds,
                        )
                        BleLog.log(
                            "nfc: activation error=0x${"%02x".format(first.error?.errorCode)}; " +
                                "retry command=0x${"%02x".format(fallback.commandCode.raw)}"
                        )
                        runActivationCommand(nfcv, fallback, patchInfo)
                    } else {
                        first
                    }
                }
            return Libre3NfcScanResult(
                patchInfo = patchInfo,
                commandCode = activation.request.commandCode,
                commandParameters = activation.request.parameters,
                activationResponse = activation.response,
                activationError = activation.error,
            )
        } finally {
            runCatching { nfcv.close() }
        }
    }

    private fun runActivationCommand(
        nfcv: NfcV,
        request: ActivationRequest,
        patchInfo: Libre3NfcPatchInfo,
    ): ActivationAttempt {
        BleLog.log(
            "nfc: activation command=0x${"%02x".format(request.commandCode.raw)} " +
                "params=${request.parameters.toHex()}"
        )
        val raw = nfcv.transceive(
            NfcActivationCommand.command(
                request.commandCode,
                request.timeSeconds,
                request.receiverId,
            )
        )
        BleLog.log("nfc: activation raw=${raw.toHex()}")
        runCatching { Libre3NfcActivationResponse(raw) }
            .getOrNull()
            ?.let { return ActivationAttempt(request = request, response = it, error = null) }

        runCatching { Libre3NfcActivationErrorResponse(raw) }
            .getOrNull()
            ?.let {
                BleLog.log("nfc: activation error=0x${"%02x".format(it.errorCode)}")
                return ActivationAttempt(request = request, response = null, error = it)
            }

        throw Libre3NfcException.InvalidActivationResponseForPatch(
            request.commandCode,
            patchInfo,
            raw,
        )
    }

    private fun activationRequest(
        mode: Libre3NfcScanMode,
        patchInfo: Libre3NfcPatchInfo,
    ): ActivationRequest? {
        val now = defaultActivationTimeSeconds()
        return when (mode) {
            Libre3NfcScanMode.ReadPatchInfo -> null
            is Libre3NfcScanMode.ActivateFreshSensor -> {
                if (!patchInfo.isStorageState) throw Libre3NfcException.UnexpectedSensorState(patchInfo)
                request(NfcActivationCommandCode.ACTIVATE, mode.receiverId, mode.timeSeconds ?: now)
            }
            is Libre3NfcScanMode.SwitchReceiver -> {
                if (patchInfo.isStorageState) throw Libre3NfcException.UnexpectedSensorState(patchInfo)
                request(NfcActivationCommandCode.SWITCH_RECEIVER, mode.receiverId, mode.timeSeconds ?: now)
            }
            is Libre3NfcScanMode.ActivateOrSwitchReceiver -> {
                request(patchInfo.recommendedCommandCode, mode.receiverId, mode.timeSeconds ?: now)
            }
            is Libre3NfcScanMode.ForceActivationCommand -> {
                request(mode.commandCode, mode.receiverId, mode.timeSeconds ?: now)
            }
        }
    }

    private fun request(
        commandCode: NfcActivationCommandCode,
        receiverId: Int,
        timeSeconds: Int,
    ): ActivationRequest = ActivationRequest(
        commandCode = commandCode,
        receiverId = receiverId,
        timeSeconds = timeSeconds,
        parameters = NfcActivationCommand.metcrc(timeSeconds, receiverId),
    )

    private fun finish(result: Result<Libre3NfcScanResult>) {
        val callback = synchronized(lock) {
            val current = completion
            completion = null
            activeMode = null
            current
        }
        activity.runOnUiThread {
            adapter?.disableReaderMode(activity)
            BleLog.log("nfc: reader mode disabled")
            signalScanFinished(result)
            callback?.invoke(result)
        }
    }

    private fun signalScanFinished(result: Result<Libre3NfcScanResult>) {
        val haptic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (result.isSuccess) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.REJECT
        } else {
            if (result.isSuccess) HapticFeedbackConstants.CONTEXT_CLICK else HapticFeedbackConstants.LONG_PRESS
        }
        activity.window.decorView.performHapticFeedback(
            haptic,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING or
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
        )

        val effect = if (result.isSuccess) {
            VibrationEffect.createWaveform(longArrayOf(0, 60, 40, 90), -1)
        } else {
            VibrationEffect.createWaveform(longArrayOf(0, 120, 70, 120), -1)
        }
        vibrator()?.takeIf { it.hasVibrator() }?.vibrate(effect)

        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tone.startTone(
                if (result.isSuccess) ToneGenerator.TONE_PROP_ACK else ToneGenerator.TONE_PROP_NACK,
                120,
            )
            activity.window.decorView.postDelayed({ tone.release() }, 180)
        }
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activity.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            activity.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private data class ActivationRequest(
        val commandCode: NfcActivationCommandCode,
        val receiverId: Int,
        val timeSeconds: Int,
        val parameters: ByteArray,
    )

    private data class ActivationAttempt(
        val request: ActivationRequest,
        val response: Libre3NfcActivationResponse?,
        val error: Libre3NfcActivationErrorResponse?,
    ) {
        val shouldTryAlternate: Boolean get() = error?.isJugglucoNonFatal == true
    }

    companion object {
        private fun defaultActivationTimeSeconds(): Int {
            val seconds = ((System.currentTimeMillis() / 1000L) - 1L).coerceAtLeast(0L)
            return (seconds and 0xffffffffL).toInt()
        }
    }
}

private fun NfcActivationCommandCode.alternate(): NfcActivationCommandCode =
    when (this) {
        NfcActivationCommandCode.ACTIVATE -> NfcActivationCommandCode.SWITCH_RECEIVER
        NfcActivationCommandCode.SWITCH_RECEIVER -> NfcActivationCommandCode.ACTIVATE
    }
