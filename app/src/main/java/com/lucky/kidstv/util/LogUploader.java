package com.lucky.kidstv.util;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Lucky TV 七牛云日志上报
 * - 崩溃自动上报（区分设备/用户/环境）
 * - 手动上报错误信息
 * - 日志上传到七牛 Kodo: lucky-tv/{deviceId}/{yyyyMMdd_HHmmss}.log
 * Hermes 可通过七牛 API 拉取云端日志 debug
 *
 * 崩溃上报可靠性设计（2026-08-01）：
 * 1. 崩溃瞬间：先写内部存储 crash_logs/（毫秒级必成功）再尽力同步上传
 * 2. 启动补传：init 时 flushPendingLogs() 把未上传的本地日志补传，成功即删
 * 3. 内部存储（getFilesDir）无需任何存储权限，Android 10+ scoped storage 下也可靠
 */
public class LogUploader {

    private static final String TAG = "LuckyTVLog";
    private static final String UPLOAD_HOST = "https://upload-z0.qiniup.com/"; // 华东
    private static final String PREF = "lucky_tv_log";
    private static final String KEY_DEVICE_ID = "device_id";
    private static final int LOG_CAPTURE_LINES = 300;

    private static Context sContext;
    private static String sDeviceId;
    private static String sAk;
    private static String sSk;
    private static String sBucket;
    private static Thread.UncaughtExceptionHandler sDefaultHandler;

