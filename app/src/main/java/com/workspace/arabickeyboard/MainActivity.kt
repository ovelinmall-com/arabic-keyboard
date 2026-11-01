package com.workspace.arabickeyboard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var statusView: TextView
    private lateinit var enableButton: Button
    private lateinit var pickerButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(28, 32, 28, 32)
            setBackgroundColor(0xFFF1F3F6.toInt())
        }
        root.addView(TextView(this).apply {
            text = "مفاتيح عربية"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(0xFF17212B.toInt())
        }, matchWrap())
        root.addView(TextView(this).apply {
            text = "فعّلها من إعدادات أندرويد، ثم اخترها من قائمة لوحات الإدخال."
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xFF6F7B87.toInt())
            setPadding(0, 14, 0, 22)
        }, matchWrap())
        statusView = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFF17212B.toInt())
            setPadding(0, 0, 0, 18)
        }
        root.addView(statusView, matchWrap())
        enableButton = Button(this).apply {
            text = "تفعيل لوحة المفاتيح"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        }
        root.addView(enableButton, matchWrap())
        pickerButton = Button(this).apply {
            text = "اختيار لوحة المفاتيح"
            setOnClickListener { getSystemService(InputMethodManager::class.java)?.showInputMethodPicker() }
        }
        root.addView(pickerButton, matchWrap())
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refreshKeyboardStatus()
    }

    private fun refreshKeyboardStatus() {
        val enabled = getSystemService(InputMethodManager::class.java)
            ?.enabledInputMethodList
            ?.any { it.packageName == packageName } == true
        statusView.text = if (enabled) {
            "لوحة المفاتيح مفعّلة"
        } else {
            "لوحة المفاتيح غير مفعّلة"
        }
        enableButton.visibility = if (enabled) android.view.View.GONE else android.view.View.VISIBLE
        pickerButton.visibility = if (enabled) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun matchWrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        bottomMargin = 10
    }
}
