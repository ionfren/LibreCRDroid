package re.abbot.librecr.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import re.abbot.librecr.app.R
import re.abbot.librecr.app.log.BleLog

@Composable
fun LogScreen(modifier: Modifier = Modifier) {
    val logLines by BleLog.log.collectAsState()
    val loggingEnabled by BleLog.enabled.collectAsState()
    Column(
        modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.title_log), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { BleLog.setEnabled(!loggingEnabled) }) {
                Text(
                    stringResource(
                        if (loggingEnabled) R.string.log_stop else R.string.log_start,
                    ),
                )
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { BleLog.clear() }) { Text(stringResource(R.string.log_clear)) }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
        ) {
            for (line in logLines.asReversed().take(400)) {
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }
    }
}
