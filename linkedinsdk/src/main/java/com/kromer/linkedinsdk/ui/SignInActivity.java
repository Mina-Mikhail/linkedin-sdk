package com.kromer.linkedinsdk.ui;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.databinding.DataBindingUtil;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.RequiresApi;
import android.support.v7.app.AppCompatActivity;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import com.github.ybq.android.spinkit.sprite.Sprite;
import com.github.ybq.android.spinkit.style.Circle;
import com.kromer.linkedinsdk.Linkedin;
import com.kromer.linkedinsdk.R;
import com.kromer.linkedinsdk.data.enums.ErrorCode;
import com.kromer.linkedinsdk.data.enums.QueryParameter.AuthorizationUrlParameters;
import com.kromer.linkedinsdk.data.enums.QueryParameter.CodeUrlParameters;
import com.kromer.linkedinsdk.data.model.LinkedInUser;
import com.kromer.linkedinsdk.data.network.ApiClient;
import com.kromer.linkedinsdk.data.network.response.AccessTokenResponse;
import com.kromer.linkedinsdk.databinding.ActivitySignInBinding;
import com.kromer.linkedinsdk.utils.Constants;
import com.kromer.linkedinsdk.utils.NetworkUtils;
import com.kromer.linkedinsdk.utils.WebViewUtils.MyJsToAndroid;
import com.uber.autodispose.ScopeProvider;
import io.reactivex.Flowable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.observers.DisposableSingleObserver;
import io.reactivex.schedulers.Schedulers;

import static com.kromer.linkedinsdk.utils.WebViewUtils.addMyClickCallBackJs;
import static com.uber.autodispose.AutoDispose.autoDisposable;

