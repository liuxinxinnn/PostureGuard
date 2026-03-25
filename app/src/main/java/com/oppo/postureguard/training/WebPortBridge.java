package com.oppo.postureguard.training;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebMessage;
import android.webkit.WebMessagePort;
import android.webkit.WebView;

import androidx.annotation.Nullable;

/**
 * High-frequency bridge using framework WebMessagePort.
 *
 * Why not androidx.webkit WebMessagePortCompat?
 * On some OEM WebView APK versions, WebMessagePortCompat#postMessage throws
 * UnsupportedOperationException which can crash MediaPipe callback threads
 * ("No pending exception expected"). Framework WebMessagePort is much more
 * compatible across devices.
 *
 * Transport: JSON string only (v2). We intentionally avoid ArrayBuffer here
 * to maximize compatibility and prevent crashes.
 */
public final class WebPortBridge {
    private final WebView webView;
    private final Uri targetOrigin;

    @Nullable
    private WebMessagePort port;

    public interface TextListener {
        void onText(String msg);
    }

    public WebPortBridge(WebView webView, Uri targetOrigin) {
        this.webView = webView;
        this.targetOrigin = targetOrigin;
    }

    public void connect(@Nullable final Runnable onReady,
                        @Nullable final TextListener onText) {
        try {
            final WebMessagePort[] ports = webView.createWebMessageChannel();
            port = ports[0];

            final Handler mainHandler = new Handler(Looper.getMainLooper());
            port.setWebMessageCallback(
                    new WebMessagePort.WebMessageCallback() {
                        @Override
                        public void onMessage(WebMessagePort port, WebMessage message) {
                            if (message == null) return;
                            final String data = message.getData();
                            if (data == null || onText == null) return;
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    onText.onText(data);
                                }
                            });
                        }
                    }
            );

            // Send the other end of the port to the page.
            webView.postWebMessage(
                    new WebMessage("init", new WebMessagePort[]{ports[1]}),
                    targetOrigin
            );

            if (onReady != null) onReady.run();
        } catch (Throwable t) {
            // Never crash callers (especially MediaPipe callback threads).
            port = null;
        }
    }

    public void postText(String text) {
        final WebMessagePort p = port;
        if (p == null) return;
        try {
            p.postMessage(new WebMessage(text));
        } catch (Throwable t) {
            // Ignore. Transport failure should never kill camera/pipeline threads.
        }
    }

    public void close() {
        try {
            if (port != null) {
                port.close();
                port = null;
            }
        } catch (Throwable t) {
            port = null;
        }
    }
}
