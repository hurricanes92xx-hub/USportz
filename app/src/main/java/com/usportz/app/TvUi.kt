package com.usportz.app

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Android TV helpers for the 10-foot/D-pad UI pass. */
object TvUi {
    fun isTelevision(context: Context): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)

    fun Modifier.dpadFocusable(focusRequester: FocusRequester? = null): Modifier =
        then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusable()

    fun Modifier.dpadClickable(onClick: () -> Unit): Modifier =
        clickable(onClick = onClick)

    val focusBorder: BorderStroke = BorderStroke(2.dp, Color(0xFF48B9FF))
}

@Composable
fun rememberTvFocusRequester(): FocusRequester = remember { FocusRequester() }
