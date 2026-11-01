package fr.jpb.meteowidget

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : Activity() {

    private val scope = MainScope()
    private lateinit var repo: WeatherRepository
    private var preview: Pair<Condition, Phase>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        repo = WeatherRepository(this)

        findViewById<Button>(R.id.use_gps).setOnClickListener { useGps(ask = true) }
        val city = findViewById<EditText>(R.id.city)
        findViewById<Button>(R.id.search).setOnClickListener { search(city.text.toString()) }
        city.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) search(city.text.toString())
            true
        }
        findViewById<Button>(R.id.pin).setOnClickListener { pinWidget() }
        findViewById<Button>(R.id.battery).setOnClickListener {
            @SuppressLint("BatteryLife")
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            runCatching { startActivity(i) }
        }
        // Options de test (émulateur) : --es test_ambience RAIN:DAY / --ez pin true
        intent.getStringExtra("test_ambience")?.let { v ->
            repo.forcedAmbience = v.split(":").let { runCatching { Condition.valueOf(it[0]) to Phase.valueOf(it[1]) }.getOrNull() }
        }
        if (intent.getBooleanExtra("pin", false)) pinWidget()
        intent.getIntExtra("test_interval_s", 0).takeIf { it > 0 }?.let {
            getSharedPreferences("meteo", MODE_PRIVATE).edit().putLong("interval", it * 1000L).apply()
        }
        findViewById<Button>(R.id.background_location).setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android ouvre l'écran des autorisations : choisir « Toujours autoriser »
                requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), REQ_BACKGROUND)
            }
        }
        buildAmbienceChips()
        buildPhotoRows()
        findViewById<TextView>(R.id.credits).text =
            resources.openRawResource(R.raw.photo_credits).bufferedReader().use { it.readText() }
    }

    override fun onResume() {
        super.onResume()
        Refresher.schedule(this)
        val power = getSystemService(PowerManager::class.java)
        findViewById<Button>(R.id.battery).visibility =
            if (power.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
        findViewById<Button>(R.id.background_location).visibility =
            if (repo.followGps && Locator.hasPermission(this) && !Locator.hasBackgroundPermission(this)) View.VISIBLE else View.GONE
        render()
        if (repo.followGps && !Locator.hasPermission(this)) useGps(ask = repo.place == null) else refresh(force = false)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render() {
        val place = repo.place
        findViewById<TextView>(R.id.current_place).text = when {
            place == null -> "Aucun lieu choisi"
            repo.followGps -> "Position actuelle : ${place.name}"
            else -> "Ville choisie : ${place.name}"
        }
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE)
        val next = Refresher.nextAt(this)
        val last = repo.cached?.fetchedAt
        findViewById<TextView>(R.id.update_status).text = buildString {
            append("Météo du ").append(if (last != null) fmt.format(java.util.Date(last)) else "—")
            if (next > System.currentTimeMillis()) append(" · prochaine mise à jour vers ").append(fmt.format(java.util.Date(next)))
        }
        findViewById<TextView>(R.id.update_log).text = UpdateLog.entries(this).take(12).joinToString("\n")
        val container = findViewById<FrameLayout>(R.id.preview)
        container.removeAllViews()
        container.addView(WidgetRenderer.build(this, preview).apply(this, container))
        WeatherWidget.refreshAll(this)
    }

    private fun pinWidget() {
        val manager = AppWidgetManager.getInstance(this)
        if (manager.isRequestPinAppWidgetSupported) {
            manager.requestPinAppWidget(ComponentName(this, WeatherWidget::class.java), null, null)
        } else {
            toast("Appui long sur l'écran d'accueil ▸ Widgets ▸ Météo Paysage")
        }
    }

    private fun refresh(force: Boolean) = scope.launch {
        if (force) runCatching { repo.refresh(force = true) }
        val ok = Updater.update(this@MainActivity, "appli", budgetMs = 20_000)
        if (!ok && repo.place != null) toast("Météo indisponible (connexion ?)")
        render()
    }

    private fun useGps(ask: Boolean) {
        if (!Locator.hasPermission(this)) {
            if (ask) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION)
            }
            return
        }
        repo.followGps = true
        scope.launch {
            Updater.update(this@MainActivity, "appli", budgetMs = 20_000)
            if (repo.place == null) toast("Position introuvable : activez la localisation ou cherchez une ville.")
            render()
            findViewById<Button>(R.id.background_location).visibility =
                if (!Locator.hasBackgroundPermission(this@MainActivity)) View.VISIBLE else View.GONE
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) useGps(ask = false)
            else toast("Sans localisation, recherchez votre ville ci-dessous.")
        }
    }

    private fun search(query: String) {
        if (query.isBlank()) return
        val results = findViewById<LinearLayout>(R.id.results)
        results.removeAllViews()
        scope.launch {
            val places = runCatching { repo.searchCity(query) }.getOrElse {
                toast("Recherche impossible (connexion ?)"); return@launch
            }
            if (places.isEmpty()) toast("Aucune ville trouvée")
            for (p in places) {
                results.addView(Button(this@MainActivity, null, 0, R.style.Btn).apply {
                    text = if (p.detail.isBlank()) p.name else "${p.name}  ·  ${p.detail}"
                    setOnClickListener {
                        repo.followGps = false
                        repo.place = p
                        results.removeAllViews()
                        findViewById<EditText>(R.id.city).text.clear()
                        refresh(force = true)
                    }
                })
            }
        }
    }

    private fun buildAmbienceChips() {
        val row = findViewById<LinearLayout>(R.id.ambiences)
        val options = listOf<Pair<String, Pair<Condition, Phase>?>>(
            "Météo réelle" to null,
            "Soleil" to (Condition.CLEAR to Phase.DAY),
            "Lever" to (Condition.CLEAR to Phase.SUNRISE),
            "Coucher" to (Condition.PARTLY to Phase.SUNSET),
            "Nuit" to (Condition.CLEAR to Phase.NIGHT),
            "Nuageux" to (Condition.CLOUDY to Phase.DAY),
            "Nuit nuageuse" to (Condition.CLOUDY to Phase.NIGHT),
            "Pluie" to (Condition.RAIN to Phase.DAY),
            "Pluie de nuit" to (Condition.RAIN to Phase.NIGHT),
            "Orage" to (Condition.STORM to Phase.DAY),
            "Neige" to (Condition.SNOW to Phase.DAY),
            "Neige de nuit" to (Condition.SNOW to Phase.NIGHT),
            "Brouillard" to (Condition.FOG to Phase.DAY),
        )
        for ((label, value) in options) {
            row.addView(Button(this, null, 0, R.style.Btn).apply {
                text = label
                setOnClickListener {
                    preview = value
                    if (value == null) repo.forcedAmbience = null
                    render()
                }
            })
        }
    }

    private fun buildPhotoRows() {
        val list = findViewById<LinearLayout>(R.id.photos)
        list.removeAllViews()
        for (b in Backdrop.entries) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(TextView(this).apply {
                text = b.label + if (Photos.has(this@MainActivity, b)) "  ✓" else ""
                setTextColor(getColor(R.color.text))
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.gravity = android.view.Gravity.CENTER_VERTICAL
            row.addView(Button(this, null, 0, R.style.Btn).apply {
                text = "Choisir"
                setOnClickListener {
                    val pick = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
                    startActivityForResult(pick, REQ_PHOTO + b.ordinal)
                }
            })
            row.addView(Button(this, null, 0, R.style.Btn).apply {
                text = "Retirer"
                visibility = if (Photos.has(this@MainActivity, b)) View.VISIBLE else View.GONE
                setOnClickListener {
                    Photos.remove(this@MainActivity, b)
                    buildPhotoRows()
                    render()
                }
            })
            list.addView(row)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val b = Backdrop.entries.getOrNull(requestCode - REQ_PHOTO) ?: return
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { Photos.importFrom(this@MainActivity, b, uri) }.getOrDefault(false) }
            if (!ok) toast("Impossible de lire cette image")
            buildPhotoRows()
            render()
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private companion object {
        const val REQ_LOCATION = 1
        const val REQ_BACKGROUND = 2
        const val REQ_PHOTO = 100
    }
}
