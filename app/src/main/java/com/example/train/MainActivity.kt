package com.example.train

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity : AppCompatActivity(), GameView.Host {

    private lateinit var gv: GameView
    private var updateDialogShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)

        gv = GameView(this, this)
        setContentView(gv)
        hideBars()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!gv.onBack()) finish()
            }
        })

        checkUpdate(false)
    }

    private fun hideBars() {
        val c = WindowCompat.getInsetsController(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    override fun onResume() {
        super.onResume()
        gv.resume()
    }

    override fun onPause() {
        gv.pause()
        super.onPause()
    }

    // ── GameView.Host ───────────────────────────────────────────────────

    override fun getBest(key: String): Double {
        return getSharedPreferences("train", MODE_PRIVATE).getFloat(key, -1f).toDouble()
    }

    override fun putBest(key: String, v: Double) {
        getSharedPreferences("train", MODE_PRIVATE).edit().putFloat(key, v.toFloat()).apply()
    }

    override fun checkUpdate(manual: Boolean) {
        gv.updateStatus = if (manual) "Update dhoondh raha hai..." else ""
        UpdateChecker.check(this) { info ->
            if (info == null) {
                gv.updateStatus = if (manual) "Aap latest version par ho" else ""
            } else if (!updateDialogShown || manual) {
                updateDialogShown = true
                gv.updateStatus = "Naya version mila"
                AlertDialog.Builder(this)
                    .setTitle("Naya version mila")
                    .setMessage("Train ka naya update aaya hai. Abhi update karein?")
                    .setPositiveButton("Update") { _, _ -> startUpdate(info) }
                    .setNegativeButton("Baad me", null)
                    .show()
            }
        }
    }

    private fun startUpdate(info: UpdateChecker.Info) {
        gv.updateStatus = "Download shuru..."
        UpdateChecker.downloadAndInstall(
            this, info,
            onProgress = { p -> gv.updateStatus = if (p >= 100) "Install screen khul rahi hai" else "Download $p%" },
            onError = { m ->
                gv.updateStatus = "Update fail: $m"
                Toast.makeText(this, "Update nahi hua: $m", Toast.LENGTH_LONG).show()
            },
            onInstallFailed = {
                gv.updateStatus = "Install screen nahi khuli"
                Toast.makeText(this, "Install screen nahi khuli, dobara try karo", Toast.LENGTH_LONG).show()
            }
        )
    }
}
