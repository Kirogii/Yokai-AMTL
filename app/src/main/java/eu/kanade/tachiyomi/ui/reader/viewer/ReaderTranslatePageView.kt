package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.R

/**
 * Custom view displayed as the translate stub "page 0" in the reader.
 * Shows a large centered button to trigger translation.
 *
 * In pager mode: clicking translates the current real page.
 * In webtoon mode: clicking translates all loaded pages.
 */
class ReaderTranslatePageView(context: Context) : FrameLayout(context) {

    private lateinit var translateButton: Button
    private lateinit var statusText: TextView
    private var isTranslating: Boolean = false

    var onTranslateClicked: (() -> Unit)? = null

    init {
        setupView()
    }

    private fun setupView() {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        setBackgroundColor(Color.parseColor("#1E1E2E"))

        val innerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT,
            )
        }

        // Title
        val title = TextView(context).apply {
            text = "AI Translation"
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 16)
        }

        // Subtitle
        val subtitle = TextView(context).apply {
            text = "Tap below to translate this chapter"
            setTextColor(Color.parseColor("#AAAAAA"))
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }

        // Translate button
        translateButton = Button(context).apply {
            text = "Translate"
            setTextColor(Color.WHITE)
            textSize = 18f
            setBackgroundColor(Color.parseColor("#0052A3"))
            setPadding(64, 20, 64, 20)
            setOnClickListener {
                if (!isTranslating) {
                    onTranslateClicked?.invoke()
                }
            }
        }

        // Status text (hidden until translating)
        statusText = TextView(context).apply {
            text = ""
            setTextColor(Color.parseColor("#28A745"))
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
            visibility = GONE
        }

        innerLayout.addView(title)
        innerLayout.addView(subtitle)
        innerLayout.addView(translateButton)
        innerLayout.addView(statusText)
        addView(innerLayout)
    }

    fun setTranslating(active: Boolean) {
        isTranslating = active
        translateButton.isEnabled = !active
        translateButton.text = if (active) "Translating..." else "Translate"
        statusText.text = if (active) "Please wait..." else ""
        statusText.visibility = if (active) VISIBLE else GONE
    }

    fun setStatus(message: String, isError: Boolean = false) {
        statusText.text = message
        statusText.setTextColor(
            if (isError) Color.parseColor("#FF4D4D") else Color.parseColor("#28A745"),
        )
        statusText.visibility = VISIBLE
    }
}
