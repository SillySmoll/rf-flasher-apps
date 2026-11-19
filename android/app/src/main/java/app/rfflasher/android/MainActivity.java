// RadioFoundry Flasher for Android (unofficial wrapper)
// Shows the RadioFoundry web installer, built from github.com/radiofoundry/radiofoundry (MIT) and bundled in
// assets/site, in a WebView. WebView has no WebUSB, so assets/rf-android.js adds navigator.usb on top of UsbBridge.
package app.rfflasher.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;

public class MainActivity extends Activity {
	static final String HOST = "appassets.androidplatform.net";
	static final String ORIGIN = "https://" + HOST;
	static final String START = ORIGIN + "/flash/";

	private static final int REQ_OPEN = 1;
	private static final int REQ_SAVE = 2;

	WebView web;
	private UsbBridge bridge;
	private String shim;
	private boolean shimAtStart;
	private ValueCallback<Uri[]> fileCallback;
	private byte[] pendingSave;
	private CompletableFuture<Boolean> pendingSaveDone;

	@Override
	protected void onCreate(Bundle state) {
		super.onCreate(state);
		// a flash or backup must not stop because the screen went off
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) WebView.setWebContentsDebuggingEnabled(true);

		web = new WebView(this);
		setContentView(edgeSafe(web));
		WebSettings s = web.getSettings();
		s.setJavaScriptEnabled(true);
		s.setDomStorageEnabled(true);
		s.setDatabaseEnabled(true);
		s.setAllowFileAccess(false);
		s.setAllowContentAccess(false);
		s.setSupportMultipleWindows(false);
		s.setUserAgentString(s.getUserAgentString() + " RFFlasherAndroid/" + BuildConfig.VERSION_NAME);

		final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
				.setDomain(HOST)
				.addPathHandler("/", new SiteHandler(this))
				.build();

