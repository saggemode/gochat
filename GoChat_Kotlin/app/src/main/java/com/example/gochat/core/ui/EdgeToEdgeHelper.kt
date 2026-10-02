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
 * Centralized Edge-to-Edge and Window Insets helper for GoChat.
 *
 * Ensures all screens work perfectly across all Android smartphones (Samsung One UI,
 * Google Pixel, Xiaomi MIUI/HyperOS, OnePlus OxygenOS, etc.).
 *
 * Prevents UI elements (bottom buttons, floating action buttons, text fields, bottom sheets,
 * and bottom navigation) from overlapping with:
 * 1. Samsung 3-button navigation bar (Recents / Home / Back)
 * 2. System gesture navigation bars & gesture handles
 * 3. Status bar, camera cutouts / punch holes, and display notches
 * 4. Software keyboard (IME)
 */
object EdgeToEdgeHelper {

    /**
     * Call in every Activity's `onCreate` **before** `setContentView`.
     * Enables proper edge-to-edge rendering and transparent system bars.
     */
    fun ComponentActivity.applyEdgeToEdge() {
        enableEdgeToEdge()
    }
}

/**
 * Top-level extension to enable Edge-to-Edge easily from any ComponentActivity.
 */
fun ComponentActivity.applyEdgeToEdge() {
    enableEdgeToEdge()
}

/**
 * Applies system bar insets as **padding** to the given view.
 * Preserves the view's initial XML padding and adds the system bar insets.
 */
fun View.applySystemBarInsets(
    applyTop: Boolean = true,
    applyBottom: Boolean = true,
    applyLeft: Boolean = true,
    applyRight: Boolean = true
) {
    val initialPaddingLeft = paddingLeft
    val initialPaddingTop = paddingTop
    val initialPaddingRight = paddingRight
    val initialPaddingBottom = paddingBottom

    ViewCompat.setOnApplyWindowInsetsListener(this) { v, windowInsets ->
        val insets = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        v.updatePadding(
            left = if (applyLeft) initialPaddingLeft + insets.left else initialPaddingLeft,
            top = if (applyTop) initialPaddingTop + insets.top else initialPaddingTop,
            right = if (applyRight) initialPaddingRight + insets.right else initialPaddingRight,
            bottom = if (applyBottom) initialPaddingBottom + insets.bottom else initialPaddingBottom
        )
        windowInsets
    }
    ViewCompat.requestApplyInsets(this)
}

/**
 * Applies status bar and cutout top insets as **padding** to the given view (e.g. Toolbar).
 * Preserves initial padding so the toolbar content is positioned correctly below the status bar.
 */
fun View.applyStatusBarInset() {
    val initialPaddingTop = paddingTop
    val initialPaddingLeft = paddingLeft
    val initialPaddingRight = paddingRight

    ViewCompat.setOnApplyWindowInsetsListener(this) { v, windowInsets ->
        val insets = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        v.updatePadding(
            left = initialPaddingLeft + insets.left,
            top = initialPaddingTop + insets.top,
            right = initialPaddingRight + insets.right
        )
        windowInsets
    }
    ViewCompat.requestApplyInsets(this)
}

/**
 * Applies navigation bar and cutout bottom insets as **padding** to the given view.
 * Prevents bottom content or action bars from being overlapped by the Samsung navigation bar.
 */
fun View.applyNavigationBarInset() {
    val initialPaddingBottom = paddingBottom
    val initialPaddingLeft = paddingLeft
    val initialPaddingRight = paddingRight

    ViewCompat.setOnApplyWindowInsetsListener(this) { v, windowInsets ->
        val insets = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        v.updatePadding(
            left = initialPaddingLeft + insets.left,
            right = initialPaddingRight + insets.right,
            bottom = initialPaddingBottom + insets.bottom
        )
        windowInsets
    }
    ViewCompat.requestApplyInsets(this)
}

/**
 * Applies system bar insets as **margins** to the given view.
 * Use for Floating Action Buttons (FABs) or views that need margin offset
 * rather than internal padding.
 */
fun View.applySystemBarMargins(
    top: Boolean = false,
    bottom: Boolean = true,
    left: Boolean = false,
    right: Boolean = false
) {
    val lp = layoutParams as? ViewGroup.MarginLayoutParams
    val initialMarginLeft = lp?.leftMargin ?: 0
    val initialMarginTop = lp?.topMargin ?: 0
    val initialMarginRight = lp?.rightMargin ?: 0
    val initialMarginBottom = lp?.bottomMargin ?: 0

    ViewCompat.setOnApplyWindowInsetsListener(this) { v, windowInsets ->
        val insets = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        v.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            if (left) leftMargin = initialMarginLeft + insets.left
            if (top) topMargin = initialMarginTop + insets.top
            if (right) rightMargin = initialMarginRight + insets.right
            if (bottom) bottomMargin = initialMarginBottom + insets.bottom
        }
        windowInsets
    }
    ViewCompat.requestApplyInsets(this)
}

/**
 * Applies both system bar and IME (keyboard) insets to the view's bottom padding.
 * When the software keyboard is visible, it lifts the view above the keyboard.
 * When the keyboard is hidden, it cushions the view above the Samsung navigation bar.
 *
 * Ideal for login screens, chat inputs, or forms.
 */
fun View.applySystemBarAndImeInsets(
    applyTop: Boolean = true,
    applyBottom: Boolean = true
) {
    val initialPaddingLeft = paddingLeft
    val initialPaddingTop = paddingTop
    val initialPaddingRight = paddingRight
    val initialPaddingBottom = paddingBottom

    ViewCompat.setOnApplyWindowInsetsListener(this) { v, windowInsets ->
        val systemBarsInsets = windowInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        val imeInsets = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
        val bottomInset = maxOf(imeInsets.bottom, systemBarsInsets.bottom)

        v.updatePadding(
            left = initialPaddingLeft + systemBarsInsets.left,
            top = if (applyTop) initialPaddingTop + systemBarsInsets.top else initialPaddingTop,
            right = initialPaddingRight + systemBarsInsets.right,
            bottom = if (applyBottom) initialPaddingBottom + bottomInset else initialPaddingBottom
        )
        windowInsets
    }
    ViewCompat.requestApplyInsets(this)
}
