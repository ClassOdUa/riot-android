/*
 * Copyright 2015 OpenMarket Ltd
 * Copyright 2018 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.activity;

import android.content.DialogInterface;
import android.content.Intent;
import android.net.http.SslError;
import android.os.Build;
import android.support.v7.app.AlertDialog;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.google.gson.Gson;
import com.google.gson.internal.LinkedTreeMap;
import com.google.gson.reflect.TypeToken;

import org.matrix.androidsdk.util.Log;

import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

import im.vector.R;

/**
 * FallbackLoginActivity is the fallback login activity
 * i.e this activity is created when the client does not support the
 */
public class FallbackLoginActivity extends RiotAppCompatActivity {
    private static final String LOG_TAG = FallbackLoginActivity.class.getSimpleName();

    public static final String EXTRA_HOME_SERVER_ID = "FallbackLoginActivity.EXTRA_HOME_SERVER_ID";

    private WebView mWebView = null;
    private String mHomeServerUrl = null;

    @Override
    public int getLayoutRes() {
        return R.layout.activity_login_fallback;
    }

    @Override
    public int getTitleRes() {
        return R.string.login;
    }

    @Override
    public void initUiAndData() {
        mWebView = findViewById(R.id.account_creation_webview);
        mWebView.getSettings().setJavaScriptEnabled(true);

        Intent intent = getIntent();
        mHomeServerUrl = "https://matrix.org/";

        if (intent.hasExtra(EXTRA_HOME_SERVER_ID)) {
            mHomeServerUrl = intent.getStringExtra(EXTRA_HOME_SERVER_ID);
        }

        // check the trailing slash
        if (!mHomeServerUrl.endsWith("/")) {
            mHomeServerUrl += "/";
        }

        // AppRTC requires third party cookies to work
        android.webkit.CookieManager cookieManager = android.webkit.CookieManager.getInstance();

        // clear the cookies must be cleared
        if ((null != cookieManager) && !cookieManager.hasCookies()) {
            launchWebView();
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            try {
                cookieManager.removeAllCookie();
            } catch (Exception e) {
                Log.e(LOG_TAG, " cookieManager.removeAllCookie() fails " + e.getLocalizedMessage(), e);
            }
            launchWebView();
        } else {
            try {
                cookieManager.removeAllCookies(new ValueCallback<Boolean>() {
                    @Override
                    public void onReceiveValue(Boolean value) {
                        launchWebView();
                    }
                });
            } catch (Exception e) {
                Log.e(LOG_TAG, " cookieManager.removeAllCookie() fails " + e.getLocalizedMessage(), e);
                launchWebView();
            }
        }
    }

    private void launchWebView() {
        mWebView.loadUrl(mHomeServerUrl + "_matrix/static/client/login/");

        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler,
                                           SslError error) {
                final SslErrorHandler fHander = handler;

                new AlertDialog.Builder(FallbackLoginActivity.this)
                        .setMessage(R.string.ssl_could_not_verify)
                        .setPositiveButton(R.string.ssl_trust, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                fHander.proceed();
                            }
                        })
                        .setNegativeButton(R.string.ssl_do_not_trust, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                fHander.cancel();
                            }
                        })
                        .setOnKeyListener(new DialogInterface.OnKeyListener() {
                            @Override
                            public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                                if (event.getAction() == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_BACK) {
                                    fHander.cancel();
                                    dialog.dismiss();
                                    return true;
                                }
                                return false;
                            }
                        })
                        .show();
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);

                // on error case, close this activity
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        finish();
                    }
                });
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // avoid infinite onPageFinished call
                if (url.startsWith("http")) {
                    // Generic method to make a bridge between JS and the UIWebView
                    final String MXCJavascriptSendObjectMessage = "javascript:window.matrixLogin.sendObjectMessage = function(parameters) { var i" +
                            "frame = document.createElement('iframe');  iframe.setAttribute('src', 'js:' + JSON.stringify(parameters));  document" +
                            ".documentElement.appendChild(iframe); iframe.parentNode.removeChild(iframe); iframe = null; };";

                    view.loadUrl(MXCJavascriptSendObjectMessage);

                    // The function the fallback page calls when the registration is complete
                    final String MXCJavascriptOnRegistered = "javascript:window.matrixLogin.onLogin = function(homeserverUrl, userId, accessToken" +
                            ") { matrixLogin.sendObjectMessage({ 'action': 'onLogin', 'homeServer': homeserverUrl,'userId': userId,  'accessToken" +
                            "': accessToken  }); };";

                    view.loadUrl(MXCJavascriptOnRegistered);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(android.webkit.WebView view, java.lang.String url) {
                if ((null != url) && url.startsWith("js:")) {
                    String json = url.substring(3);
                    Map<String, Object> serverParams = null;

                    try {
                        // URL decode
                        json = URLDecoder.decode(json, "UTF-8");
                        serverParams = new Gson().fromJson(json, new TypeToken<HashMap<String, Object>>() {
                        }.getType());

                    } catch (Exception e) {
                        Log.e(LOG_TAG, "## shouldOverrideUrlLoading() : fromJson failed " + e.getMessage(), e);
                    }

                    // succeeds to parse parameters
                    if (null != serverParams) {
                        try {
                            String action = (String) serverParams.get("action");
                            LinkedTreeMap<String, String> parameters = (LinkedTreeMap<String, String>) serverParams.get("homeServer");

                            if (TextUtils.equals("onLogin", action) && (null != parameters)) {
                                final String userId = parameters.get("user_id");
                                final String accessToken = parameters.get("access_token");
                                final String homeServer = parameters.get("home_server");

                                // remove the trailing /
                                if (mHomeServerUrl.endsWith("/")) {
                                    mHomeServerUrl = mHomeServerUrl.substring(0, mHomeServerUrl.length() - 1);
                                }

                                // check if the parameters are defined
                                if ((null != homeServer) && (null != userId) && (null != accessToken)) {
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            Intent returnIntent = new Intent();
                                            returnIntent.putExtra("homeServerUrl", mHomeServerUrl);
                                            returnIntent.putExtra("homeServer", homeServer);
                                            returnIntent.putExtra("userId", userId);
                                            returnIntent.putExtra("accessToken", accessToken);
                                            setResult(RESULT_OK, returnIntent);

                                            finish();
                                        }
                                    });
                                }
                            }
                        } catch (Exception e) {
                            Log.e(LOG_TAG, "## shouldOverrideUrlLoading() : failed " + e.getMessage(), e);
                        }
                    }
                    return true;
                }

                return false;
            }
        });
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            // This is to fix a bug in the v7 support lib. If there is no options menu and you hit MENU, it will crash with a
            // NPE @ android.support.v7.app.ActionBarImplICS.getThemedContext(ActionBarImplICS.java:274)
            // This can safely be removed if we add in menu options on this screen
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        CommonActivityUtils.onLowMemory(this);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        CommonActivityUtils.onTrimMemory(this, level);
    }
}
