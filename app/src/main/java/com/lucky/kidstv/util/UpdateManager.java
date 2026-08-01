package com.lucky.kidstv.util;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.lucky.kidstv.R;
import com.lucky.kidstv.bean.AppInfo;
import com.lucky.kidstv.ui.dialog.TipDialog;
import com.lzy.okgo.OkGo;
import com.lzy.okgo.callback.AbsCallback;
import com.lzy.okgo.model.Response;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 远程升级：从 update.json 获取版本信息，比对本地版本，新版本提示下载安装。
 * update.json 结构: {"version":"1.0.20260801_2031","apk":"https://...apk","note":"更新说明"}
 */
public class UpdateManager {

    private static final String UPDATE_URL = "https://cdn.jsdmirror.com/gh/2007xinyuan/LuckyTV@main/app/src/main/assets/update.json";

    /**
     * 检查更新（异步）
     *
     * @param activity    用于弹窗的 Activity
     * @param showUpToDate 已是最新时是否提示（手动检查时 true，启动静默检查 false）
     */
    public static void checkUpdate(final Activity activity, final boolean showUpToDate) {
        OkGo.<String>get(UPDATE_URL)
                .tag("update_check")
                .execute(new AbsCallback<String>() {
                    @Override
                    public String convertResponse(okhttp3.Response response) throws Throwable {
                        if (response.body() != null) {
                            return response.body().string();
                        }
                        throw new IllegalStateException("网络请求错误");
                    }

                    @Override
                    public void onSuccess(Response<String> response) {
                        try {
                            JSONObject obj = new JSONObject(response.body());
                            String remoteVersion = obj.optString("version", "");
                            final String apkUrl = obj.optString("apk", "");
                            String note = obj.optString("note", "发现新版本，是否立即更新？");
                            if (TextUtils.isEmpty(remoteVersion) || TextUtils.isEmpty(apkUrl)) {
                                if (showUpToDate) toast(activity, "更新信息不完整");
                                return;
                            }
                            String localVersion = getLocalVersion(activity);
                            if (needUpdate(localVersion, remoteVersion)) {
                                final String fNote = note;
                                activity.runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        new TipDialog(activity, fNote, "立即更新", "以后再说", new TipDialog.OnListener() {
                                            @Override
                                            public void left() {
                                                downloadAndInstall(activity, apkUrl);
                                            }

                                            @Override
                                            public void right() {
                                            }

                                            @Override
                                            public void cancel() {
                                            }
                                        }).show();
                                    }
                                });
                            } else {
                                if (showUpToDate) toast(activity, "已是最新版本");
                            }
                        } catch (Throwable th) {
                            th.printStackTrace();
                            if (showUpToDate) toast(activity, "检查更新失败");
                        }
                    }

                    @Override
                    public void onError(Response<String> response) {
                        super.onError(response);
                        if (showUpToDate) toast(activity, "检查更新失败");
                    }
                });
    }

    private static boolean needUpdate(String local, String remote) {
        if (TextUtils.isEmpty(local) || TextUtils.isEmpty(remote)) return false;
        // 版本格式 1.0.yyyyMMdd_HHmm，取时间戳部分比较
        String l = local.substring(local.lastIndexOf('.') + 1);
        String r = remote.substring(remote.lastIndexOf('.') + 1);
        try {
            return r.compareTo(l) > 0;
        } catch (Throwable th) {
            return false;
        }
    }

    private static String getLocalVersion(Context ctx) {
        try {
            return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (Throwable th) {
            return "";
        }
    }

    private static void toast(Context ctx, String msg) {
        if (ctx != null) Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show();
    }

    /**
     * 下载 APK 并弹出安装（OkGo 下载到 cacheDir，FileProvider 共享）
     */
    public static void downloadAndInstall(final Context context, final String apkUrl) {
        final File apkFile = new File(context.getCacheDir(), "lucky_update.apk");
        OkGo.<File>get(apkUrl)
                .tag("update_download")
                .execute(new AbsCallback<File>() {
                    @Override
                    public File convertResponse(okhttp3.Response response) throws Throwable {
                        InputStream is = response.body().byteStream();
                        FileOutputStream fos = new FileOutputStream(apkFile);
                        byte[] buf = new byte[8192];
                        int len;
                        while ((len = is.read(buf)) > 0) {
                            fos.write(buf, 0, len);
                        }
                        fos.flush();
                        fos.close();
                        is.close();
                        return apkFile;
                    }

                    @Override
                    public void onSuccess(Response<File> response) {
                        installApk(context, apkFile);
                    }

                    @Override
                    public void onError(Response<File> response) {
                        super.onError(response);
                        toast(context, "下载更新包失败");
                    }
                });
    }

    private static void installApk(Context context, File apkFile) {
        try {
            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", apkFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (Build.VERSION.SDK_INT >= 26) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            context.startActivity(intent);
        } catch (Throwable th) {
            th.printStackTrace();
            toast(context, "安装失败: " + th.getMessage());
        }
    }
}
