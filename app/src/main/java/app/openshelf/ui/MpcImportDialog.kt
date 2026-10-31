package app.openshelf.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.openshelf.R
import app.openshelf.core.mpc.MpcImport
import app.openshelf.core.mpc.MpcPlanRow
import app.openshelf.core.mpc.MpcRowStatus

@Composable
fun MpcImportDialog(
    rows: List<MpcPlanRow>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    val selectable = remember(rows) { rows.filter { MpcImport.selectable(it.status) }.map { it.profileId }.toSet() }
    var selected by remember(rows) {
        mutableStateOf(
            rows.filter { it.status == MpcRowStatus.READY || it.status == MpcRowStatus.NEEDS_PERMISSION }
                .map { it.profileId }
                .toSet(),
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.import_mpc_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(stringResource(R.string.import_mpc_hint), style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { selected = selectable }) {
                        Text(stringResource(R.string.select_all))
                    }
                    TextButton(onClick = { selected = emptySet() }) {
                        Text(stringResource(R.string.clear_selection))
                    }
                }
                for (row in rows) {
                    ProfileRow(
                        row = row,
                        checked = row.profileId in selected,
                        onChecked = { checked ->
                            selected = if (checked) selected + row.profileId else selected - row.profileId
                        },
                    )
                }
                if (rows.any { it.status == MpcRowStatus.PRIVATE }) {
                    Text(
                        stringResource(R.string.import_mpc_private_hint),
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) {
                Text(stringResource(R.string.import_mpc_mount))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun ProfileRow(
    row: MpcPlanRow,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    val enabled = MpcImport.selectable(row.status)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked && enabled,
            onCheckedChange = onChecked,
            enabled = enabled,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(row.name, style = MaterialTheme.typography.bodyLarge)
            Text(statusLabel(row), style = MaterialTheme.typography.bodySmall)
            val path = row.preview?.takeIf { it.isNotBlank() }
            if (path != null && row.status != MpcRowStatus.PRIVATE) {
                Text(
                    path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun statusLabel(row: MpcPlanRow): String {
    return when (row.status) {
        MpcRowStatus.READY -> stringResource(R.string.import_mpc_ready)
        MpcRowStatus.NEEDS_PERMISSION -> stringResource(R.string.import_mpc_needs_permission)
        MpcRowStatus.PRIVATE -> stringResource(R.string.import_mpc_private)
        MpcRowStatus.DUPLICATE -> stringResource(R.string.import_mpc_duplicate, row.duplicateOf.orEmpty())
        MpcRowStatus.ALREADY_MOUNTED -> stringResource(R.string.import_mpc_already)
        MpcRowStatus.UNUSABLE -> stringResource(R.string.import_mpc_unusable)
    }
}
