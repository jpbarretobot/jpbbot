package fr.jpb.meteowidget

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
        buildAmbienceChips()
        buildPhotoRows()
    }

    override fun onResume() {
        super.onResume()
        render()
        if (repo.place == null || repo.followGps) useGps(ask = repo.place == null) else refresh(force = false)
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
        val container = findViewById<FrameLayout>(R.id.preview)
        container.removeAllViews()
        container.addView(WidgetRenderer.build(this, preview).apply(this, container))
        WeatherWidget.refreshAll(this)
    }

    private fun refresh(force: Boolean) = scope.launch {
        val ok = runCatching { repo.refresh(force) }.isSuccess
        if (!ok) toast("Météo indisponible (connexion ?)")
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
            val loc = Locator.locate(this@MainActivity, allowFresh = true)
            if (loc == null) {
                toast("Position introuvable : activez la localisation ou cherchez une ville.")
            } else {
                val old = repo.place
                if (old == null || UpdateWorker.distanceKm(old.lat, old.lon, loc.latitude, loc.longitude) > 1.0) {
                    repo.place = Place(Locator.placeName(this@MainActivity, loc), loc.latitude, loc.longitude)
                }
            }
            refresh(force = false)
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
                setOnClickListener { preview = value; render() }
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
        const val REQ_PHOTO = 100
    }
}
