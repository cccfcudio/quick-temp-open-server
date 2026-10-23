package app.openshelf.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.openshelf.R

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val notice by produceState(initialValue = "") {
        value = readAsset(context, "licenses/cloudflared-NOTICE.txt")
    }
    val build by produceState(initialValue = "") {
        value = readAsset(context, "cloudflared-build.txt")
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        Text(stringResource(R.string.about_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.about_body), modifier = Modifier.padding(top = 12.dp))
        if (build.isNotBlank()) {
            Text(build, modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
        }
        Text(notice, modifier = Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodySmall)
    }
}

private fun readAsset(context: Context, name: String): String {
    return try {
        context.assets.open(name).bufferedReader().use { it.readText() }
    } catch (error: Exception) {
        ""
    }
}
