package com.github.tvbox.osc.ui.dialog;

import android.app.Activity;
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
        mRoot.setPadding(dp(24), dp(20), dp(24), dp(16));

        TextView title = new TextView(act);
        title.setText("配置中心");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setGravity(Gravity.START);
        mRoot.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (!TextUtils.isEmpty(mUrl)) {
            mWebView = new WebView(act);
            WebSettings ws = mWebView.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setUseWideViewPort(true);
            ws.setLoadWithOverviewMode(true);
            ws.setSupportZoom(false);
            ws.setMediaPlaybackRequiresUserGesture(false);
            mWebView.setWebViewClient(new WebViewClient());
            mWebView.setWebChromeClient(new WebChromeClient());
            applyCookies();
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            lp.topMargin = dp(12);
            mRoot.addView(mWebView, lp);
            mWebView.loadUrl(mUrl);
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
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
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
