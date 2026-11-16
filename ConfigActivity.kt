package org.example.flipweather

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.*

class ConfigActivity : Activity() {
    private var locs = mutableListOf<Loc>()
    private lateinit var saved: LinearLayout
    private lateinit var results: LinearLayout
    private lateinit var gpsBox: CheckBox

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id != AppWidgetManager.INVALID_APPWIDGET_ID)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        locs = Store.saved(this).toMutableList()

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }

        val last = Store.lastRun(this)
        root.addView(TextView(this).apply {
            textSize = 12f
            text = "Last background refresh before opening: " +
                if (last == 0L) "never" else java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(last)
        })

        root.addView(TextView(this).apply { text = "Background opacity" })
        root.addView(SeekBar(this).apply {
            max = 100; progress = Store.alpha(this@ConfigActivity) * 100 / 255
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    Store.setAlpha(this@ConfigActivity, p * 255 / 100); WeatherWidget.render(this@ConfigActivity)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })

        gpsBox = CheckBox(this).apply {
            text = "Follow my location (shown first)"
            isChecked = Store.gps(this@ConfigActivity)
            setOnCheckedChangeListener { _, on ->
                Store.setGps(this@ConfigActivity, on)
                if (on) askLocation() else save()
            }
        }
        root.addView(gpsBox)

        root.addView(TextView(this).apply { text = "\nLocations (first one is home)" })
        saved = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(saved)

        val q = EditText(this).apply { hint = "Search city"; setSingleLine() }
        root.addView(q)
        root.addView(Button(this).apply {
            text = "Search"
            setOnClickListener {
                val term = q.text.toString().trim()
                if (term.isEmpty()) return@setOnClickListener
                Thread {
                    val r = try { Weather.search(term) } catch (e: Exception) { emptyList() }
                    runOnUiThread { showResults(r) }
                }.start()
            }
        })
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(results)

        root.addView(Button(this).apply {
            text = "Done"
            setOnClickListener {
                save()
                if (id != AppWidgetManager.INVALID_APPWIDGET_ID)
                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                finish()
            }
        })
        setContentView(ScrollView(this).apply { addView(root, ViewGroup.LayoutParams(-1, -2)) })
        refreshSaved()
        WeatherWidget.heal(this)
    }

    private fun askLocation() {
        if (!Here.hasPermission(this)) requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        else afterForeground()
    }

    // Updating while the widget is in the background needs "Allow all the time".
    private fun afterForeground() {
        if (Build.VERSION.SDK_INT >= 29 &&
            checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
        else fetchNow()
    }

    private fun fetchNow() {
        Thread { Here.refresh(this); runOnUiThread { save() } }.start()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        val ok = res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED
        when (code) {
            1 -> if (ok) afterForeground() else {
                Toast.makeText(this, "Location permission denied", Toast.LENGTH_SHORT).show()
                gpsBox.isChecked = false
            }
            2 -> fetchNow()   // still works while the app is open if "all the time" was declined
        }
    }

    private fun save() {
        Store.setLocations(this, locs)
        WeatherWidget.render(this)
        RefreshWorker.schedule(this); RefreshWorker.now(this)
    }

    private fun showResults(r: List<Pair<String, Loc>>) {
        results.removeAllViews()
        if (r.isEmpty()) results.addView(TextView(this).apply { text = "No results (or offline)" })
        r.forEach { (label, loc) ->
            results.addView(Button(this).apply {
                text = "+ $label"; isAllCaps = false
                setOnClickListener { locs.add(loc); results.removeAllViews(); save(); refreshSaved() }
            })
        }
    }

    private fun refreshSaved() {
        saved.removeAllViews()
        locs.forEachIndexed { i, l ->
            saved.addView(LinearLayout(this).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(TextView(this@ConfigActivity).apply { text = l.name }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(Button(this@ConfigActivity).apply {
                    text = "✕"
                    setOnClickListener { locs.removeAt(i); save(); refreshSaved() }
                })
            })
        }
    }
}
