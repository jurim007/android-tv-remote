package com.jurim007.androidtvremote.ui

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * Makes the enclosing dialog shrink above the keyboard instead of sliding up behind it.
 *
 * When Android slides a dialog window to keep a text field visible, the cursor's drag handle is
 * drawn where the field was before the slide, away from the cursor. Resizing keeps them together.
 * Call this inside the content of any dialog with a text field.
 */
@Composable
internal fun ResizeDialogForKeyboard() {
    val view = LocalView.current
    SideEffect {
        @Suppress("DEPRECATION") // Still honored while the dialog fits system windows, as these do.
        (view.parent as? DialogWindowProvider)?.window
            ?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
}