    /** 在 Application.onCreate 中调用（七牛配置由 App 层从 BuildConfig 传入） */
    public static void init(Context context, String ak, String sk, String bucket) {
        sContext = context.getApplicationContext();
        sAk = ak;
        sSk = sk;
        sBucket = bucket;
        sDeviceId = getDeviceId();
        // 启动补传：上次崩溃若没传出去（进程被秒杀），这里补传本地日志（子线程，避免主线程网络限制）
        new Thread(new Runnable() {
            @Override
            public void run() {
                flushPendingLogs();
            }
        }).start();
        // 崩溃捕获：先落盘（不丢）→ 尽力同步上传 → 交给系统默认处理
        sDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                String stack = Log.getStackTraceString(throwable);
                Log.e(TAG, "CRASH: " + stack);
                StringBuilder sb = new StringBuilder();
                sb.append("=== LUCKY TV CRASH REPORT ===\n");
                sb.append(buildHeader());
                sb.append("\n--- Crash Stack ---\n").append(stack);
                sb.append("\n\n--- Recent Logcat ---\n").append(captureLogcat());
                String report = sb.toString();
                // 兜底1：毫秒级写内部存储，崩溃信息绝不丢失（即使进程被秒杀）
                writeLocalLog("CRASH", report);
                // 兜底2：尽力同步上传（进程可能很快被杀，能传就传；传不出去下次启动补传）
                uploadSync(report);
                if (sDefaultHandler != null) {
                    sDefaultHandler.uncaughtException(thread, throwable);
                } else {
                    android.os.Process.killProcess(android.os.Process.myPid());
                }
            }
        });
        Log.i(TAG, "LogUploader initialized. deviceId=" + sDeviceId + " qiniu=" + (qiniuEnabled() ? "enabled" : "disabled"));
    }

    /** 手动上报一条错误/日志 */
    public static void report(String tag, String message) {
        if (!qiniuEnabled()) return;
        StringBuilder sb = new StringBuilder();
        sb.append("=== LUCKY TV LOG REPORT ===\n");
        sb.append(buildHeader());
        sb.append("\n--- ").append(tag).append(" ---\n").append(message);
        sb.append("\n\n--- Recent Logcat ---\n").append(captureLogcat());
        upload(sb.toString());
    }

    private static boolean qiniuEnabled() {
        return sAk != null && !sAk.isEmpty()
                && sSk != null && !sSk.isEmpty()
                && sBucket != null && !sBucket.isEmpty();
    }

    private static String buildHeader() {
        StringBuilder sb = new StringBuilder();
        sb.append("app: Lucky TV\n");
        sb.append("version: ").append(getVersionName()).append(" (").append(getVersionCode()).append(")\n");
        sb.append("deviceId: ").append(sDeviceId).append("\n");
        sb.append("device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        sb.append("android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("time: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date())).append("\n");
        return sb.toString();
    }

    private static String getVersionName() {
        try {
            PackageInfo pi = sContext.getPackageManager().getPackageInfo(sContext.getPackageName(), 0);
            return pi.versionName;
        } catch (Exception e) {
            return "?";
        }
    }

    private static int getVersionCode() {
        try {
            PackageInfo pi = sContext.getPackageManager().getPackageInfo(sContext.getPackageName(), 0);
            return pi.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String getDeviceId() {
        android.content.SharedPreferences sp = sContext.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String id = sp.getString(KEY_DEVICE_ID, "");
        if (id.isEmpty()) {
            id = "tv-" + UUID.randomUUID().toString().substring(0, 8);
            sp.edit().putString(KEY_DEVICE_ID, id).apply();
        }
        return id;
    }

    private static String captureLogcat() {
        try {
            Process process = Runtime.getRuntime().exec(new String[]{
                    "logcat", "-d", "-t", String.valueOf(LOG_CAPTURE_LINES), "-v", "time"
            });
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null && count < LOG_CAPTURE_LINES) {
                sb.append(line).append('\n');
                count++;
            }
            process.destroy();
            return sb.toString();
        } catch (Exception e) {
            return "logcat capture failed: " + e.getMessage();
        }
    }

    /** 异步上传（普通日志上报） */
    private static void upload(final String content) {
        if (!qiniuEnabled()) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                doUpload(content);
            }
        }).start();
    }

    /** 同步上传（崩溃上报用：进程即将被杀，尽力而为） */
    private static void uploadSync(final String content) {
        if (!qiniuEnabled()) return;
        doUpload(content);
    }

    private static void doUpload(final String content) {
        try {
            String key = "lucky-tv/" + sDeviceId + "/" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(new Date()) + ".log";
            String token = generateUploadToken(sAk, sSk, sBucket, key);
            byte[] body = content.getBytes("UTF-8");
            String boundary = "----LuckyTVBoundary" + System.currentTimeMillis();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bos.write(("--" + boundary + "\r\n").getBytes());
            bos.write(("Content-Disposition: form-data; name=\"token\"\r\n\r\n" + token + "\r\n").getBytes());
            bos.write(("--" + boundary + "\r\n").getBytes());
            bos.write(("Content-Disposition: form-data; name=\"key\"\r\n\r\n" + key + "\r\n").getBytes());
            bos.write(("--" + boundary + "\r\n").getBytes());
            bos.write(("Content-Disposition: form-data; name=\"file\"; filename=\"log.txt\"\r\n").getBytes());
            bos.write(("Content-Type: text/plain\r\n\r\n").getBytes());
            bos.write(body);
            bos.write(("\r\n--" + boundary + "--\r\n").getBytes());

            HttpURLConnection conn = (HttpURLConnection) new URL(UPLOAD_HOST).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            DataOutputStream dos = new DataOutputStream(conn.getOutputStream());
            dos.write(bos.toByteArray());
            dos.flush();
            dos.close();
            int code = conn.getResponseCode();
            if (code == 200) {
                Log.i(TAG, "Qiniu upload OK: " + key);
            } else {
                Log.w(TAG, "Qiniu upload failed: HTTP " + code);
            }
            conn.disconnect();
        } catch (Exception e) {
            Log.w(TAG, "Qiniu upload exception: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    /** 七牛上传凭证生成（AK + SK + bucket + key），与录音卡 Flutter 端同款签名 */
    public static String generateUploadToken(String ak, String sk, String bucket, String key) {
        try {
            String scope = key != null && !key.isEmpty() ? bucket + ":" + key : bucket;
            long deadline = System.currentTimeMillis() / 1000 + 3600;
            String policy = "{\"scope\":\"" + scope + "\",\"deadline\":" + deadline + "}";
            String encodedPolicy = base64Url(policy.getBytes("UTF-8"));
            String sign = hmacSha1Base64(sk, encodedPolicy);
            return ak + ":" + sign + ":" + encodedPolicy;
        } catch (Exception e) {
            Log.e(TAG, "token gen failed: " + e.getMessage());
            return "";
        }
    }

    private static String base64Url(byte[] data) {
        String b64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
        return b64.replace('+', '-').replace('/', '_');
    }

    private static String hmacSha1Base64(String sk, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(sk.getBytes("UTF-8"), "HmacSHA1"));
        return base64Url(mac.doFinal(data.getBytes("UTF-8")));
    }

    /** 本地兜底：写日志文件到 App 私有目录（无需存储权限，崩溃时必能写入） */
    public static void writeLocalLog(String tag, String message) {
        try {
            File dir = new File(sContext.getFilesDir(), "LuckyTV/logs");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "lucky_" + new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(new Date()) + ".log");
            FileOutputStream fos = new FileOutputStream(f, true);
            OutputStreamWriter writer = new OutputStreamWriter(fos, "UTF-8");
            writer.write("[" + new SimpleDateFormat("HH:mm:ss", Locale.CHINA).format(new Date()) + "] [" + tag + "] " + message + "\n");
            writer.flush();
            writer.close();
        } catch (Exception ignored) {
        }
    }

    /** 启动补传：把本地待上传日志批量发到七牛，成功后删除本地文件 */
    private static void flushPendingLogs() {
        if (!qiniuEnabled()) return;
        try {
            File dir = new File(sContext.getFilesDir(), "LuckyTV/logs");
            if (!dir.exists()) return;
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                try {
                    StringBuilder content = new StringBuilder();
                    BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        content.append(line).append('\n');
                    }
                    reader.close();
                    if (content.length() > 0) {
                        String key = "lucky-tv/" + sDeviceId + "/" + f.getName().replace(".log", "") + ".log";
                        String token = generateUploadToken(sAk, sSk, sBucket, key);
                        byte[] body = content.toString().getBytes("UTF-8");
                        String boundary = "----LuckyTVBoundary" + System.currentTimeMillis();
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        bos.write(("--" + boundary + "\r\n").getBytes());
                        bos.write(("Content-Disposition: form-data; name=\"token\"\r\n\r\n" + token + "\r\n").getBytes());
                        bos.write(("--" + boundary + "\r\n").getBytes());
                        bos.write(("Content-Disposition: form-data; name=\"key\"\r\n\r\n" + key + "\r\n").getBytes());
                        bos.write(("--" + boundary + "\r\n").getBytes());
                        bos.write(("Content-Disposition: form-data; name=\"file\"; filename=\"log.txt\"\r\n").getBytes());
                        bos.write(("Content-Type: text/plain\r\n\r\n").getBytes());
                        bos.write(body);
                        bos.write(("\r\n--" + boundary + "--\r\n").getBytes());

                        HttpURLConnection conn = (HttpURLConnection) new URL(UPLOAD_HOST).openConnection();
                        conn.setConnectTimeout(10000);
                        conn.setReadTimeout(10000);
                        conn.setRequestMethod("POST");
                        conn.setDoOutput(true);
                        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                        DataOutputStream dos = new DataOutputStream(conn.getOutputStream());
                        dos.write(bos.toByteArray());
                        dos.flush();
                        dos.close();
                        int code = conn.getResponseCode();
                        conn.disconnect();
                        if (code == 200) {
                            Log.i(TAG, "Qiniu flush OK: " + key);
                            f.delete();
                        } else {
                            Log.w(TAG, "Qiniu flush failed: HTTP " + code + " " + f.getName());
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Qiniu flush exception: " + e.getMessage() + " " + f.getName());
                }
            }
        } catch (Exception ignored) {
        }
    }
}
