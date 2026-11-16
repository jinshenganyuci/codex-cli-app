package com.nanci.codexremote.ui.shell

import android.content.res.Configuration
import android.util.Log
import android.view.ViewTreeObserver
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imeAnimationSource
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nanci.codexremote.BuildConfig
import kotlinx.coroutines.flow.collect

/** Geometry follows IME insets. Window/focus epochs only protect delayed focus cleanup. */
@Stable
internal class ComposerFocusSession {
    var focused by mutableStateOf(false)
        private set
    var focusEpoch by mutableIntStateOf(0)
        private set
    var windowEpoch by mutableIntStateOf(0)
        private set
    var interactionEpoch by mutableIntStateOf(0)
        private set
    var active by mutableStateOf(false)
        private set

    fun onFocusChanged(value: Boolean): Boolean {
        if (focused == value) return false
        focused = value
        focusEpoch++
        trace("focus=$value")
        return value
    }

    fun onWindowState(value: Boolean) {
        if (active == value) return
        active = value
        windowEpoch++
        trace("window=$value")
    }

    fun onEditorTouch() {
        // A touch during IME dismissal must invalidate that dismissal's pending cleanup,
        // even if Compose has not yet delivered an actual focus transition.
        interactionEpoch++
        trace("editorTouch")
    }

    fun onFocusRequest() {
        interactionEpoch++
        trace("focusRequest")
    }

    fun matches(focus: Int, window: Int, interaction: Int): Boolean =
        active && focused && focusEpoch == focus && windowEpoch == window && interactionEpoch == interaction

    fun trace(event: String) {
        if (BuildConfig.DEBUG) Log.d("ComposerIme", "$event focus=$focusEpoch window=$windowEpoch touch=$interactionEpoch active=$active focused=$focused")
    }
}

internal data class ComposerImeState(
    val widthExpanded: Boolean,
    val layoutExpanded: Boolean,
    val focusSession: ComposerFocusSession,
)

internal data class ComposerImeSnapshot(
    val current: Int,
    val source: Int,
    val target: Int,
    val visible: Boolean,
) {
    val animating: Boolean get() = source != target
    val openingOrOpen: Boolean get() = if (animating) target > 0 else current > 0
    val closingToHidden: Boolean get() = animating && source > 0 && target <= 0
    val geometryHidden: Boolean get() = !animating && current <= 0 && target <= 0
    val settledHidden: Boolean get() = !animating && !visible && current <= 0 && target <= 0
}

/**
 * Per-focus, per-foreground observation. The initial snapshot is deliberately untrusted:
 * resumed Android windows can briefly publish the previous keyboard's positive insets.
 */
internal class ComposerImeCloseGuard {
    private var previous: ComposerImeSnapshot? = null
    private var interaction = 0
    private var openEvidenceAllowed = false
    private var observedOpen = false
    private var pendingClose = false

    fun observe(snapshot: ComposerImeSnapshot, interactionEpoch: Int): String? {
        val before = previous
        previous = snapshot
        if (before == null) {
            interaction = interactionEpoch
            openEvidenceAllowed = snapshot.geometryHidden
            return "baseline"
        }
        if (interaction != interactionEpoch) {
            interaction = interactionEpoch
            // Retain genuine open evidence when tapping an already open editor, but never
            // carry a closing request across a fresh touch during that closing animation.
            if (pendingClose || snapshot.closingToHidden || before.closingToHidden || !snapshot.openingOrOpen) {
                observedOpen = false
                openEvidenceAllowed = snapshot.geometryHidden
            }
            pendingClose = false
            return "touch-reset"
        }
        // A resumed positive baseline can be followed by several stale positive frames.
        // Positive height alone never qualifies: first see hidden geometry or a new opening
        // target/animation from zero. A reversal from an observed closing target also qualifies.
        val newOpening = snapshot.target > 0 && (before.target <= 0 ||
            (snapshot.source <= 0 && (before.source != snapshot.source || before.target != snapshot.target)))
        if (snapshot.geometryHidden || newOpening) openEvidenceAllowed = true
        if (snapshot.openingOrOpen && !snapshot.closingToHidden && openEvidenceAllowed) {
            val firstOpen = !observedOpen
            observedOpen = true
            if (pendingClose) {
                pendingClose = false
                return "opening-cancel"
            }
            if (firstOpen) return "opening-observed"
        }
        if (snapshot.closingToHidden && !before.closingToHidden && observedOpen) {
            pendingClose = true
            return "closing-armed"
        }
        if (pendingClose && snapshot.settledHidden) {
            pendingClose = false
            observedOpen = false
            return "clear"
        }
        return null
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun rememberComposerImeState(): ComposerImeState {
    val density = LocalDensity.current
    val view = LocalView.current
    val owner = LocalLifecycleOwner.current
    val focusManager = LocalFocusManager.current
    val configuration = LocalConfiguration.current
    val session = remember { ComposerFocusSession() }
    val snapshot = ComposerImeSnapshot(
        WindowInsets.ime.getBottom(density),
        WindowInsets.imeAnimationSource.getBottom(density),
        WindowInsets.imeAnimationTarget.getBottom(density),
        WindowInsets.isImeVisible,
    )
    val latestSnapshot = rememberUpdatedState(snapshot)
    DisposableEffect(owner, view, session) {
        var resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        var windowFocused = view.hasWindowFocus()
        fun update() { session.onWindowState(resumed && windowFocused) }
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> resumed = false
                else -> Unit
            }
            update()
        }
        val windowObserver = ViewTreeObserver.OnWindowFocusChangeListener {
            windowFocused = it
            update()
        }
        val tree = view.viewTreeObserver
        owner.lifecycle.addObserver(lifecycleObserver)
        tree.addOnWindowFocusChangeListener(windowObserver)
        update()
        onDispose {
            owner.lifecycle.removeObserver(lifecycleObserver)
            if (tree.isAlive) tree.removeOnWindowFocusChangeListener(windowObserver)
            else view.viewTreeObserver.removeOnWindowFocusChangeListener(windowObserver)
            session.onWindowState(false)
        }
    }

    val focusEpoch = session.focusEpoch
    val windowEpoch = session.windowEpoch
    LaunchedEffect(session, session.focused, session.active, focusEpoch, windowEpoch) {
        if (!session.active || !session.focused) return@LaunchedEffect
        val guard = ComposerImeCloseGuard()
        snapshotFlow { latestSnapshot.value to session.interactionEpoch }.collect { (insets, touchEpoch) ->
            val event = guard.observe(insets, touchEpoch)
            if (event != null) session.trace("$event current=${insets.current} source=${insets.source} target=${insets.target} visible=${insets.visible}")
            if (event == "clear" && session.matches(focusEpoch, windowEpoch, touchEpoch)) {
                // The APK cleanup also waits for animation/visibility to settle. Epochs are
                // our local recovery guard; they are not claimed as a decompiled APK API.
                focusManager.clearFocus()
            }
        }
    }
    val hardwareKeyboard = configuration.keyboard == Configuration.KEYBOARD_QWERTY &&
        configuration.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO
    return ComposerImeState(
        widthExpanded = snapshot.openingOrOpen,
        layoutExpanded = session.focused && (snapshot.openingOrOpen || hardwareKeyboard),
        focusSession = session,
    )
}

/** Observe this editor's own down events without consuming or replacing text-field gestures. */
internal fun Modifier.observeComposerEditorTouch(session: ComposerFocusSession, enabled: Boolean): Modifier =
    pointerInput(session, enabled) {
        if (!enabled) return@pointerInput
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.any { it.pressed && !it.previousPressed }) session.onEditorTouch()
            }
        }
    }
