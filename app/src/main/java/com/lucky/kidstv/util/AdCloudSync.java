package com.lucky.kidstv.util;

import android.content.Context;

import com.lucky.kidstv.BuildConfig;
import com.orhanobut.hawk.Hawk;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 广告标记云端同步（七牛 lucky-tv bucket）：
 * - pullAndMerge(): 启动/配置加载后，拉取云端 ads.json 合并进本地 Hawk（全局共享他人标记）
 * - recordLocal(): 本地手动标记保存后记录到待回传队列（Hawk 固定键）
 * - pushLocal(): 把待回传队列合并回传云端（子账号只读+上传权限，无删除；Hawk 无键枚举故用固定键队列）
 * 安全：云端地址公开读无需密钥；回传用 BuildConfig 注入的子账号 AK/SK（受限权限，反编译也摸不到主账号）。
 */
public class AdCloudSync {
    private static final String TAG = "AdCloudSync";
    private static final String PENDING_KEY = "ad_pending_push"; // {"md5":"start,end;...", ...}
    private static final ExecutorService sPool = Executors.newSingleThreadExecutor();
    private static volatile boolean sPulling = false;
    private static volatile boolean sPushing = false;

    /** 拉取云端广告标记并合并进本地 Hawk（后台线程，失败静默不影响播放） */
    public static void pullAndMerge(final Context ctx) {
        final String cloudUrl = Hawk.get(HawkConfig.AD_CLOUD_URL, "");
        if (cloudUrl.isEmpty()) return;
        if (sPulling) return;
        sPulling = true;
        sPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(cloudUrl).openConnection();
                    conn.setConnectTimeout(8000);
                    conn.setReadTimeout(8000);
                    conn.setRequestMethod("GET");
                    int code = conn.getResponseCode();
                    if (code != 200) return;
                    java.io.InputStream is = conn.getInputStream();
                    String json = new String(readAll(is), "UTF-8");
                    is.close();
                    JSONObject root = new JSONObject(json);
                    if (!root.has("segments")) return;
                    JSONObject segs = root.getJSONObject("segments");
                    int merged = 0;
                    Iterator<String> it = segs.keys();
                    while (it.hasNext()) {
                        String md5 = it.next();
                        String segStr = segs.optString(md5, "");
                        if (segStr.isEmpty()) continue;
                        String key = HawkConfig.AD_SEGMENTS_PREFIX + md5;
                        String local = Hawk.get(key, "");
                        if (local == null || local.isEmpty()) {
                            Hawk.put(key, segStr);
                            merged++;
                        } else if (!local.equals(segStr)) {
                            Hawk.put(key, mergeSegments(local, segStr));
                            merged++;
                        }
                    }
                    LOG.i(TAG, "ads pulled & merged, count=" + merged);
                } catch (Throwable th) {
                    // 拉取失败静默（不阻塞播放）
                } finally {
                    sPulling = false;
                }
            }
        });
    }

    /** 本地标记保存后调用：把该视频的新标记记录到待回传队列 */
    public static void recordLocal(String md5, String segStr) {
        try {
            Map<String, String> pending = getPending();
            String old = pending.get(md5);
            pending.put(md5, (old == null || old.isEmpty()) ? segStr : mergeSegments(old, segStr));
            savePending(pending);
        } catch (Throwable th) {
            // 忽略
        }
    }

    /** 回传待回传队列到云端（后台线程；成功后清空队列） */
    public static void pushLocal() {
        if (!Hawk.get(HawkConfig.AD_CLOUD_PUSH, true)) return;
        final String cloudUrl = Hawk.get(HawkConfig.AD_CLOUD_URL, "");
        if (cloudUrl.isEmpty()) return;
        if (sPushing) return;
        sPushing = true;
        sPool.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Map<String, String> pending = getPending();
                    if (pending.isEmpty()) return;

                    // 1. 拉取云端当前库作为基底（云端为主、本地补充）
                    JSONObject segs = new JSONObject();
                    try {
                        HttpURLConnection conn = (HttpURLConnection) new URL(cloudUrl).openConnection();
                        conn.setConnectTimeout(8000);
                        conn.setReadTimeout(8000);
                        conn.setRequestMethod("GET");
                        if (conn.getResponseCode() == 200) {
                            java.io.InputStream is = conn.getInputStream();
                            String json = new String(readAll(is), "UTF-8");
                            is.close();
                            JSONObject root = new JSONObject(json);
                            if (root.has("segments")) {
                                JSONObject remote = root.getJSONObject("segments");
                                Iterator<String> it = remote.keys();
                                while (it.hasNext()) {
                                    String md5 = it.next();
                                    segs.put(md5, remote.optString(md5, ""));
                                }
                            }
                        }
                    } catch (Throwable ignore) {
                    }
                    // 2. 合并待回传队列
                    for (Map.Entry<String, String> e : pending.entrySet()) {
                        String md5 = e.getKey();
                        if (segs.has(md5)) {
                            segs.put(md5, mergeSegments(segs.optString(md5, ""), e.getValue()));
                        } else {
                            segs.put(md5, e.getValue());
                        }
                    }

                    // 3. 上传合并后的库（覆盖式；子账号无删除权限，天然防误删）
                    JSONObject payload = new JSONObject();
                    payload.put("version", System.currentTimeMillis() / 1000);
                    payload.put("updated", new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX",
                            java.util.Locale.CHINA).format(new java.util.Date()));
                    payload.put("segments", segs);
                    byte[] body = payload.toString().getBytes("UTF-8");
                    String key = extractKey(cloudUrl);
                    String token = makeUploadToken(BuildConfig.QINIU_AK, BuildConfig.QINIU_SK,
                            BuildConfig.QINIU_BUCKET, key);

                    String boundary = "----AdCloudBoundary" + System.currentTimeMillis();
                    HttpURLConnection conn = (HttpURLConnection) new URL(
                            "https://upload-z0.qiniup.com/").openConnection();
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    conn.setDoOutput(true);
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                    OutputStream os = conn.getOutputStream();
                    os.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"token\"\r\n\r\n"
                            + token + "\r\n").getBytes("UTF-8"));
                    os.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"key\"\r\n\r\n"
                            + key + "\r\n").getBytes("UTF-8"));
                    os.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"ads.json\"\r\n"
                            + "Content-Type: application/json\r\n\r\n").getBytes("UTF-8"));
                    os.write(body);
                    os.write(("\r\n--" + boundary + "--\r\n").getBytes("UTF-8"));
                    os.flush();
                    os.close();
                    int code = conn.getResponseCode();
                    conn.disconnect();
                    if (code == 200) {
                        LOG.i(TAG, "ads pushed to cloud ok, segs=" + segs.length());
                        // 回传成功：清空待回传队列
                        Hawk.put(PENDING_KEY, new HashMap<String, String>());
                    } else {
                        LOG.i(TAG, "ads push failed code=" + code);
                    }
                } catch (Throwable th) {
                    LOG.i(TAG, "ads push error: " + th.getMessage());
                } finally {
                    sPushing = false;
                }
            }
        });
    }

    private static Map<String, String> getPending() {
        Map<String, String> m = Hawk.get(PENDING_KEY, (Map<String, String>) null);
        return m == null ? new HashMap<String, String>() : m;
    }

    private static void savePending(Map<String, String> m) {
        Hawk.put(PENDING_KEY, m);
    }

    private static String extractKey(String cloudUrl) {
        int idx = cloudUrl.indexOf("/config/");
        return idx >= 0 ? cloudUrl.substring(idx + 1) : "config/ads.json";
    }

    /** 合并两段标记串 "s1,e1;s2,e2" 去重（以 a 为基底，追加 b 中不存在的段） */
    private static String mergeSegments(String a, String b) {
        if (a == null || a.isEmpty()) return b == null ? "" : b;
        if (b == null || b.isEmpty()) return a;
        StringBuilder sb = new StringBuilder(a);
        String[] bParts = b.split(";");
        for (String bp : bParts) {
            if (bp.isEmpty()) continue;
            String start = bp.split(",")[0];
            boolean dup = false;
            for (String ap : a.split(";")) {
                if (!ap.isEmpty() && ap.split(",")[0].equals(start)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) sb.append(";").append(bp);
        }
        return sb.toString();
    }

    private static byte[] readAll(java.io.InputStream is) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    /** 七牛上传凭证：紧凑 JSON（无空格）+ HMAC-SHA1，与 LogUploader 同款签名 */
    public static String makeUploadToken(String ak, String sk, String bucket, String key) {
        try {
            String scope = (key != null && !key.isEmpty()) ? bucket + ":" + key : bucket;
            long deadline = System.currentTimeMillis() / 1000 + 3600;
            String policy = "{\"scope\":\"" + scope + "\",\"deadline\":" + deadline + "}";
            String encodedPolicy = base64Url(policy.getBytes("UTF-8"));
            String sign = hmacSha1Base64(sk, encodedPolicy);
            return ak + ":" + sign + ":" + encodedPolicy;
        } catch (Exception e) {
            return "";
        }
    }

    private static String base64Url(byte[] data) {
        return android.util.Base64.encodeToString(data, android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
    }

    private static String hmacSha1Base64(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA1"));
            return base64Url(mac.doFinal(data.getBytes("UTF-8")));
        } catch (Exception e) {
            return "";
        }
    }
}
