package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.github.tvbox.osc.util.ZLog;
import com.lcstudio.commonsurport.L;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 「配置中心」这类 action 卡片的结果渲染器（二维码优化版 qr-t4）。
 *
 * 站点 spider 的 action(act) 返回值约定：
 *   {"msg":"提示文字 / HTML / 图片地址 / data:image"}
 *   {"web":{"url":"https://...","mode":"browser|view","cookie":[{"url":"..","keys":{"k":"v"}}]}}
 *
 * 二维码页面的关键约束：内容必须能用满整屏高度，且可缩放/可滚动，
 * 否则二维码下半部分会被壳自己的标题栏/底栏/系统栏压掉 → 扫不出来。
 * 所以这里：全屏 + 无底栏 + 顶部悬浮小按钮 + 允许双指缩放 + 载入后注入自适应样式。
 */
public class ActionResultDialog extends Dialog {

    /** 构建标记：用来在 app.log 里确认装的是哪一版 */
    private static final String BUILD_TAG = "qr-t4";

    private final String mRaw;
    private String mUrl;
    private String mMsg;
    private JSONArray mCookie;

    private WebView mWebView;

    public ActionResultDialog(Activity activity, String rawJson) {
        super(activity, android.R.style.Theme_Translucent_NoTitleBar);
        this.mRaw = rawJson;
        parse(rawJson);
    }

