package com.aliothmoon.maafw.testapp

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** 三行等高铺满全屏，PI 里的点击坐标按比例落在哪一行，不随分辨率变 */
class InputTestActivity : Activity() {

    private lateinit var normal: EditText
    private lateinit var password: EditText
    private lateinit var echo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        normal = field(R.string.hint_normal, InputType.TYPE_CLASS_TEXT)
        password = field(R.string.hint_password, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        echo = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(PADDING, 0, PADDING, 0)
        }
        watch(normal, "normal")
        watch(password, "password")
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
                listOf(normal, password, echo).forEach {
                    addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                }
            },
        )
        refreshEcho()
        Log.i(TAG, "created displayId=${displayId()}")
    }

    private fun field(hint: Int, inputType: Int) = EditText(this).apply {
        setHint(hint)
        this.inputType = inputType
        isSingleLine = true
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        setPadding(PADDING, 0, PADDING, 0)
    }

    private fun watch(field: EditText, name: String) {
        field.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    Log.i(TAG, "text field=$name value=[$s] displayId=${displayId()}")
                    refreshEcho()
                }
            },
        )
        field.setOnFocusChangeListener { _, hasFocus ->
            Log.i(TAG, "focus field=$name focused=$hasFocus displayId=${displayId()}")
        }
    }

    private fun refreshEcho() {
        echo.text = "normal=[${normal.text}]  password=[${password.text}]"
    }

    @Suppress("DEPRECATION")
    private fun displayId(): Int = windowManager.defaultDisplay.displayId

    private companion object {
        const val TAG = "MaaFwTestApp"
        const val PADDING = 48
    }
}
