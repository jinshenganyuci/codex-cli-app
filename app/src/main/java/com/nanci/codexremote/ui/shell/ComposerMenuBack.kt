package com.nanci.codexremote.ui.shell

import android.os.Build
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.compose.BackHandler
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalView

/** Non-focusable menus need their own Back owner; keep Back from dismissing the IME first. */
@Composable
internal fun ComposerMenuBackHandler(enabled: Boolean,onBack: () -> Unit) {
    val currentBack by rememberUpdatedState(onBack)
    BackHandler(enabled=enabled) { currentBack() }
    if(Build.VERSION.SDK_INT>=33) OverlayMenuBack(enabled) { currentBack() }
}

@RequiresApi(33)
@Composable
private fun OverlayMenuBack(enabled: Boolean,onBack: () -> Unit) {
    val view=LocalView.current
    val currentBack by rememberUpdatedState(onBack)
    DisposableEffect(view,enabled) {
        val dispatcher=if(enabled) view.findOnBackInvokedDispatcher() else null
        val callback=OnBackInvokedCallback { currentBack() }
        dispatcher?.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY,callback)
        onDispose { dispatcher?.unregisterOnBackInvokedCallback(callback) }
    }
}
