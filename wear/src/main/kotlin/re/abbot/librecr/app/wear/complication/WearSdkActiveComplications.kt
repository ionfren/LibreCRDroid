package re.abbot.librecr.app.wear.complication

import android.content.ComponentName
import android.content.Context
import android.os.OutcomeReceiver
import androidx.annotation.RequiresApi
import com.google.wear.Sdk
import com.google.wear.services.complications.ActiveComplicationConfig
import com.google.wear.services.complications.ComplicationsManager
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** The system's active configuration remains authoritative after process restarts/OTA updates. */
@RequiresApi(36)
internal object WearSdkActiveComplications {
    suspend fun find(context: Context, executor: Executor): Map<ComponentName, Set<Int>> =
        suspendCancellableCoroutine { continuation ->
            Sdk.getWearManager(context, ComplicationsManager::class.java)
                .getActiveComplicationConfigsAsync(
                    executor,
                    object : OutcomeReceiver<List<ActiveComplicationConfig>, Throwable> {
                        override fun onResult(result: List<ActiveComplicationConfig>) {
                            continuation.resume(
                                result.groupBy { it.config.providerComponent }
                                    .mapValues { (_, configs) -> configs.map { it.config.id }.toSet() },
                            )
                        }

                        override fun onError(error: Throwable) {
                            continuation.resumeWithException(error)
                        }
                    },
                )
        }
}
