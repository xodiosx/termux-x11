package com.termux.x11;


import android.content.Context;
import android.os.Build;
import android.os.Environment;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;

public class LogcatLogger {

    private static Thread loggerThread;
    private static boolean isRunning = false;

    public static void start(Context context, String filterTag) {

        if (isRunning) return;  // prevent multiple threads
        isRunning = true;

        loggerThread = new Thread(() -> {
            try {
                // Determine directory
                File dir;
                if (Build.VERSION.SDK_INT >= 29) {
                    dir = new File(context.getExternalFilesDir(null), "logs");
                } else {
                    dir = new File(Environment.getExternalStorageDirectory(), "xodos/logs");
                }

                if (!dir.exists()) dir.mkdirs();

                File logFile = new File(dir, "app.log");
                FileWriter writer = new FileWriter(logFile, true);

                // Clear old logs (optional)
                Runtime.getRuntime().exec("logcat -c");

                // Start reading logs
                Process process = Runtime.getRuntime().exec("logcat");

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream())
                );

                String line;
                while (isRunning && (line = reader.readLine()) != null) {

                    // Tag filtering
                    if (filterTag == null || line.contains(filterTag)) {
                        writer.write(line + "\n");
                        writer.flush();
                    }
                }

                writer.close();

            } catch (Exception e) {
                e.printStackTrace();
            }
        });

        loggerThread.start();
    }

    public static void stop() {
        isRunning = false;
        if (loggerThread != null) {
            loggerThread.interrupt();
            loggerThread = null;
        }
    }
}