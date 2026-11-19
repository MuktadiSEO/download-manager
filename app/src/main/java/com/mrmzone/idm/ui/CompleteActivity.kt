package com.mrmzone.idm.ui

import android.content.Intent
import android.os.Bundle
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.mrmzone.idm.R
import com.mrmzone.idm.engine.DM
import com.mrmzone.idm.engine.FileStore
import com.mrmzone.idm.util.Fmt

/** "Download complete" dialog: Open / Open folder / Close */
class CompleteActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = DM.get(intent.getLongExtra("id", 0)) ?: run { finish(); return }
        val p = (18 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(p, p, p, p) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
        head.addView(ImageView(this).apply { setImageResource(R.drawable.logo_mrm) }, LinearLayout.LayoutParams(p * 2, p * 2))
        head.addView(TextView(this).apply { text = "Download complete"; textSize = 19f; setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(p / 2, 0, 0, 0) })
        root.addView(head)
        fun line(k: String, v: String) = root.addView(TextView(this).apply { text = "$k: $v"; textSize = 14f; setPadding(0, p / 3, 0, 0) })
        line("File", d.fileName)
        line("Size", Fmt.size(d.totalSize))
        line("Saved to", FileStore.folderLabel(d.saveDir, d.category))
        line("Address", d.url)
        val cb = CheckBox(this).apply { text = "Don't show this dialog again"; setPadding(0, p / 2, 0, 0) }
        root.addView(cb)
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.END; setPadding(0, p / 2, 0, 0) }
        fun btn(t: String, filled: Boolean, a: () -> Unit) = btns.addView(MaterialButton(this, null,
            if (filled) com.google.android.material.R.attr.materialButtonStyle else com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = t; setOnClickListener { if (cb.isChecked) DM.prefs.showCompleteDialog = false; a(); finish() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = p / 3 })
        btn("Close", false) {}
        btn("Folder", false) { runCatching { startActivity(FileStore.folderIntent(d)) }.onFailure { toast("Saved in Download/MRM Zone") } }
        btn("Open", true) {
            val i = FileStore.openIntent(this, d)
            if (i != null) runCatching { startActivity(Intent.createChooser(i, "Open with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { toast("No app can open this file") }
        }
        root.addView(btns)
        setContentView(root)
    }
    private fun toast(s: String) = Toast.makeText(applicationContext, s, Toast.LENGTH_SHORT).show()
}
