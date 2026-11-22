package com.github.tvbox.osc.ui.activity;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
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

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 「配置中心」action 结果的独立全屏查看页（qr-t5）。
 *
 * 为什么不用 Dialog：整个壳是 android:screenOrientation="landscape"，
 * 手机横屏只有 360dp 左右高度，二维码下半截必然被裁；独立 Activity 不锁方向，
 * 手机可以竖着看，且没有 dialog 的窗口内边距/遮罩挤压。
 */
public class ActionWebActivity extends Activity {

    private static final String BUILD_TAG = "qr-t5";
    private static final String EXTRA_JSON = "action_json";

    private WebView mWebView;
    private String mUrl = "";
    private String mHtml = "";
    /** true = 只显示二维码（铺满屏）；false = 显示整页 */
    private boolean mQrOnly = true;

    public static void start(Context context, String json) {
        if (context == null || TextUtils.isEmpty(json)) return;
        try {
            Intent it = new Intent(context, ActionWebActivity.class);
            it.putExtra(EXTRA_JSON, json);
            if (!(context instanceof Activity)) it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(it);
        } catch (Throwable th) {
            ZLog.e("ActionWebActivity.start failed", th);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }

        String raw = getIntent() == null ? "" : getIntent().getStringExtra(EXTRA_JSON);
        String url = "";
        String msg = "";
        JSONArray cookie = null;
        try {
            JSONObject obj = new JSONObject(raw == null ? "{}" : raw);
            msg = obj.optString("msg", "");
            JSONObject web = obj.optJSONObject("web");
            if (web != null) {
                url = web.optString("url", "");
                cookie = web.optJSONArray("cookie");
            }
        } catch (Throwable ignored) {
        }
        mUrl = url;
        ZLog.i("BUILD=" + BUILD_TAG + " action 返回 url=" + url
                + " cookieGroups=" + (cookie == null ? 0 : cookie.length())
                + " msgLen=" + (msg == null ? 0 : msg.length())
                + " raw=" + (raw == null ? "null" : raw.substring(0, Math.min(800, raw.length()))));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#FF0E1013"));

        boolean hasHtml = !TextUtils.isEmpty(msg) && msg.contains("<")
                && (msg.contains("<img") || msg.contains("<div") || msg.contains("<html")
                    || msg.contains("<body") || msg.contains("<table") || msg.contains("<canvas")
                    || msg.contains("<svg"));
        boolean hasImg = !TextUtils.isEmpty(msg)
                && (msg.contains("data:image")
                    || msg.matches("(?s)^\\s*https?://\\S+\\.(png|jpg|jpeg|gif|webp|bmp).*$")
                    || msg.matches("(?s)^\\s*[A-Za-z0-9+/=\\s]{400,}$"));

        if (!TextUtils.isEmpty(url) || hasHtml || hasImg) {
            mWebView = new WebView(this);
            WebSettings ws = mWebView.getSettings();
            ws.setJavaScriptEnabled(true);
            ws.setDomStorageEnabled(true);
            ws.setDatabaseEnabled(true);
            ws.setSupportZoom(true);
            ws.setBuiltInZoomControls(true);
            ws.setDisplayZoomControls(false);
            ws.setUseWideViewPort(true);
            ws.setLoadWithOverviewMode(false);
            ws.setJavaScriptCanOpenWindowsAutomatically(true);
            ws.setMediaPlaybackRequiresUserGesture(false);
            ws.setTextZoom(100);
            if (Build.VERSION.SDK_INT >= 21) {
                ws.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            }
            String ua = ws.getUserAgentString();
            if (ua != null && !ua.contains(" Mobile")) ws.setUserAgentString(ua + " Mobile");
            mWebView.setVerticalScrollBarEnabled(true);
            mWebView.setBackgroundColor(Color.parseColor("#FF0E1013"));
            mWebView.setWebChromeClient(new WebChromeClient());
            mWebView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String u) {
                    if (mQrOnly) showQrOnly(view);
                    else injectFit(view);
                }
            });
            applyCookies(url, cookie);
            root.addView(mWebView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            if (!TextUtils.isEmpty(url)) {
                mHtml = "";
                mWebView.loadUrl(url);
            } else if (hasHtml) {
                mHtml = wrapHtml(msg);
                mWebView.loadDataWithBaseURL(null, mHtml, "text/html", "utf-8", null);
            } else {
                String src = msg.trim();
                if (!src.startsWith("data:") && !src.startsWith("http")) {
                    src = "data:image/png;base64," + src.replaceAll("\\s", "");
                }
                mHtml = wrapHtml("<img src=\"" + src + "\"/>");
                mWebView.loadDataWithBaseURL(null, mHtml, "text/html", "utf-8", null);
            }
        } else {
            ScrollView sv = new ScrollView(this);
            TextView tv = new TextView(this);
            tv.setText(TextUtils.isEmpty(msg) ? ("未识别返回：\n" + raw) : msg);
            tv.setTextColor(Color.parseColor("#DDDDDD"));
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            tv.setPadding(dp(14), dp(56), dp(14), dp(16));
            tv.setTextIsSelectable(true);
            sv.addView(tv);
            root.addView(sv, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(6), dp(10), dp(6));
        bar.setBackgroundColor(Color.parseColor("#B3121519"));

        TextView title = new TextView(this);
        title.setText("配置中心");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        bar.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final String openUrl = url;
        if (!TextUtils.isEmpty(openUrl)) {
            TextView open = chip("浏览器打开");
            open.setOnClickListener(v -> {
                try {
                    Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(openUrl));
                    startActivity(it);
                    finish();
                } catch (Throwable th) {
                    ZLog.e("open browser failed", th);
                }
            });
            LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            olp.leftMargin = dp(8);
            bar.addView(open, olp);
        }
        TextView mode = chip(mQrOnly ? "看整页" : "只看二维码");
        mode.setOnClickListener(v -> {
            mQrOnly = !mQrOnly;
            mode.setText(mQrOnly ? "看整页" : "只看二维码");
            reloadPage();
        });
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.leftMargin = dp(8);
        bar.addView(mode, mlp);

        TextView close = chip("关闭");
        close.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.leftMargin = dp(8);
        bar.addView(close, clp);

        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.TOP;
        root.addView(bar, barLp);

        setContentView(root);
        close.requestFocus();
    }

    private TextView chip(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tv.setPadding(dp(12), dp(6), dp(12), dp(6));
        tv.setBackgroundColor(Color.parseColor("#66FFFFFF"));
        tv.setFocusable(true);
        return tv;
    }

    /**
     * 只保留页面里面积最大的那张图/画布（二维码），铺满屏幕并按 contain 缩放。
     * 手机横屏只有 360dp 高度，整页渲染必裁二维码下半截，所以这里直接把页面塌缩成一张图。
     */
    private void showQrOnly(WebView view) {
        try {
            String js = "(function(){try{"
                    + "var best=null,area=0;"
                    + "var list=document.querySelectorAll('img,canvas,svg');"
                    + "for(var i=0;i<list.length;i++){var el=list[i];"
                    + "var r=el.getBoundingClientRect();var a=Math.max(r.width,0)*Math.max(r.height,0);"
                    + "if(a>area){area=a;best=el;}}"
                    + "if(!best){return 'none';}"
                    + "var src='';"
                    + "if(best.tagName==='CANVAS'){try{src=best.toDataURL('image/png');}catch(e){src='';}}"
                    + "else if(best.tagName==='IMG'){src=best.currentSrc||best.src||'';}"
                    + "else {src='';}"
                    + "if(!src&&best.tagName==='SVG'){src='data:image/svg+xml;charset=utf-8,'+encodeURIComponent(new XMLSerializer().serializeToString(best));}"
                    + "if(!src){return 'none';}"
                    + "var html='<html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=8,user-scalable=yes\">'"
                    + "+'<style>html,body{margin:0;padding:0;background:#0E1013;height:100%;overflow:hidden;}'"
                    + "img{position:absolute;left:50%;top:50%;transform:translate(-50%,-50%);"
                    + "max-width:100vw;max-height:100vh;width:auto;height:auto;image-rendering:pixelated;}</style></head>'"
                    + "+'<body><img src=\"'+src+'\"></body></html>';"
                    + "document.open();document.write(html);document.close();"
                    + "return 'ok';}catch(e){return 'err';}})();";
            view.evaluateJavascript(js, value -> {
                ZLog.i("QR 提取结果=" + value);
                // 页面里没有可提取的图（纯文字/表格类返回）→ 退回整页自适应
                if (value == null || value.contains("none") || value.contains("err")) {
                    injectFit(view);
                }
            });
        } catch (Throwable th) {
            ZLog.e("showQrOnly failed", th);
        }
    }

    /** 在「只看二维码 / 看整页」之间切换时重新加载 */
    private void reloadPage() {
        if (mWebView == null) return;
        if (!TextUtils.isEmpty(mUrl)) {
            mWebView.loadUrl(mUrl);
        } else if (!TextUtils.isEmpty(mHtml)) {
            mWebView.loadDataWithBaseURL(null, mHtml, "text/html", "utf-8", null);
        }
    }

    private void injectFit(WebView view) {
        try {
            String js = "(function(){try{"
                    + "var s=document.createElement('style');s.type='text/css';"
                    + "s.innerHTML='html,body{margin:0!important;padding-top:48px!important;overflow-x:hidden!important;}"
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
            ZLog.e("injectFit failed", th);
        }
    }

    private void applyCookies(String url, JSONArray cookie) {
        if (cookie == null) return;
        try {
            CookieManager cm = CookieManager.getInstance();
            cm.setAcceptCookie(true);
            for (int i = 0; i < cookie.length(); i++) {
                JSONObject group = cookie.optJSONObject(i);
                if (group == null) continue;
                String u = group.optString("url", url);
                JSONObject keys = group.optJSONObject("keys");
                if (keys == null) continue;
                java.util.Iterator<String> it = keys.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    cm.setCookie(u, k + "=" + keys.optString(k, ""));
                }
            }
            cm.flush();
        } catch (Throwable th) {
            ZLog.e("applyCookies failed", th);
        }
    }

    private String wrapHtml(String body) {
        if (body == null) body = "";
        if (body.toLowerCase().contains("<html")) return body;
        return "<!DOCTYPE html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=5,user-scalable=yes\">"
                + "<style>html,body{margin:0;padding:52px 10px 16px;background:#0E1013;color:#ddd;font-size:16px;}"
                + "img,canvas,svg{max-width:100%;height:auto;display:block;margin:0 auto;}"
                + "table{width:100%;border-collapse:collapse;}td{word-break:break-all;}"
                + "pre,code{white-space:pre-wrap;word-break:break-all;}</style></head><body>"
                + body + "</body></html>";
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (mWebView != null && mWebView.canGoBack()) {
                mWebView.goBack();
                return true;
            }
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        try {
            if (mWebView != null) {
                mWebView.stopLoading();
                mWebView.loadUrl("about:blank");
                mWebView.destroy();
                mWebView = null;
            }
        } catch (Throwable ignored) {
        }
        super.onDestroy();
    }
}
