package com.hari.bbwa;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Polls the bridge in the background and raises a notification for new incoming messages.
 * An alarm restarts it every minute if the system killed it, and wakes the phone to check.
 */
public class PollService extends Service {
    private static final long INTERVAL_MS = 15000;
    private static final long ALARM_MS = 60000;
    private static final int MAX_INDIVIDUAL = 4;

    private final Object lock = new Object();
    private volatile boolean running;
    private volatile boolean pollNow;
    private Thread worker;

    @Override
    public IBinder onBind(Intent i) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Api.paired(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        scheduleAlarm(this);
        if (!running) {
            running = true;
            worker = new Thread(new Runnable() {
                public void run() { loop(); }
            }, "bbwa-poll");
            worker.start();
        } else {
            synchronized (lock) {
                pollNow = true; // the alarm (or the app opening) asks for an immediate check
                lock.notifyAll();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        synchronized (lock) { lock.notifyAll(); }
        super.onDestroy();
    }

    static void scheduleAlarm(Context c) {
        AlarmManager am = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
        am.setRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + ALARM_MS, ALARM_MS, alarmIntent(c));
    }

    /** Stop polling for good (used when unpairing). */
    static void stop(Context c) {
        ((AlarmManager) c.getSystemService(Context.ALARM_SERVICE)).cancel(alarmIntent(c));
        c.stopService(new Intent(c, PollService.class));
    }

    private static PendingIntent alarmIntent(Context c) {
        return PendingIntent.getService(c, 0, new Intent(c, PollService.class), PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private void loop() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        while (running && Api.paired(this)) {
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bbwa-poll");
            wl.acquire(20000);
            try {
                pollOnce();
                Api.sp(this).edit().putLong("lastPoll", System.currentTimeMillis()).putString("pollErr", "").commit();
            } catch (Exception e) {
                Api.sp(this).edit().putString("pollErr", Api.why(e)).commit();
            } finally {
                if (wl.isHeld()) wl.release();
            }
            synchronized (lock) {
                if (!pollNow) {
                    try {
                        lock.wait(INTERVAL_MS);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                pollNow = false;
            }
        }
        running = false;
    }

    private void pollOnce() throws Exception {
        long since = Api.sp(this).getLong("seq", -1);
        // First run (-1): just learn the current position, do not notify for old messages.
        JSONObject u = Api.updates(this, since < 0 ? Long.MAX_VALUE / 2 : since);
        long now = u.optLong("seq", since);
        if (since >= 0) {
            JSONArray a = u.optJSONArray("messages");
            int shown = 0;
            int skipped = 0;
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject m = a.optJSONObject(i);
                if (!worthNotifying(m)) continue;
                if (shown < MAX_INDIVIDUAL) {
                    notifyMessage(m);
                    shown++;
                } else {
                    skipped++;
                }
            }
            if (skipped > 0) notifySummary(skipped);
        }
        Api.sp(this).edit().putLong("seq", now).commit();
    }

    private boolean worthNotifying(JSONObject m) {
        if (m == null || m.optBoolean("fromMe")) return false;
        if (m.optBoolean("archived")) return false; // archived chats stay silent
        JSONObject meta = m.optJSONObject("meta");
        if (meta != null && meta.optBoolean("edited")) return false; // an edit is not a new message
        String chat = m.optString("chatId");
        return chat.length() > 0 && !chat.equals(ChatActivity.openChat);
    }

    private static String preview(JSONObject m) {
        String body = m.optString("body");
        if (body.length() > 0) return body;
        String t = m.optString("type");
        if (t.equals("image")) return "[Photo]";
        if (t.equals("video")) return "[Video]";
        if (t.equals("ptt")) return "[Voice message]";
        if (t.equals("audio")) return "[Audio]";
        if (t.equals("sticker")) return "[Sticker]";
        if (t.equals("document")) return "[Document]";
        return "New message";
    }

    private static String number(String id) {
        int at = id.indexOf('@');
        return at > 0 ? id.substring(0, at) : id;
    }

    private void notifyMessage(JSONObject m) {
        String chat = m.optString("chatId");
        String title = m.optString("chatName");
        if (title.length() == 0) title = number(m.optString("sender"));
        String text = preview(m);
        if (m.optBoolean("isGroup")) {
            String who = number(m.optString("sender"));
            if (who.length() > 0) text = who + ": " + text;
        }
        Intent open = new Intent(this, ChatActivity.class);
        open.putExtra("id", chat);
        open.putExtra("name", m.optString("chatName"));
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        post(chat.hashCode(), title, text, open);
    }

    private void notifySummary(int more) {
        Intent open = new Intent(this, ChatListActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        post(1, "BB Whatsapp", more + " more new messages", open);
    }

    private void post(int id, String title, String text, Intent open) {
        postNotification(this, id, title, text, open);
    }

    static void postNotification(Context c, int id, String title, String text, Intent open) {
        PendingIntent pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setTicker(title + ": " + text)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_ALL)
                .build();
        ((NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE)).notify(id, n);
    }

    /** Raise a sample notification so you can see whether this phone shows them. */
    static void testAlert(Context c) {
        Intent open = new Intent(c, ChatListActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        postNotification(c, 2, "BB Whatsapp test alert", "If you can see this, alerts work", open);
    }

    /** One line for the chat list: when the background check last succeeded, or why it failed. */
    static String status(Context c) {
        String err = Api.sp(c).getString("pollErr", "");
        long last = Api.sp(c).getLong("lastPoll", 0);
        if (last == 0) return "alerts: not checked yet";
        String when = new SimpleDateFormat("HH:mm:ss").format(new Date(last));
        return err.length() > 0 ? "alerts: " + err + " (last ok " + when + ")" : "alerts: checked " + when;
    }
}
