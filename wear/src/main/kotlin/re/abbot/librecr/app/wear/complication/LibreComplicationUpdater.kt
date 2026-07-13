package re.abbot.librecr.app.wear.complication

import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import re.abbot.librecr.app.log.BleLog

object LibreComplicationUpdater {
    fun requestAll(context: Context, lifeCount: Int? = null) {
        request(context, AgeDeltaComplicationService::class.java, lifeCount)
        request(context, TrendAboveValueComplicationService::class.java, lifeCount)
        request(context, DateComplicationService::class.java, lifeCount)
        request(context, WatchBatteryComplicationService::class.java, lifeCount)
    }

    private fun request(context: Context, serviceClass: Class<*>, lifeCount: Int?) {
        runCatching {
            ComplicationDataSourceUpdateRequester.create(
                context = context.applicationContext,
                complicationDataSourceComponent = ComponentName(context, serviceClass),
            ).requestUpdateAll()
            val route = if (Build.VERSION.SDK_INT >= 36) "WEAR_SDK" else "LEGACY_BROADCAST"
            BleLog.log(
                "COMPLICATION_UPDATE_REQUESTED lc=${lifeCount ?: -1} " +
                    "service=${serviceClass.simpleName} route=$route",
            )
        }.onFailure {
            BleLog.log("complication: update request failed for ${serviceClass.simpleName}: ${it.message ?: it::class.java.simpleName}")
        }
    }
}