public class SignInActivity
    extends AppCompatActivity {

  private static final String TAG = "LINKED_IN_SDK";
  private ActivitySignInBinding mViewDataBinding;

  private WebView webView;
  private ProgressBar progressBar;

  private String clientId, clientSecret, redirectURL, state;

  private CompositeDisposable compositeDisposable;
  private LinkedInUser linkedInUser;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    if (!NetworkUtils.isNetworkConnected(SignInActivity.this)) {
      onLinkedInSignInFailure(ErrorCode.ERROR_NO_INTERNET,
          getResources().getString(R.string.no_internet));
    } else {
      performDataBinding();
      getIntentData();
      setUpViews();
    }
  }

  private void performDataBinding() {
    mViewDataBinding = DataBindingUtil.setContentView(this, R.layout.activity_sign_in);
    mViewDataBinding.executePendingBindings();
  }

  private void getIntentData() {
    clientId = getIntent().getStringExtra(Linkedin.CLIENT_ID);
    clientSecret = getIntent().getStringExtra(Linkedin.CLIENT_SECRET);
    redirectURL = getIntent().getStringExtra(Linkedin.REDIRECT_URL);
    state = getIntent().getStringExtra(Linkedin.STATE);
  }

  private void setUpViews() {
    setUpLoader();

    initWebView();
  }

  private void setUpLoader() {
    progressBar = mViewDataBinding.progressBar;

    Sprite circle = new Circle();
    circle.setColor(getResources().getColor(R.color.linkedinBlue));
    progressBar.setIndeterminateDrawable(circle);
  }

  @SuppressLint({ "SetJavaScriptEnabled" })
  private void initWebView() {
    webView = mViewDataBinding.webView;
    webView.requestFocus(View.FOCUS_DOWN);

    webView.addJavascriptInterface(new MyJsToAndroid(), "my");
    WebSettings webSettings = webView.getSettings();
    webSettings.setJavaScriptEnabled(true);
    webView.setScrollbarFadingEnabled(true);
    webView.setVerticalScrollBarEnabled(false);
    webView.setWebChromeClient(new WebChromeClient());

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      webView.setWebViewClient(getLollipopWebViewClient());
    } else {
      webView.setWebViewClient(getPreLollipopWebViewClient());
    }

    webView.setFindListener((activeMatchOrdinal, numberOfMatches, isDoneCounting) -> {
      if (numberOfMatches > 0) {
        showLoading();
      } else {
        hideLoading();
      }
    });
    webView.loadUrl(getAuthorizationUrl());
  }
  // https://www.linkedin.com/start/join
  // https://www.linkedin.com/feed/
  // must clear web cache

  @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
  private WebViewClient getLollipopWebViewClient() {
    return new WebViewClient() {
      @Override
      public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        String url = request.getUrl().toString();
        Log.w(TAG, url);

        //when cancel url contain ?trk=current page

        if (url.contains("session_redirect")
            || url.contains("code=")
            || url.contains("login-success")) {

          view.loadUrl(url);
          Uri uri = Uri.parse(url);
          String code = uri.getQueryParameter(CodeUrlParameters.CODE);
          if (code != null && !TextUtils.isEmpty(code)) {
            Log.w(TAG, "code = " + code);
            showLoading();
            compositeDisposable = new CompositeDisposable();
            linkedInUser = new LinkedInUser();
            getAccessToken(code);
          }

          if (url.contains(ErrorCode.ERROR_USER_CANCELLED_MSG)) {
            onLinkedInSignInFailure(ErrorCode.ERROR_USER_CANCELLED,
                getResources().getString(R.string.user_cancelled));
          }
        }

        return true;
      }

      @Override
      public void onPageStarted(WebView view, String url, Bitmap favicon) {
        if (!NetworkUtils.isNetworkConnected(SignInActivity.this)) {
          onLinkedInSignInFailure(ErrorCode.ERROR_NO_INTERNET,
              getResources().getString(R.string.no_internet));
        }

        // Register my clicks lister to each element in page
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
          view.evaluateJavascript(addMyClickCallBackJs(), null);
        }

        showLoading();
      }

      @Override
      public void onPageFinished(WebView view, String url) {
        webView.findAllAsync(ErrorCode.ERROR_MSG);
      }

      @Override
      public void onReceivedError(WebView view, int errorCode, String description,
          String failingUrl) {
        showLoading();
        super.onReceivedError(view, errorCode, description, failingUrl);
      }

      @Override
      public void onReceivedError(WebView view, WebResourceRequest request,
          WebResourceError error) {
        showLoading();
        super.onReceivedError(view, request, error);
      }

      @Override
      public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
        showLoading();
        super.onReceivedSslError(view, handler, error);
      }

      @Override
      public void onReceivedHttpError(WebView view, WebResourceRequest request,
          WebResourceResponse errorResponse) {
        showLoading();
        super.onReceivedHttpError(view, request, errorResponse);
      }
    };
  }

  private WebViewClient getPreLollipopWebViewClient() {
    return new WebViewClient() {
      @Override
      public boolean shouldOverrideUrlLoading(WebView view, String url) {
        Log.w(TAG, url);

        Uri uri = Uri.parse(url);
        String code = uri.getQueryParameter(CodeUrlParameters.CODE);
        if (code != null && !TextUtils.isEmpty(code)) {
          Log.w(TAG, "code = " + code);
          showLoading();
          compositeDisposable = new CompositeDisposable();
          linkedInUser = new LinkedInUser();
          getAccessToken(code);
        }

        if (url.contains(ErrorCode.ERROR_USER_CANCELLED_MSG)) {
          onLinkedInSignInFailure(ErrorCode.ERROR_USER_CANCELLED,
              getResources().getString(R.string.user_cancelled));
        }

        return super.shouldOverrideUrlLoading(view, url);
      }

      @Override
      public void onPageStarted(WebView view, String url, Bitmap favicon) {
        if (!NetworkUtils.isNetworkConnected(SignInActivity.this)) {
          onLinkedInSignInFailure(ErrorCode.ERROR_NO_INTERNET,
              getResources().getString(R.string.no_internet));
        }
        showLoading();
      }

      @Override
      public void onPageFinished(WebView view, String url) {
        webView.findAllAsync(ErrorCode.ERROR_MSG);
      }

      @Override
      public void onReceivedError(WebView view, int errorCode, String description,
          String failingUrl) {
        showLoading();
        super.onReceivedError(view, errorCode, description, failingUrl);
      }

      @Override
      public void onReceivedError(WebView view, WebResourceRequest request,
          WebResourceError error) {
        showLoading();
        super.onReceivedError(view, request, error);
      }

      @Override
      public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
        showLoading();
        super.onReceivedSslError(view, handler, error);
      }

      @Override
      public void onReceivedHttpError(WebView view, WebResourceRequest request,
          WebResourceResponse errorResponse) {
        showLoading();
        super.onReceivedHttpError(view, request, errorResponse);
      }
    };
  }

  private String getAuthorizationUrl() {
    return Uri.parse(Constants.AUTHORIZATION_URL)
        .buildUpon()
        .appendQueryParameter(AuthorizationUrlParameters.RESPONSE_TYPE, Constants.RESPONSE_TYPE)
        .appendQueryParameter(AuthorizationUrlParameters.CLIENT_ID, clientId)
        .appendQueryParameter(AuthorizationUrlParameters.REDIRECT_URI, redirectURL)
        .appendQueryParameter(AuthorizationUrlParameters.STATE, state)
        .appendQueryParameter(AuthorizationUrlParameters.SCOPE, Constants.SIGN_IN_SCOPE).build()
        .toString();
  }

  private void getAccessToken(String code) {
    compositeDisposable.add(ApiClient.getInstance().getApiService()
        .getAccessToken(Constants.GRANT_TYPE, code, redirectURL, clientId, clientSecret)
        .subscribeOn(Schedulers.io())
        .observeOn(AndroidSchedulers.mainThread())
        .as(autoDisposable(ScopeProvider.UNBOUND))
        .subscribeWith(new DisposableSingleObserver<AccessTokenResponse>() {
          @Override
          public void onSuccess(AccessTokenResponse response) {
            if (!compositeDisposable.isDisposed()) {
              String accessToken = response.getAccess_token();
              linkedInUser.setToken(accessToken);

              Log.w(TAG, "accessToken = " + accessToken);
              getFullProfile(accessToken);
              dispose();
            }
          }

          @Override
          public void onError(Throwable e) {
            if (!compositeDisposable.isDisposed()) {
              if (!NetworkUtils.isNetworkConnected(SignInActivity.this)) {
                onLinkedInSignInFailure(ErrorCode.ERROR_NO_INTERNET,
                    getResources().getString(R.string.no_internet));
              } else {
                onLinkedInSignInFailure(ErrorCode.ERROR_OTHER,
                    getResources().getString(R.string.some_error));
              }
              dispose();
            }
          }
        }));
  }

  private void getFullProfile(String accessToken) {
    compositeDisposable.add(Flowable.merge(getLiteProfile(accessToken), getEmail(accessToken))
        .observeOn(AndroidSchedulers.mainThread())
        .doOnError(throwable -> {
          if (!NetworkUtils.isNetworkConnected(SignInActivity.this)) {
            onLinkedInSignInFailure(ErrorCode.ERROR_NO_INTERNET,
                getResources().getString(R.string.no_internet));
          } else {
            onLinkedInSignInFailure(ErrorCode.ERROR_OTHER,
                getResources().getString(R.string.some_error));
          }
        })
        .doOnComplete(() -> onLinkedInSignInSuccess(linkedInUser))
        .as(autoDisposable(ScopeProvider.UNBOUND))
        .subscribe());
  }

  private Flowable<LinkedInUser> getLiteProfile(String accessToken) {
    return ApiClient.getInstance()
        .getApiService()
        .getProfile(Constants.TOKEN_TYPE + accessToken)
        .subscribeOn(Schedulers.newThread())
        .map(
            response -> {
              String id = response.getId();
              String firstName = response.getFirstName();
              String lastName = response.getLastName();
              String profilePicture = response.getProfilePicture();

              linkedInUser.setId(id);
              linkedInUser.setFirstName(firstName);
              linkedInUser.setLastName(lastName);
              linkedInUser.setProfilePicture(profilePicture);

              Log.w(TAG, "profilePicture = " + profilePicture);
              return linkedInUser;
            });
  }

  private Flowable<LinkedInUser> getEmail(String accessToken) {
    return ApiClient.getInstance()
        .getApiService()
        .getEmail(Constants.TOKEN_TYPE + accessToken)
        .subscribeOn(Schedulers.newThread())
        .map(
            response -> {
              String email = response.getEmail();
              linkedInUser.setEmailAddress(email);

              Log.w(TAG, "email = " + email);
              return linkedInUser;
            });
  }

  private void onLinkedInSignInSuccess(LinkedInUser linkedinUser) {
    clear();
    Intent intent = new Intent();
    intent.putExtra(Constants.USER, linkedinUser);
    setResult(Constants.SUCCESS, intent);
    finish();
  }

  private void onLinkedInSignInFailure(int errorType, String errorMsg) {
    Log.w(TAG, errorMsg);
    clear();
    Intent intent = new Intent();
    intent.putExtra(Constants.ERROR_TYPE, errorType);
    setResult(Constants.FAILURE, intent);
    finish();
  }

  private void showLoading() {
    webView.setVisibility(View.GONE);
    progressBar.setVisibility(View.VISIBLE);
  }

  private void hideLoading() {
    webView.setVisibility(View.VISIBLE);
    progressBar.setVisibility(View.GONE);
  }

  private void clear() {
    if (webView == null) return;
    webView.clearCache(true);
    webView.clearHistory();
  }

  @Override
  protected void onDestroy() {
    if (compositeDisposable != null) {
      compositeDisposable.dispose();
    }
    super.onDestroy();
  }
}