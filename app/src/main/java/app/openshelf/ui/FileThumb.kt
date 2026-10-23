package app.openshelf.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.openshelf.R
import app.openshelf.core.browse.FileIcon
import app.openshelf.core.browse.FileIcons
import app.openshelf.share.AndroidJpegPreview
import app.openshelf.share.BrowserRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
fun FileThumb(
    mountId: String?,
    folder: String,
    row: BrowserRow,
    modifier: Modifier = Modifier,
) {
    val entry = row as? BrowserRow.EntryRow
    val icon = when (row) {
        is BrowserRow.MountRow -> FileIcon.FOLDER
        is BrowserRow.EntryRow -> FileIcons.of(row.entry.name, row.entry.directory)
    }
    val context = LocalContext.current
    var image by remember(mountId, folder, entry?.entry?.name, entry?.entry?.modifiedEpochMs) {
        mutableStateOf<ImageBitmap?>(null)
    }
    if (mountId != null && entry != null && !entry.entry.directory &&
        (icon == FileIcon.IMAGE || icon == FileIcon.VIDEO)
    ) {
        val name = entry.entry.name
        LaunchedEffect(mountId, folder, name, entry.entry.modifiedEpochMs, entry.entry.size) {
            val relative = if (folder.isEmpty()) name else "$folder/$name"
            val decoded = withContext(Dispatchers.IO) {
                val bytes = AndroidJpegPreview.get(context).jpeg(mountId, relative, 160) ?: return@withContext null
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }
            if (decoded != null && isActive) {
                image = decoded
            }
        }
    }
    Box(modifier.size(48.dp), contentAlignment = Alignment.Center) {
        val shown = image
        if (shown != null) {
            Image(
                bitmap = shown,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop,
            )
            if (icon == FileIcon.VIDEO) {
                Text(
                    "▶",
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 4.dp, bottom = 2.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        } else {
            Icon(
                painter = painterResource(iconRes(icon)),
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

private fun iconRes(icon: FileIcon): Int = when (icon) {
    FileIcon.FOLDER -> R.drawable.ic_file_folder
    FileIcon.IMAGE -> R.drawable.ic_file_image
    FileIcon.AUDIO -> R.drawable.ic_file_audio
    FileIcon.VIDEO -> R.drawable.ic_file_video
    FileIcon.PDF -> R.drawable.ic_file_pdf
    FileIcon.ARCHIVE -> R.drawable.ic_file_zip
    FileIcon.TEXT -> R.drawable.ic_file_text
    FileIcon.CODE -> R.drawable.ic_file_code
    FileIcon.SHEET -> R.drawable.ic_file_sheet
    FileIcon.SLIDES -> R.drawable.ic_file_slides
    FileIcon.OTHER -> R.drawable.ic_file_other
}
