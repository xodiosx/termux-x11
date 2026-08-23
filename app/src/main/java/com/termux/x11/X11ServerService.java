package com.termux.x11;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Looper;
import android.system.Os;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.lang.reflect.Method;

public final class X11ServerService extends Service {
    private static final String TAG = "X11Server";
    private static final String NOTIF_CH = "xodos2_x11_cmd";
    private static final int NOTIF_ID = 0x5e11_0001;
    private static final Object lock = new Object();
    private static boolean cmdThreadRunning;

    @Override
    public void onCreate() {
        super.onCreate();
        startAsForeground();
    }

    private void startAsForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                NOTIF_CH, "X11 display", NotificationManager.IMPORTANCE_LOW
            );
            ch.setDescription("In-process Xorg for X11 (Lorie client is in the main app)");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(ch);
            }
        }
        Notification n = new NotificationCompat.Builder(this, NOTIF_CH)
            .setContentTitle(getString(R.string.app_name) + " DISPLAY — X11")
            .setContentText("xServer (Termux-x11 is running)")
            .setSmallIcon(R.drawable.ic_x11_icon)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
            } else {
                startForeground(NOTIF_ID, n);
            }
        } catch (Exception e) {
            Log.w(TAG, "startForeground; retry without FGS type", e);
            startForeground(NOTIF_ID, n);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startAsForeground();
        startCmdEntryInBackground(intent);
        return START_STICKY;
    }

    private void startCmdEntryInBackground(final Intent intent) {
        final Application app = getApplication();
        synchronized (lock) {
            if (cmdThreadRunning) return;
            cmdThreadRunning = true;
        }
        new Thread(() -> {
            try {
                // Set environment variables from intent extras (fallbacks provided)
                String tmpdir = intent != null ? intent.getStringExtra("tmpdir") : null;
                if (tmpdir == null || tmpdir.isEmpty()) {
                    tmpdir = new File(getFilesDir(), "usr/tmp").getAbsolutePath();
                }
                File tmpDirFile = new File(tmpdir);
                if (!tmpDirFile.exists()) tmpDirFile.mkdirs();
                Os.setenv("TMPDIR", tmpdir, true);

                String xkb = intent != null ? intent.getStringExtra("xkb") : null;
                if (xkb != null && !xkb.isEmpty()) {
                    Os.setenv("XKB_CONFIG_ROOT", xkb, true);
                }

                Os.setenv("TERMUX_X11_DEBUG", "1", true);
                Os.setenv("TERMUX_X11_OVERRIDE_PACKAGE", getPackageName(), true);

                // Prepare Looper for this thread
                Looper.prepare();

                // Load CmdEntryPoint class (triggers static initializer, loads native library)
                Class<?> clazz = Class.forName("com.termux.x11.CmdEntryPoint");

                // Use Unsafe to allocate instance without calling constructor (avoids broadcast)
                Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
                java.lang.reflect.Field f = unsafeClass.getDeclaredField("theUnsafe");
                f.setAccessible(true);
                Object unsafe = f.get(null);
                Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
                Object instance = allocate.invoke(unsafe, clazz);

                // Build X server arguments from intent (default to ":0")
                String[] serverArgs;
                if (intent != null && intent.hasExtra("serverArgs")) {
                    String[] passedArgs = intent.getStringArrayExtra("serverArgs");
                    serverArgs = passedArgs != null ? passedArgs : new String[]{":0"};
                } else {
                    serverArgs = new String[]{":0"};
                }

                // Call native start(String[] args)
                Method start = clazz.getMethod("start", String[].class);
                boolean ok = (Boolean) start.invoke(instance, (Object) serverArgs);
                if (!ok) {
                    Log.e(TAG, "start() returned false");
                    return;
                }

                // Call native listenForConnections() on a new thread
                Method listen = clazz.getDeclaredMethod("listenForConnections");
                listen.setAccessible(true);
                Thread listenThread = new Thread(() -> {
                    try {
                        listen.invoke(instance);
                    } catch (Exception e) {
                        Log.e(TAG, "listenForConnections failed", e);
                    }
                }, "X11-listen");
                listenThread.start();

                // Enter loop to keep the service thread alive
                Looper.loop();
            } catch (Throwable t) {
                Log.e(TAG, "CmdEntryPoint start", t);
                synchronized (lock) {
                    cmdThreadRunning = false;
                }
            }
        }, "xodos2-CmdEntryPoint-:x11").start();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}