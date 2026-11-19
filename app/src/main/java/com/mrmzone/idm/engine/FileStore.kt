package com.mrmzone.idm.engine

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.mrmzone.idm.data.DownloadItem
import java.io.File

object FileStore {
    const val ROOT = "MRM Zone"

    fun tempFile(ctx: Context, item: DownloadItem): File {
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "temp").apply { mkdirs() }
        return File(dir, "${item.id}.part")
    }

    fun mime(name: String): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"

    /** Moves a finished temp file into public Download/MRM Zone/<category>. Returns final path (file path or content uri). */
    fun publish(ctx: Context, temp: File, item: DownloadItem): String {
        item.saveDir?.let { tree -> publishToTree(ctx, temp, item, tree)?.let { return it } }
        val sub = "$ROOT/${item.category.folder}"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cr = ctx.contentResolver
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, item.fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime(item.fileName))
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$sub")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return temp.absolutePath
            try {
                cr.openOutputStream(uri)!!.use { out -> temp.inputStream().use { it.copyTo(out, 256 * 1024) } }
                cv.clear(); cv.put(MediaStore.Downloads.IS_PENDING, 0)
                cr.update(uri, cv, null, null)
                temp.delete()
                // read back the actual name (MediaStore may rename duplicates)
                cr.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) item.fileName = it.getString(0) ?: item.fileName
                }
                return uri.toString()
            } catch (e: Exception) {
                runCatching { cr.delete(uri, null, null) }
                return temp.absolutePath
            }
        } else {
            return try {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), sub).apply { mkdirs() }
                var dest = File(dir, item.fileName)
                var i = 1
                val base = item.fileName.substringBeforeLast('.'); val ext = item.fileName.substringAfterLast('.', "")
                while (dest.exists()) { dest = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext"); i++ }
                if (!temp.renameTo(dest)) { temp.copyTo(dest); temp.delete() }
                item.fileName = dest.name
                dest.absolutePath
            } catch (e: Exception) { temp.absolutePath }
        }
    }

    /** Saves into a folder the user picked (any folder / SD card). Returns the document uri, or null to fall back. */
    private fun publishToTree(ctx: Context, temp: File, item: DownloadItem, tree: String): String? = runCatching {
        val cr = ctx.contentResolver
        val treeUri = Uri.parse(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val doc = DocumentsContract.createDocument(cr, parent, mime(item.fileName), item.fileName) ?: return null
        try {
            cr.openOutputStream(doc)!!.use { out -> temp.inputStream().use { it.copyTo(out, 256 * 1024) } }
        } catch (e: Exception) { runCatching { DocumentsContract.deleteDocument(cr, doc) }; throw e }
        temp.delete()
        // the provider may rename duplicates: "file (1).zip"
        cr.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) item.fileName = it.getString(0) ?: item.fileName
        }
        doc.toString()
    }.getOrNull()

    /** "Download/MRM Zone/Video", "Movies/My folder", "SD card/Films" … for showing to the user. */
    fun folderLabel(tree: String?, category: com.mrmzone.idm.data.Category): String {
        if (tree == null) return "Download/$ROOT/${category.folder}"
        return runCatching {
            val id = DocumentsContract.getTreeDocumentId(Uri.parse(tree))   // "primary:Movies/X" or "1A2B-3C4D:Films"
            val vol = id.substringBefore(':'); val path = id.substringAfter(':', "")
            val root = if (vol == "primary") "Phone" else "SD card"
            if (path.isEmpty()) root else if (vol == "primary") path else "$root/$path"
        }.getOrDefault("Custom folder")
    }

    fun uriFor(ctx: Context, path: String): Uri =
        if (path.startsWith("content://")) Uri.parse(path)
        else FileProvider.getUriForFile(ctx, ctx.packageName + ".files", File(path))

    fun exists(ctx: Context, path: String?): Boolean {
        if (path == null) return false
        return if (path.startsWith("content://")) runCatching {
            ctx.contentResolver.openFileDescriptor(Uri.parse(path), "r")?.use { true } ?: false
        }.getOrDefault(false) else File(path).exists()
    }

    fun delete(ctx: Context, path: String?) {
        if (path == null) return
        runCatching {
            val u = Uri.parse(path)
            if (path.startsWith("content://") && DocumentsContract.isDocumentUri(ctx, u)) DocumentsContract.deleteDocument(ctx.contentResolver, u)
            else if (path.startsWith("content://")) ctx.contentResolver.delete(u, null, null) else File(path).delete()
        }
    }

    fun openIntent(ctx: Context, item: DownloadItem): Intent? {
        val p = item.finalPath ?: return null
        val uri = uriFor(ctx, p)
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime(item.fileName))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun shareIntent(ctx: Context, item: DownloadItem): Intent? {
        val p = item.finalPath ?: return null
        return Intent(Intent.ACTION_SEND).setType(mime(item.fileName))
            .putExtra(Intent.EXTRA_STREAM, uriFor(ctx, p))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun folderIntent(item: DownloadItem?): Intent {
        item?.saveDir?.let { tree ->
            runCatching {
                val t = Uri.parse(tree)
                val dir = DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t))
                return Intent(Intent.ACTION_VIEW).setDataAndType(dir, "vnd.android.document/directory")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        val rel = "Download/$ROOT" + (item?.let { "/" + it.category.folder } ?: "")
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3A" + Uri.encode(rel))
        return Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.document/directory")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