    private void parse(String json) {
        if (TextUtils.isEmpty(json)) return;
        try {
            JSONObject obj = new JSONObject(json);
            mMsg = obj.optString("msg", "");
            JSONObject web = obj.optJSONObject("web");
            if (web != null) {
                mUrl = web.optString("url", "");
                JSONArray arr = web.optJSONArray("cookie");
                if (arr != null) mCookie = arr;
            }
        } catch (Throwable th) {
            L.w("ActionResultDialog parse failed: " + th);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Activity act = getOwnerActivity();
        Window win = getWindow();
        if (win != null) {
            win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            win.setBackgroundDrawableResource(android.R.color.transparent);
            // 全屏：状态栏也吃掉，避免页面底部被系统栏压住
            win.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                    WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }

        ZLog.i("BUILD=" + BUILD_TAG + " action 返回 url=" + (mUrl == null ? "" : mUrl)
                + " cookieGroups=" + (mCookie == null ? 0 : mCookie.length())
                + " msgLen=" + (mMsg == null ? 0 : mMsg.length())
                + " raw=" + (mRaw == null ? "null" : mRaw.substring(0, Math.min(800, mRaw.length()))));

        FrameLayout root = new FrameLayout(act);
        root.setBackgroundColor(Color.parseColor("#FF0E1013"));

        boolean hasHtmlInMsg = !TextUtils.isEmpty(mMsg) && mMsg.contains("<")
                && (mMsg.contains("<img") || mMsg.contains("<div") || mMsg.contains("<html")
                    || mMsg.contains("<body") || mMsg.contains("<table") || mMsg.contains("<canvas")
                    || mMsg.contains("<svg"));
        boolean hasImgInMsg = !TextUtils.isEmpty(mMsg)
                && (mMsg.contains("data:image")
                    || mMsg.matches("(?s)^\\s*https?://\\S+\\.(png|jpg|jpeg|gif|webp|bmp).*$")
                    || mMsg.matches("(?s)^\\s*[A-Za-z0-9+/=\\s]{400,}$"));   // 纯 base64 图片

        if (!TextUtils.isEmpty(mUrl) || hasHtmlInMsg || hasImgInMsg) {
            mWebView = new WebView(act);
            WebSettings ws = mWebView.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setDatabaseEnabled(true);
            ws.setSupportZoom(true);
            ws.setBuiltInZoomControls(true);
            ws.setDisplayZoomControls(false);
            ws.setUseWideViewPort(true);
            // 关键：不要 overview，否则整页被缩进一屏，二维码被压成小块且下半截被裁
            ws.setLoadWithOverviewMode(false);
            ws.setJavaScriptCanOpenWindowsAutomatically(true);
            ws.setMediaPlaybackRequiresUserGesture(false);
            ws.setTextZoom(100);
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            }
            String ua = ws.getUserAgentString();
            if (ua != null && !ua.contains(" Mobile")) ws.setUserAgentString(ua + " Mobile");
            mWebView.setVerticalScrollBarEnabled(true);
            mWebView.setHorizontalScrollBarEnabled(false);
            mWebView.setBackgroundColor(Color.parseColor("#FF0E1013"));
            mWebView.setWebChromeClient(new WebChromeClient());
            mWebView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    injectFit(view);
                }
            });
            applyCookies();
            root.addView(mWebView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            if (!TextUtils.isEmpty(mUrl)) {
                mWebView.loadUrl(mUrl);
            } else if (hasHtmlInMsg) {
                mWebView.loadDataWithBaseURL(null, wrapHtml(mMsg), "text/html", "utf-8", null);
            } else {
                String src = mMsg.trim();
                if (!src.startsWith("data:") && !src.startsWith("http")) {
                    src = "data:image/png;base64," + src.replaceAll("\\s", "");
                }
                mWebView.loadDataWithBaseURL(null, wrapHtml("<img src=\"" + src + "\"/>"),
                        "text/html", "utf-8", null);
            }
        } else {
            ScrollView sv = new ScrollView(act);
            TextView content = new TextView(act);
            content.setText(TextUtils.isEmpty(mMsg) ? ("未识别返回：\n" + safeRaw()) : mMsg);
            content.setTextColor(Color.parseColor("#DDDDDD"));
            content.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            content.setGravity(Gravity.START);
            content.setPadding(dp(14), dp(56), dp(14), dp(16));
            content.setTextIsSelectable(true);
            sv.addView(content);
            root.addView(sv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        // 顶部悬浮条：标题 + 浏览器打开 + 关闭。悬浮在内容之上、不占内容高度
        LinearLayout bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(6), dp(10), dp(6));
        bar.setBackgroundColor(Color.parseColor("#B3121519"));

        TextView title = new TextView(act);
        title.setText("配置中心");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (!TextUtils.isEmpty(mUrl)) {
            TextView open = chip(act, "浏览器打开");
            open.setOnClickListener(v -> {
                try {
                    Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(mUrl));
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    getContext().startActivity(it);
                    dismiss();
                } catch (Throwable th) {
                    L.w("open browser failed " + th);
                }
            });
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            olp.leftMargin = dp(8);
            bar.addView(open, olp);
        }
        TextView close = chip(act, "关闭");
        close.setOnClickListener(v -> dismiss());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(8);
        bar.addView(close, clp);

        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.TOP;
        root.addView(bar, barLp);

        setContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        close.requestFocus();
        dumpRaw();
    }

    private TextView chip(Activity act, String text) {
        TextView tv = new TextView(act);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setPadding(dp(12), dp(6), dp(12), dp(6));
        tv.setBackgroundColor(Color.parseColor("#66FFFFFF"));
        tv.setFocusable(true);
        return tv;
    }

    /** 载入后注入自适应样式：图片/画布不超出屏宽，页面可纵向滚动，不强行缩放整页 */
    private void injectFit(WebView view) {
        try {
            String js = "(function(){try{"
                    + "var s=document.createElement('style');"
                    + "s.type='text/css';"
                    + "s.innerHTML='html,body{margin:0!important;padding-top:44px!important;overflow-x:hidden!important;}"
                    + "img,canvas,svg,video{max-width:100%!important;height:auto!important;display:block!important;margin:0 auto!important;}"
                    + "table{width:100%!important;}td{word-break:break-all;}';"
                    + "(document.head||document.documentElement).appendChild(s);"
                    + "var vp=document.querySelector('meta[name=viewport]');"
                    + "if(!vp){vp=document.createElement('meta');vp.name='viewport';"
                    + "vp.content='width=device-width,initial-scale=1,maximum-scale=5,user-scalable=yes';"
                    + "(document.head||document.documentElement).appendChild(vp);}"
                    + "}catch(e){}})();";
            view.evaluateJavascript(js, null);
        } catch (Throwable th) {
            L.w("injectFit failed " + th);
        }
    }

    /** 让页面按屏幕宽度自适应，图片不超出屏幕，二维码居中 */
    private String wrapHtml(String body) {
        if (body == null) body = "";
        if (body.toLowerCase().contains("<html")) return body;
        return "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=5,user-scalable=yes\">"
                + "<style>html,body{margin:0;padding:48px 10px 16px;background:#0E1013;color:#ddd;font-size:16px;}"
                + "img,canvas,svg{max-width:100%;height:auto;display:block;margin:0 auto;}"
                + "table{width:100%;border-collapse:collapse;}td{word-break:break-all;}"
                + "pre,code{white-space:pre-wrap;word-break:break-all;}</style></head><body>"
                + body + "</body></html>";
    }

    private void applyCookies() {
        if (mCookie == null) return;
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            for (int i = 0; i < mCookie.length(); i++) {
                JSONObject group = mCookie.optJSONObject(i);
                if (group == null) continue;
                String url = group.optString("url", mUrl);
                JSONObject keys = group.optJSONObject("keys");
                if (keys == null) continue;
                java.util.Iterator<String> it = keys.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    cm.setCookie(url, k + "=" + keys.optString(k, ""));
                }
            }
            cm.flush();
        } catch (Throwable th) {
            L.w("ActionResultDialog cookie failed: " + th);
        }
    }

    private String safeRaw() {
        if (TextUtils.isEmpty(mRaw)) return "(空)";
        return mRaw.length() > 1500 ? mRaw.substring(0, 1500) + " …" : mRaw;
    }

    /** 落盘原始返回，便于按真实协议继续适配（路径：Android/data/<pkg>/files/action_log/） */
    private void dumpRaw() {
        try {
            Activity act = getOwnerActivity();
            File dir = act == null ? null : act.getExternalFilesDir("action_log");
            if (dir == null) return;
            if (!dir.exists()) dir.mkdirs();
            String name = "action-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".json";
            FileOutputStream fos = new FileOutputStream(new File(dir, name));
            fos.write((mRaw == null ? "" : mRaw).getBytes("UTF-8"));
            fos.close();
            L.d("ActionResultDialog dump -> " + new File(dir, name).getAbsolutePath());
        } catch (Throwable th) {
            L.w("ActionResultDialog dump failed: " + th);
        }
    }

    private int dp(int v) {
        return (int) (v * getContext().getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (mWebView != null && mWebView.canGoBack()) {
                mWebView.goBack();
                return true;
            }
            dismiss();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void dismiss() {
        try {
            if (mWebView != null) {
                mWebView.stopLoading();
                mWebView.loadUrl("about:blank");
                mWebView.destroy();
                mWebView = null;
            }
        } catch (Throwable ignored) {
        }
        super.dismiss();
    }

    public static void show(Activity activity, String json) {
        if (activity == null || activity.isFinishing()) return;
        // 改用独立全屏页：壳整体锁横屏，Dialog 在手机上只有 360dp 高度，二维码底部会被裁
        com.github.tvbox.osc.ui.activity.ActionWebActivity.start(activity, json);
    }

    /** 供 Fragment 等非 Activity 上下文使用 */
    public static void show(android.content.Context context, String json) {
        if (context instanceof Activity) show((Activity) context, json);
    }
}
