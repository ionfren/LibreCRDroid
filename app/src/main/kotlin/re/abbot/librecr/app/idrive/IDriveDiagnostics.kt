package re.abbot.librecr.app.idrive

import de.bmw.idrive.BMWRemoting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import re.abbot.librecr.app.log.BleLog

/** Connection metadata only. Never records readings, certificates, challenges or image payloads. */
internal data class IDriveDiagnosticSnapshot(
    val stage: String = "WAITING_FOR_AAIDRIVE",
    val lastFailure: String? = null,
    val failures: Int = 0,
) {
    fun text(): String = listOfNotNull("Stage: $stage", "Failures: $failures", lastFailure).joinToString("\n")
}

internal object IDriveDiagnostics {
    private val mutable = MutableStateFlow(IDriveDiagnosticSnapshot())
    val snapshot = mutable.asStateFlow()

    @Synchronized fun stage(value: String) {
        mutable.value = mutable.value.copy(stage = value)
        BleLog.log("idrive: $value")
    }

    @Synchronized fun failure(error: Throwable) {
        val failure = "${mutable.value.stage}: ${idriveFailureDescription(error)}"
        mutable.value = mutable.value.copy(lastFailure = failure, failures = mutable.value.failures + 1)
        BleLog.log("idrive: $failure")
    }
}

internal fun idriveFailureDescription(error: Throwable): String {
    // Etch wraps the useful BMW error in IOException; generated exceptions can have a null message.
    return generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }
        .take(5).joinToString(" → ") { cause ->
            val message = when (cause) {
                is BMWRemoting.ServiceException -> cause.errorMsg
                is BMWRemoting.SecurityException -> cause.errorMsg
                is BMWRemoting.IllegalArgumentException -> cause.errorMsg
                is BMWRemoting.IllegalStateException -> cause.errorMsg
                else -> cause.message
            }?.replace(Regex("[\\p{Cntrl}]"), " ")?.take(240)
            listOfNotNull(cause.javaClass.simpleName, message?.takeIf { it.isNotBlank() }).joinToString(": ")
        }.take(800)
}
