package app.subsense.stremio;

import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.util.List;

/**
 * 0.7.0: the window's own callback, unchanged, with one look at the keys going by — the viewer's BACK after he changed the
 * subtitle delay closes the delay control (Stremio for Android TV draws its menus with Compose, inside the player's one
 * view, so no view of its own appears or goes away). Every call is passed on to the callback it wraps, first or after the
 * look; the look never consumes or changes an event.
 */
final class KeyWatch implements Window.Callback {
    final Window.Callback in;
    private final Runnable onBack;

    KeyWatch(Window.Callback in, Runnable onBack) {
        this.in = in;
        this.onBack = onBack;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        try {
            if (e != null && e.getKeyCode() == KeyEvent.KEYCODE_BACK && e.getAction() == KeyEvent.ACTION_UP) onBack.run();
        } catch (Throwable ignored) {
            // a look only: the key goes on whatever happens here
        }
        return in.dispatchKeyEvent(e);
    }

    @Override public boolean dispatchKeyShortcutEvent(KeyEvent e) { return in.dispatchKeyShortcutEvent(e); }
    @Override public boolean dispatchTouchEvent(MotionEvent e) { return in.dispatchTouchEvent(e); }
    @Override public boolean dispatchTrackballEvent(MotionEvent e) { return in.dispatchTrackballEvent(e); }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) { return in.dispatchGenericMotionEvent(e); }
    @Override public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent e) { return in.dispatchPopulateAccessibilityEvent(e); }
    @Override public View onCreatePanelView(int featureId) { return in.onCreatePanelView(featureId); }
    @Override public boolean onCreatePanelMenu(int featureId, Menu menu) { return in.onCreatePanelMenu(featureId, menu); }
    @Override public boolean onPreparePanel(int featureId, View view, Menu menu) { return in.onPreparePanel(featureId, view, menu); }
    @Override public boolean onMenuOpened(int featureId, Menu menu) { return in.onMenuOpened(featureId, menu); }
    @Override public boolean onMenuItemSelected(int featureId, MenuItem item) { return in.onMenuItemSelected(featureId, item); }
    @Override public void onWindowAttributesChanged(WindowManager.LayoutParams attrs) { in.onWindowAttributesChanged(attrs); }
    @Override public void onContentChanged() { in.onContentChanged(); }
    @Override public void onWindowFocusChanged(boolean hasFocus) { in.onWindowFocusChanged(hasFocus); }
    @Override public void onAttachedToWindow() { in.onAttachedToWindow(); }
    @Override public void onDetachedFromWindow() { in.onDetachedFromWindow(); }
    @Override public void onPanelClosed(int featureId, Menu menu) { in.onPanelClosed(featureId, menu); }
    @Override public boolean onSearchRequested() { return in.onSearchRequested(); }
    @Override public boolean onSearchRequested(SearchEvent e) { return in.onSearchRequested(e); }
    @Override public ActionMode onWindowStartingActionMode(ActionMode.Callback cb) { return in.onWindowStartingActionMode(cb); }
    @Override public ActionMode onWindowStartingActionMode(ActionMode.Callback cb, int type) { return in.onWindowStartingActionMode(cb, type); }
    @Override public void onActionModeStarted(ActionMode mode) { in.onActionModeStarted(mode); }
    @Override public void onActionModeFinished(ActionMode mode) { in.onActionModeFinished(mode); }
    @Override public void onProvideKeyboardShortcuts(List<KeyboardShortcutGroup> data, Menu menu, int deviceId) { in.onProvideKeyboardShortcuts(data, menu, deviceId); }
    @Override public void onPointerCaptureChanged(boolean hasCapture) { in.onPointerCaptureChanged(hasCapture); }
}
