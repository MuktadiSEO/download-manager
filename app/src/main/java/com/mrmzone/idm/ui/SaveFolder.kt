package com.mrmzone.idm.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mrmzone.idm.data.Category
import com.mrmzone.idm.engine.DM
import com.mrmzone.idm.engine.FileStore

/**
 * "Save to … [Change]": lets the user download into ANY folder (phone or SD card) via the system folder picker.
 * The chosen folder is remembered for the next downloads; "Default" goes back to Download/MRM Zone/<category>.
 */
class SaveFolder(private val act: AppCompatActivity, private val onChanged: () -> Unit) {
    private val launcher: ActivityResultLauncher<Uri?> =
        act.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runCatching {
                    act.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }
                DM.prefs.saveTree = uri.toString()
                onChanged()
            }
        }

    fun label(cat: Category) = FileStore.folderLabel(DM.prefs.saveTree, cat)

    fun change(cat: Category) {
        val items = arrayOf("📁  Choose any folder…", "Default: Download/${FileStore.ROOT}/${cat.folder}")
        MaterialAlertDialogBuilder(act).setTitle("Save downloads to").setItems(items) { _, w ->
            if (w == 0) runCatching { launcher.launch(DM.prefs.saveTree?.let { Uri.parse(it) }) }
            else { DM.prefs.saveTree = null; onChanged() }
        }.show()
    }
}
