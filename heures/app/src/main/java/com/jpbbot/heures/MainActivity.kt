package com.jpbbot.heures

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var store: HoursStore
    private lateinit var statusText: TextView
    private lateinit var goButton: Button
    private lateinit var finButton: Button
    private lateinit var calculatePanel: LinearLayout
    private lateinit var monthLabel: TextView
    private lateinit var totalText: TextView
    private lateinit var entriesContainer: LinearLayout

    private var shownMonth: YearMonth = YearMonth.now()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = HoursStore(this)

        statusText = findViewById(R.id.statusText)
        goButton = findViewById(R.id.goButton)
        finButton = findViewById(R.id.finButton)
        calculatePanel = findViewById(R.id.calculatePanel)
        monthLabel = findViewById(R.id.monthLabel)
        totalText = findViewById(R.id.totalText)
        entriesContainer = findViewById(R.id.entriesContainer)

        goButton.setOnClickListener { onGo() }
        finButton.setOnClickListener { onFin() }
        findViewById<Button>(R.id.manualButton).setOnClickListener { pickManualEntry() }
        findViewById<Button>(R.id.calculateButton).setOnClickListener { toggleCalculate() }
        findViewById<Button>(R.id.prevMonthButton).setOnClickListener {
            shownMonth = shownMonth.minusMonths(1)
            renderMonth()
        }
        findViewById<Button>(R.id.nextMonthButton).setOnClickListener {
            shownMonth = shownMonth.plusMonths(1)
            renderMonth()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val running = store.runningStart()
        statusText.text = if (running == null) {
            getString(R.string.status_idle)
        } else {
            getString(R.string.status_running, formatTime(running))
        }
        goButton.isEnabled = running == null
        finButton.isEnabled = running != null
        if (calculatePanel.visibility == View.VISIBLE) renderMonth()
    }

    private fun onDataChanged() {
        refresh()
        HoursWidget.refreshAll(this)
    }

    private fun onGo() {
        if (store.start()) {
            toast(getString(R.string.toast_started))
        } else {
            toast(getString(R.string.toast_already_running))
        }
        onDataChanged()
    }

    private fun onFin() {
        val session = store.finish()
        if (session == null) {
            toast(getString(R.string.toast_not_running))
        } else {
            toast(getString(R.string.toast_finished, formatDuration(session.minutes)))
        }
        onDataChanged()
    }

    private fun toggleCalculate() {
        val show = calculatePanel.visibility != View.VISIBLE
        calculatePanel.visibility = if (show) View.VISIBLE else View.GONE
        if (show) renderMonth()
    }

    private fun renderMonth() {
        monthLabel.text = shownMonth.format(monthFormat)
        val sessions = HoursMath.sessionsOf(shownMonth, store.sessions())
        totalText.text = getString(
            R.string.total_format,
            formatDuration(HoursMath.totalMinutes(sessions)),
            formatDecimal(HoursMath.totalMinutes(sessions)),
        )

        entriesContainer.removeAllViews()
        if (sessions.isEmpty()) {
            addRow(getString(R.string.empty_month))
            return
        }
        sessions.forEach { session ->
            val line = "${formatDay(session.startDate())}   " +
                "${formatTime(session.start)} → ${formatTime(session.end)}   " +
                formatDuration(session.minutes)
            addRow(line) { confirmDelete(session) }
        }
    }

    private fun addRow(text: String, onLongPress: (() -> Unit)? = null) {
        entriesContainer.addView(
            TextView(this).apply {
                this.text = text
                textSize = 15f
                setPadding(dp(12), dp(12), dp(12), dp(12))
                onLongPress?.let { action ->
                    setOnLongClickListener {
                        action()
                        true
                    }
                }
            },
        )
    }

    private fun confirmDelete(session: WorkSession) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_title)
            .setMessage(
                getString(
                    R.string.delete_message,
                    formatDay(session.startDate()),
                    formatTime(session.start),
                    formatTime(session.end),
                ),
            )
            .setPositiveButton(R.string.delete) { _, _ ->
                store.delete(session)
                onDataChanged()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Asks for the day, then the start and end times, and saves the result. */
    private fun pickManualEntry() {
        val today = LocalDate.now()
        DatePickerDialog(
            this,
            { _, year, month, day ->
                val date = LocalDate.of(year, month + 1, day)
                TimePickerDialog(
                    this,
                    { _, startHour, startMinute ->
                        TimePickerDialog(
                            this,
                            { _, endHour, endMinute ->
                                saveManual(
                                    date,
                                    LocalTime.of(startHour, startMinute),
                                    LocalTime.of(endHour, endMinute),
                                )
                            },
                            17,
                            0,
                            true,
                        ).apply { setTitle(R.string.pick_end) }.show()
                    },
                    8,
                    0,
                    true,
                ).apply { setTitle(R.string.pick_start) }.show()
            },
            today.year,
            today.monthValue - 1,
            today.dayOfMonth,
        ).show()
    }

    private fun saveManual(date: LocalDate, startTime: LocalTime, endTime: LocalTime) {
        val session = HoursMath.manualSession(date, startTime, endTime)
        if (session == null) {
            toast(getString(R.string.toast_same_time))
            return
        }
        store.add(session)
        shownMonth = YearMonth.from(date)
        toast(getString(R.string.toast_manual_saved))
        onDataChanged()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val monthFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.FRANCE)
    }
}
