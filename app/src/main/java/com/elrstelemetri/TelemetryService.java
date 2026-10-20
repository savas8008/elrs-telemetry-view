package com.elrstelemetri;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

/**
 * Foreground service that keeps the app process running while another app (e.g. DJI Fly) is
 * in front. Without it Android pauses/freezes the app in the background: the RTMP receiver stops
 * reading, DJI Fly's send buffer fills up and DJI Fly drops the stream; telemetry, alarms and
 * logging would stop too. The receivers themselves live in MainActivity; this service only keeps
 * the process in the foreground and shows the required notification.
 */
public class TelemetryService extends Service {

    public static final String ACTION_EXIT = "com.elrstelemetri.EXIT";
    private static final String CHANNEL = "gcs";
    private static final int ID = 1;

    public static void start(Context c) {
        try {
            c.startForegroundService(new Intent(c, TelemetryService.class));
        } catch (RuntimeException e) {
            // e.g. started from the background on Android 12+: the app keeps working while visible
        }
    }

    public static void stop(Context c) {
        c.stopService(new Intent(c, TelemetryService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && nm.getNotificationChannel(CHANNEL) == null) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Ground station running",
                    NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Shown while telemetry, alarms, logging and the video receiver run in the background");
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent exit = new Intent(this, MainActivity.class).setAction(ACTION_EXIT)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent exitPi = PendingIntent.getActivity(this, 1, exit,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("ELRS Telemetry is running")
                .setContentText("Telemetry, alarms, logging and video continue in the background")
                .setContentIntent(openPi)
                .addAction(new Notification.Action.Builder(null, "Exit", exitPi).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(ID, n);
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