		bridge = new UsbBridge(this);
		web.addJavascriptInterface(bridge, "RFBridge");
		shim = readAsset("rf-android.js");
		if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
			WebViewCompat.addDocumentStartJavaScript(web, shim, Collections.singleton(ORIGIN));
			shimAtStart = true;
		}

		web.setWebViewClient(new WebViewClient() {
			@Override
			public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
				return loader.shouldInterceptRequest(req.getUrl());
			}

			@Override
			public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
				Uri u = req.getUrl();
				if (HOST.equals(u.getHost())) return false;
				openExternal(u);
				return true;
			}

			@Override
			public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
				// older WebView without document-start scripts: as early as this API allows
				if (!shimAtStart) view.evaluateJavascript(shim, null);
			}
		});

		web.setWebChromeClient(new WebChromeClient() {
			@Override
			public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
				if (fileCallback != null) fileCallback.onReceiveValue(null);
				fileCallback = callback;
				// the page asks for extensions (.bin, .zip); Android's pickers only know MIME types, so show every file
				Intent i = new Intent(Intent.ACTION_GET_CONTENT);
				i.addCategory(Intent.CATEGORY_OPENABLE);
				i.setType("*/*");
				if (params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
				try {
					startActivityForResult(Intent.createChooser(i, "Choose a file"), REQ_OPEN);
				} catch (ActivityNotFoundException e) {
					fileCallback = null;
					Toast.makeText(MainActivity.this, "No file picker on this device", Toast.LENGTH_LONG).show();
					return false;
				}
				return true;
			}
		});

		if (state != null) web.restoreState(state);
		else web.loadUrl(START);
	}

	@Override
	protected void onSaveInstanceState(Bundle out) {
		super.onSaveInstanceState(out);
		web.saveState(out);
	}

	@Override
	public void onBackPressed() {
		if (web.canGoBack()) web.goBack();
		else super.onBackPressed();
	}

	@Override
	protected void onDestroy() {
		bridge.shutdown();
		web.destroy();
		super.onDestroy();
	}

	// Android 15 draws apps under the status bar, camera cutout and navigation bar. The page goes in a frame padded by
	// those insets, coloured like the page, with status bar icons that stay readable on it.
	private View edgeSafe(WebView view) {
		boolean dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
		int bg = dark ? 0xFF121417 : 0xFFF7F7F5;
		FrameLayout frame = new FrameLayout(this);
		frame.setBackgroundColor(bg);
		view.setBackgroundColor(bg);
		frame.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
		frame.setOnApplyWindowInsetsListener((v, insets) -> {
			int l, t, r, b;
			if (Build.VERSION.SDK_INT >= 30) {
				Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
				l = i.left; t = i.top; r = i.right; b = i.bottom;
			} else {
				l = insets.getSystemWindowInsetLeft(); t = insets.getSystemWindowInsetTop();
				r = insets.getSystemWindowInsetRight(); b = insets.getSystemWindowInsetBottom();
			}
			v.setPadding(l, t, r, b);
			return insets;
		});
		Window w = getWindow();
		w.setStatusBarColor(bg);
		w.setNavigationBarColor(bg);
		if (Build.VERSION.SDK_INT >= 30) {
			WindowInsetsController c = w.getInsetsController();
			if (c != null) {
				int light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
				c.setSystemBarsAppearance(dark ? 0 : light, light);
			}
		} else if (!dark && Build.VERSION.SDK_INT >= 23) {
			int f = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
			w.getDecorView().setSystemUiVisibility(f);
		}
		return frame;
	}

	void openExternal(Uri u) {
		String scheme = u.getScheme();
		if (!"https".equals(scheme) && !"http".equals(scheme) && !"mailto".equals(scheme)) return;
		try {
			startActivity(new Intent(Intent.ACTION_VIEW, u));
		} catch (ActivityNotFoundException ignored) {
			Toast.makeText(this, u.toString(), Toast.LENGTH_LONG).show();
		}
	}

	// A file the page downloads (a backup, a codeplug): Android's "save as" screen, then the bytes. Called off the UI
	// thread; returns once the file is written (true) or the user cancelled (false).
	boolean saveFile(String name, String mime, byte[] data) throws Exception {
		CompletableFuture<Boolean> done = new CompletableFuture<>();
		runOnUiThread(() -> {
			if (pendingSaveDone != null) pendingSaveDone.complete(false);
			pendingSave = data;
			pendingSaveDone = done;
			Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
			i.addCategory(Intent.CATEGORY_OPENABLE);
			i.setType(mime == null || mime.isEmpty() ? "application/octet-stream" : mime);
			i.putExtra(Intent.EXTRA_TITLE, name);
			try {
				startActivityForResult(i, REQ_SAVE);
			} catch (ActivityNotFoundException e) {
				pendingSave = null;
				pendingSaveDone = null;
				done.completeExceptionally(e);
			}
		});
		return done.get();
	}

	@Override
	protected void onActivityResult(int request, int result, Intent data) {
		super.onActivityResult(request, result, data);
		if (request == REQ_OPEN) {
			if (fileCallback == null) return;
			Uri[] picked = null;
			if (result == RESULT_OK && data != null) {
				if (data.getClipData() != null) {
					int n = data.getClipData().getItemCount();
					picked = new Uri[n];
					for (int k = 0; k < n; k++) picked[k] = data.getClipData().getItemAt(k).getUri();
				} else if (data.getData() != null) picked = new Uri[] { data.getData() };
			}
			fileCallback.onReceiveValue(picked);
			fileCallback = null;
		} else if (request == REQ_SAVE) {
			CompletableFuture<Boolean> done = pendingSaveDone;
			byte[] bytes = pendingSave;
			pendingSave = null;
			pendingSaveDone = null;
			if (done == null) return;
			if (result != RESULT_OK || data == null || data.getData() == null) { done.complete(false); return; }
			try (OutputStream os = getContentResolver().openOutputStream(data.getData(), "wt")) {
				if (os == null) throw new IOException("can't open the file");
				os.write(bytes);
				done.complete(true);
				Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
			} catch (Exception e) {
				done.completeExceptionally(e);
				new AlertDialog.Builder(this).setTitle("Couldn't save the file").setMessage(String.valueOf(e.getMessage()))
						.setPositiveButton(android.R.string.ok, null).show();
			}
		}
	}

	String readAsset(String name) {
		try (InputStream in = getAssets().open(name)) {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[16384];
			for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
			return out.toString(StandardCharsets.UTF_8.name());
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}
}
