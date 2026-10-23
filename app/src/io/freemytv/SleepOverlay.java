package io.freemytv;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Settings > Sleep Timer > Show Time Left: the sleep timer's minutes left over anything on screen, where picked
 * (Position), while the timer runs, in the home screen's light or dark glass. A foreground service, so Android keeps it
 * while another app plays.
 */
public class SleepOverlay extends Service {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView view;
    private WindowManager.LayoutParams layout;
    private BitmapDrawable moon;
    private GradientDrawable pill;

    /** Settings > Sleep Timer > Position: the top center first, then around the screen. */
    private static final int[] GRAVITY = {Gravity.TOP | Gravity.CENTER_HORIZONTAL, Gravity.TOP | Gravity.RIGHT,
            Gravity.BOTTOM | Gravity.RIGHT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, Gravity.BOTTOM | Gravity.LEFT,
            Gravity.TOP | Gravity.LEFT};
    static final int PLACES = GRAVITY.length;

    static String placeName(int place) {
        switch (place) {
            case 1: return Ui.tr("Top right");
            case 2: return Ui.tr("Bottom right");
            case 3: return Ui.tr("Bottom center");
            case 4: return Ui.tr("Bottom left");
            case 5: return Ui.tr("Top left");
            default: return Ui.tr("Top center");
        }
    }

    /**
     * Starts or stops it to match the timer and the choice (from the :home process, where both live). Android TV has no
     * screen to allow drawing over other apps, so the first time ADB allows it; where the TV refuses, the home screen
     * shows the minutes instead (HomeView).
     */
    static void update(Context c) {
        Intent i = new Intent(c, SleepOverlay.class);
        boolean wanted = Prefs.sleepShown() && SleepTimer.minutesLeft(c) > 0;
        if (wanted && Settings.canDrawOverlays(c)) {
            c.startForegroundService(i);
        } else {
            c.stopService(i);
            if (wanted && Capabilities.overlay(c)) allow(c.getApplicationContext());
        }
    }

    private static void allow(Context c) {
        new Thread(() -> {
            try (Adb adb = Adb.connect(c, 0, null)) {
                adb.shell("appops set " + HomeMode.PKG + " SYSTEM_ALERT_WINDOW allow");
            } catch (java.io.IOException e) {
                AppLog.w("over other apps: " + e.getMessage());
            }
            boolean works = Settings.canDrawOverlays(c);
            Capabilities.prove(c, Capabilities.OVERLAY, works);
            if (works) new Handler(Looper.getMainLooper()).post(() -> update(c));
        }).start();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            int left = SleepTimer.minutesLeft(SleepOverlay.this);
            if (left == 0 || !Prefs.sleepShown()) {
                stopSelf();
                return;
            }
            view.setText(Ui.trf("Off in {n} min", "n", left));
            // The home screen's glass: white with dark ink by day, smoked with light ink at night (Settings > Theme).
            boolean night = HomeData.get(SleepOverlay.this).isNight(System.currentTimeMillis());
            int ink = night ? 0xEBFFFFFF : 0xFF1D1D1F;
            view.setTextColor(ink);
            moon.setTint(ink);
            pill.setColor(night ? 0xCC1C1C1E : 0xE6F5F5F7);
            int place = Prefs.sleepPlace() % PLACES;
            if (layout.gravity != GRAVITY[place]) {
                layout.gravity = GRAVITY[place];
                getSystemService(WindowManager.class).updateViewLayout(view, layout);
            }
            handler.postDelayed(this, 15_000);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("sleep", Ui.tr("Sleep Timer"), NotificationManager.IMPORTANCE_MIN));
        startForeground(1, new Notification.Builder(this, "sleep").setSmallIcon(R.drawable.icon)
                .setContentTitle(Ui.tr("Sleep Timer")).build());
        float k = getResources().getDisplayMetrics().widthPixels / 1920f;
        view = new TextView(this);
        view.setTypeface(Ui.regular);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, 24 * k);
        pill = new GradientDrawable();
        pill.setCornerRadius(40 * k);
        view.setBackground(pill);
        view.setPadding(Math.round(24 * k), Math.round(12 * k), Math.round(28 * k), Math.round(12 * k));
        moon = new BitmapDrawable(getResources(), Symbols.get(this, "bedtime"));
        moon.setBounds(0, 0, Math.round(26 * k), Math.round(26 * k));
        view.setCompoundDrawables(moon, null, null, null);
        view.setCompoundDrawablePadding(Math.round(12 * k));
        layout = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        layout.gravity = GRAVITY[Prefs.sleepPlace() % PLACES];
        layout.x = layout.y = Math.round(48 * k);
        getSystemService(WindowManager.class).addView(view, layout);
    }

    /** Every update (a new time, Position, Show Time Left): drawn again now. */
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        handler.removeCallbacks(tick);
        tick.run();
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        handler.removeCallbacks(tick);
        if (view != null) getSystemService(WindowManager.class).removeView(view);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
