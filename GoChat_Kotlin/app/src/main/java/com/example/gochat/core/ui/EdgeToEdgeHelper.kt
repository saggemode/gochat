package com.example.gochat.core.ui

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding

/**
 * Centralized Edge-to-Edge helper for GoChat.
 *
 * With targetSdk 35 (Android 15), edge-to-edge is enforced. These helpers
 * ensure content is never occluded by the system status bar, navigation bar,
 * or display cutouts on all manufacturers (Samsung One UI, Pixel, Xiaomi,
 * OnePlus, etc.).
 */
object EdgeToEdgeHelper {

    /**
     * Call in every Activity's `onCreate` **before** `setContentView`.
     * Enables proper edge-to-edge rendering and transparent system bars.
     */
    fun ComponentActivity.applyEdgeToEdge() {
        enableEdgeToEdge()
    }

    /**
     * Applies system bar insets as **padding** to the given view.
     * Use for root containers that should not draw behind system bars.
     *
     * @param view              The view to pad
     * @param top               Apply status bar inset to top padding
     * @param bottom            Apply nav bar inset to bottom padding
     * @param left              Apply left system bar inset
     * @param right             Apply right system bar inset
     * @param consumeInsets     Whether to consume the insets (stop dispatching to children)
     */
    fun applySystemBarInsets(
        view: View,
        top: Boolean = true,
        bottom: Boolean = true,
        left: Boolean = true,
        right: Boolean = true,
        consumeInsets: Boolean = false
    ) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(
                top = if (top) insets.top else v.paddingTop,
                bottom = if (bottom) insets.bottom else v.paddingBottom,
                left = if (left) insets.left else v.paddingLeft,
                right = if (right) insets.right else v.paddingRight
            )
            if (consumeInsets) WindowInsetsCompat.CONSUMED else windowInsets
        }
    }

    /**
     * Applies system bar insets as **margins** to the given view.
     * Use for views that already have a background and need spacing.
     *
     * @param view              The view to add margin to
     * @param top               Apply status bar inset to top margin
     * @param bottom            Apply nav bar inset to bottom margin
     */
    fun applySystemBarMargins(
        view: View,
        top: Boolean = false,
        bottom: Boolean = true
    ) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                if (top) topMargin = insets.top
                if (bottom) bottomMargin = insets.bottom
            }
            windowInsets
        }
    }

    /**
     * Applies both system bar and IME (keyboard) insets to the view's bottom padding.
     * The larger of the two is used (when keyboard is up, it takes precedence).
     * Also applies status bar inset to top padding.
     *
     * Use for chat screens or screens with text input at the bottom.
     */
    fun applySystemBarAndImeInsets(
        view: View,
        applyTop: Boolean = true
    ) {
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, windowInsets ->
            val systemBarsInsets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val bottomInset = maxOf(imeInsets.bottom, systemBarsInsets.bottom)

            v.updatePadding(
                top = if (applyTop) systemBarsInsets.top else v.paddingTop,
                bottom = bottomInset,
                left = systemBarsInsets.left,
                right = systemBarsInsets.right
            )
            windowInsets
        }
    }
}
