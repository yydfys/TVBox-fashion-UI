package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.app.Dialog;
import android.graphics.Color;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
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
 * 「配置中心」这类 action 卡片的结果渲染器。
 *
 * 站点 spider 的 action(act) 返回值约定（与主流壳一致）：
 *   {"msg":"提示文字"}
 *   {"web":{"url":"https://...","mode":"browser|view","cookie":[{"url":"..","keys":{"k":"v"}}]}}
 *
 * 这里只做「显示」：有 web 就开内嵌 WebView 让页面自己跑（页面内部的网盘授权/保存逻辑由页面自身完成），
 * 只有 msg 就弹个文本。原始 JSON 同时落盘到 files/action_log/，方便按真实协议继续适配。
 */
public class ActionResultDialog extends Dialog {

    private final String mRaw;
    private String mUrl;
    private String mMsg;
    private JSONArray mCookie;

    private WebView mWebView;
    private LinearLayout mRoot;

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
        ZLog.i("action 返回 url=" + (mUrl == null ? "" : mUrl)
                + " cookieGroups=" + (mCookie == null ? 0 : mCookie.length())
                + " msgLen=" + (mMsg == null ? 0 : mMsg.length())
                + " raw=" + (mRaw == null ? "null" : mRaw.substring(0, Math.min(800, mRaw.length()))));
        super.onCreate(savedInstanceState);
        Activity act = getOwnerActivity();
        Window win = getWindow();
        if (win != null) {
            win.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
            win.setBackgroundDrawableResource(android.R.color.transparent);
        }

        mRoot = new LinearLayout(act);
        mRoot.setOrientation(LinearLayout.VERTICAL);
        mRoot.setBackgroundColor(Color.parseColor("#F2101215"));
        mRoot.setPadding(dp(10), dp(10), dp(10), dp(8));

        TextView title = new TextView(act);
        title.setText("配置中心");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setGravity(Gravity.START);
        LinearLayout titleRow = new LinearLayout(act);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!TextUtils.isEmpty(mUrl)) {
            TextView open = new TextView(act);
            open.setText("浏览器打开");
            open.setTextColor(Color.WHITE);
            open.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            open.setPadding(dp(10), dp(4), dp(10), dp(4));
            open.setBackgroundColor(Color.parseColor("#66FFFFFF"));
            open.setFocusable(true);
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
            titleRow.addView(open, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        mRoot.addView(titleRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        boolean hasHtmlInMsg = !TextUtils.isEmpty(mMsg)
                && (mMsg.contains("<") && (mMsg.contains("<img") || mMsg.contains("<div")
                    || mMsg.contains("<html") || mMsg.contains("<body") || mMsg.contains("<table")));
        boolean hasImgInMsg = !TextUtils.isEmpty(mMsg)
                && (mMsg.contains("data:image") || mMsg.matches("(?s)^\\s*https?://\\S+\\.(png|jpg|jpeg|gif|webp).*$"));
        if (!TextUtils.isEmpty(mUrl) || hasHtmlInMsg || hasImgInMsg) {
            mWebView = new WebView(act);
            WebSettings ws = mWebView.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setDatabaseEnabled(true);
            // 关键：允许缩放，二维码页面才能双指放大到可扫
            ws.setSupportZoom(true);
            ws.setBuiltInZoomControls(true);
            ws.setDisplayZoomControls(false);
            ws.setUseWideViewPort(true);
            ws.setLoadWithOverviewMode(true);
            ws.setJavaScriptCanOpenWindowsAutomatically(true);
            ws.setMediaPlaybackRequiresUserGesture(false);
            if (android.os.Build.VERSION.SDK_INT >= 21) {
                ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            }
            String ua = ws.getUserAgentString();
            if (ua != null && !ua.contains(" Mobile")) {
                ws.setUserAgentString(ua + " Mobile");
            }
            ws.setAllowFileAccess(true);
            mWebView.setWebViewClient(new WebViewClient());
            mWebView.setWebChromeClient(new WebChromeClient());
            mWebView.setInitialScale(0);
            applyCookies();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            lp.topMargin = dp(6);
            mRoot.addView(mWebView, lp);
            if (!TextUtils.isEmpty(mUrl)) {
                mWebView.loadUrl(mUrl);
            } else if (hasHtmlInMsg) {
                mWebView.loadDataWithBaseURL(mUrl == null ? null : mUrl, wrapHtml(mMsg), "text/html", "utf-8", null);
            } else {
                mWebView.loadDataWithBaseURL(null, wrapHtml("<img src=\"" + mMsg.trim() + "\" style=\"width:100%;height:auto\"/>"),
                        "text/html", "utf-8", null);
            }
        } else {
            TextView content = new TextView(act);
            content.setText(TextUtils.isEmpty(mMsg) ? ("未识别返回：\n" + safeRaw()) : mMsg);
            content.setTextColor(Color.parseColor("#DDDDDD"));
            content.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            content.setGravity(Gravity.START);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            lp.topMargin = dp(12);
            mRoot.addView(content, lp);
        }

        TextView close = new TextView(act);
        close.setText("关闭");
        close.setTextColor(Color.WHITE);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        close.setGravity(Gravity.CENTER);
        close.setFocusable(true);
        close.setBackgroundColor(Color.parseColor("#66FFFFFF"));
        close.setOnClickListener(v -> dismiss());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(38));
        clp.topMargin = dp(12);
        mRoot.addView(close, clp);

        setContentView(mRoot, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        close.requestFocus();
        dumpRaw();
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

    /** 让页面按屏幕宽度自适应，图片不超出屏幕，二维码居中且尽量大 */
    private String wrapHtml(String body) {
        if (body == null) body = "";
        if (body.toLowerCase().contains("<html")) return body;
        return "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<style>html,body{margin:0;padding:8px;background:#101215;color:#ddd;font-size:16px;}"
                + "img{max-width:100%;height:auto;display:block;margin:0 auto;}"
                + "table{width:100%;border-collapse:collapse;}td{word-break:break-all;}"
                + "pre,code{white-space:pre-wrap;word-break:break-all;}</style></head><body>"
                + body + "</body></html>";
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
        try {
            ActionResultDialog d = new ActionResultDialog(activity, json);
            d.setOwnerActivity(activity);
            d.show();
        } catch (Throwable th) {
            L.w("ActionResultDialog show failed: " + th);
        }
    }

    /** 供 Fragment 等非 Activity 上下文使用 */
    public static void show(android.content.Context context, String json) {
        if (context instanceof Activity) show((Activity) context, json);
    }
}
