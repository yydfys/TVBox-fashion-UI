package com.github.tvbox.osc.util;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 轻量文件日志（给没有 adb 的手机/电视排查用）。
 * 落盘路径：Android/data/<包名>/files/logs/app.log（卸载应用才会清）。
 * 同时仍写 logcat（tag = ZMovie）。
 */
public class ZLog {

    private static final String TAG = "ZMovie";
    private static final Object LOCK = new Object();
    private static final long MAX_SIZE = 2 * 1024 * 1024;
    private static Context sCtx;

    public static void init(Context ctx) {
        if (ctx != null) sCtx = ctx.getApplicationContext();
    }

    public static void i(String msg) {
        write(Log.INFO, "I", msg);
    }

    public static void w(String msg) {
        write(Log.WARN, "W", msg);
    }

    public static void e(String msg, Throwable th) {
        StringBuilder sb = new StringBuilder(msg == null ? "" : msg);
        if (th != null) {
            sb.append(" | ").append(th.getClass().getName()).append(": ").append(th.getMessage());
            StackTraceElement[] st = th.getStackTrace();
            int n = Math.min(st.length, 12);
            for (int i = 0; i < n; i++) sb.append("\n    at ").append(st[i].toString());
        }
        write(Log.ERROR, "E", sb.toString());
    }

    public static File logFile() {
        try {
            Context c = sCtx;
            if (c == null) return null;
            File dir = c.getExternalFilesDir("logs");
            if (dir == null) return null;
            if (!dir.exists() && !dir.mkdirs()) return null;
            return new File(dir, "app.log");
        } catch (Throwable th) {
            return null;
        }
    }

    private static void write(int level, String lv, String msg) {
        Log.println(level, TAG, msg);
        synchronized (LOCK) {
            File f = logFile();
            if (f == null) return;
            try {
                if (f.exists() && f.length() > MAX_SIZE) f.delete();
                FileOutputStream fos = new FileOutputStream(f, true);
                String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
                        + " " + lv + " " + msg + "\n";
                fos.write(line.getBytes("UTF-8"));
                fos.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
