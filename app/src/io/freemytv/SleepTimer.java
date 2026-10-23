package io.freemytv;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Settings > Sleep Timer: an alarm, then the remote's Sleep key through the TV's own ADB (the one the switch uses):
 * Android lets no other app put the TV to sleep.
 */
final class SleepTimer {
    private SleepTimer() {}

    static final String ACTION = HomeMode.PKG + ".SLEEP";
    private static final int STEP = 5, MOST = 240;
    /** When the TV was last turned off, in uptimeMillis (dumpsys power). Leaving the screensaver isn't waking up. */
    private static final Pattern LAST_SLEEP = Pattern.compile("mLastSleepTime=(\\d+)");

    private static PendingIntent alarm(Context c, long setAt, int flags) {
        Intent i = new Intent(ACTION).setClass(c, HomeReceiver.class).putExtra("setAt", setAt);
        return PendingIntent.getBroadcast(c, 0, i, flags | PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Minutes left, rounded up; 0 when off. A one-shot alarm is gone once it went off or the TV restarted. */
    static int minutesLeft(Context c) {
        if (alarm(c, 0, PendingIntent.FLAG_NO_CREATE) == null) return 0;
        long left = Prefs.sleepAt() - System.currentTimeMillis();
        return left <= 0 ? 0 : (int) ((left + 59_999) / 60_000);
    }

    /**
     * OK and → go to the next multiple of 5 minutes after what's left (42 min left → 45), ← to the one before (→ 40);
     * past 4 hours or below 5 minutes it's off, and from off ← starts at 4 hours.
     */
    static void step(Context c, int dir) {
        int left = minutesLeft(c);
        int next = dir > 0 ? (left / STEP + 1) * STEP : left == 0 ? MOST : (left - 1) / STEP * STEP;
        set(c, next > MOST ? 0 : next);
    }

    /** Original mode: no alarms. */
    static void stop(Context c) {
        set(c, 0);
    }

    /** Starts the timer for `minutes` from now; 0 stops it. */
    static void set(Context c, int minutes) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        PendingIntent old = alarm(c, 0, PendingIntent.FLAG_NO_CREATE);
        if (old != null) {
            am.cancel(old);
            old.cancel();
        }
        Prefs.setSleepAt(minutes == 0 ? 0 : System.currentTimeMillis() + minutes * 60_000L);
        if (minutes == 0) {
            SleepOverlay.update(c);
            return;
        }
        // A window, not an exact alarm (those need a permission from Android 12): a minute late is fine.
        am.setWindow(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + minutes * 60_000L, 60_000,
                alarm(c, SystemClock.uptimeMillis(), 0));
        AppLog.w("Sleep timer: " + minutes + " min");
        SleepOverlay.update(c);
    }

    /** The alarm went off (main process): sleep, unless the TV is off, or was turned off since (and on again). */
    static void fire(Context c, long setAt) {
        if (!c.getSystemService(PowerManager.class).isInteractive()) return;
        try (Adb adb = Adb.connect(c, 0, null)) {
            Matcher m = LAST_SLEEP.matcher(adb.shell("dumpsys power"));
            if (m.find() && Long.parseLong(m.group(1)) > setAt) {
                AppLog.w("Sleep timer: the TV was turned off since, left on");
                return;
            }
            adb.shell("input keyevent KEYCODE_SLEEP");
            AppLog.w("Sleep timer: TV off");
        } catch (IOException e) {
            AppLog.w("Sleep timer: " + e.getMessage());
        }
    }
}
