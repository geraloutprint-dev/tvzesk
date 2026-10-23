package io.freemytv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.TextView;

/**
 * The Free My TV home screen (Android's "Home" role in Free My TV mode). It runs in the :home process: if it crashes,
 * the safety net in the main process keeps working. On the 3rd crash within 5 min, the Watchdog gives the home screen
 * back to the TV's original one.
 */
public class HomeActivity extends Activity {
    /** Remote's Apps button (KEYCODE_ALL_APPS): open the All Apps sheet. */
    static final String EXTRA_OPEN_APPS = "io.freemytv.extra.OPEN_APPS";
    /** Remote's Menu button (KEYCODE_F9): context menu for the focused app. */
    static final String EXTRA_APP_MENU = "io.freemytv.extra.APP_MENU";

    /** The Apps key arrives as ACTION_ALL_APPS when we are the TV's home app, or as our extra from the key service. */
    private static boolean wantsApps(Intent intent) {
        return intent != null && (intent.getBooleanExtra(EXTRA_OPEN_APPS, false)
                || Intent.ACTION_ALL_APPS.equals(intent.getAction()));
    }

    private HomeView home;
    private int prefsVersion = Prefs.version;   // settings changed while we were away: start over

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        if (Watchdog.crashedTooOften(this)) {
            // Don't try to draw again: only show a notice while the main process restores the original home screen.
            HomeMode.requestRevertHome(this);
            TextView t = new TextView(this);
            t.setText(Ui.tr("Free My TV's home screen ran into a problem.\nGoing back to the TV's original home screen…"));
            t.setTextColor(Color.WHITE);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
            t.setGravity(Gravity.CENTER);
            t.setBackgroundColor(Color.rgb(7, 11, 30));
            setContentView(t);
            return;
        }
        Ui.init(this);
        home = new HomeView(this);
        setContentView(home);
        getWindow().setBackgroundDrawable(null);
        if (wantsApps(getIntent())) home.requestApps();
        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_APP_MENU, false)) home.showAppMenu();
    }

    @Override protected void onResume() {
        super.onResume();
        if (prefsVersion != Prefs.version) {
            prefsVersion = Prefs.version;
            HomeData.get(this).settingsChanged();
            recreate();   // a new language or city: draw everything again
            return;
        }
        if (home != null) home.resume();
    }

    @Override protected void onPause() {
        if (home != null) home.pause();
        super.onPause();
    }

    /** Home button pressed on the home screen: back to the entry state (focus on the first app, sheet closed). */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (home == null) return;
        if (wantsApps(intent)) home.requestApps();
        else if (intent != null && intent.getBooleanExtra(EXTRA_APP_MENU, false)) home.showAppMenu();
        else home.reset(true);
    }

    @Override protected void onDestroy() {
        if (home != null) home.destroy();
        super.onDestroy();
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_COMPLETE) Recs.get(this).trim();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (RemoteKeys.onKey(this, event)) return true;
        if (event.getKeyCode() == KeyEvent.KEYCODE_ALL_APPS) {   // without the key service
            if (event.getAction() == KeyEvent.ACTION_DOWN && home != null) home.requestApps();
            return true;
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_F9 || event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && home != null) home.showAppMenu();
            return true;
        }
        if (home != null && home.onKey(event)) return true;
        return super.dispatchKeyEvent(event);
    }

}
