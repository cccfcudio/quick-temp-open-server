package app.openshelf.share

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract

object SafIntents {
    fun openTree(relative: String?, initialUri: Uri? = null): Intent {
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
            val initial = initialUri ?: relative?.let { documentUri(it) }
            if (initial != null) {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
            }
        }
    }

    private fun documentUri(relative: String): Uri {
        return DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:$relative",
        )
    }
}
