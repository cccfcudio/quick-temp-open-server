package app.openshelf.ui

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.openshelf.BuildConfig
import app.openshelf.R
import app.openshelf.share.GitHubProject
import app.openshelf.share.UpdateStatus

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    updateStatus: UpdateStatus,
    onCheckUpdates: () -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
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
        Text(
            stringResource(R.string.current_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
            modifier = Modifier.padding(top = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(stringResource(R.string.github_repo), modifier = Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { uriHandler.openUri(GitHubProject.PAGE_URL) }) {
            Text(stringResource(R.string.github_url_label))
        }
        when (val status = updateStatus) {
            UpdateStatus.Checking -> Text(stringResource(R.string.update_checking))
            UpdateStatus.UpToDate -> Text(stringResource(R.string.update_up_to_date, BuildConfig.VERSION_NAME))
            is UpdateStatus.Available -> {
                Text(
                    stringResource(R.string.update_available, status.latestTag, BuildConfig.VERSION_NAME),
                    color = MaterialTheme.colorScheme.primary,
                )
                Button(
                    onClick = { uriHandler.openUri(status.releaseUrl) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(stringResource(R.string.update_open_release))
                }
            }
            is UpdateStatus.Failed -> Text(
                stringResource(R.string.update_check_failed) + ": " + status.message,
                color = MaterialTheme.colorScheme.error,
            )
        }
        TextButton(onClick = onCheckUpdates) { Text(stringResource(R.string.check_updates)) }
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
