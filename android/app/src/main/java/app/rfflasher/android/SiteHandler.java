// Serves the bundled site (assets/site) at https://appassets.androidplatform.net/ as a web server would:
// a folder is its index.html, a page that isn't there is the site's 404 page, and modules get a JavaScript MIME type.
package app.rfflasher.android;

import android.content.Context;
import android.content.res.AssetManager;
import android.webkit.WebResourceResponse;

import androidx.webkit.WebViewAssetLoader;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class SiteHandler implements WebViewAssetLoader.PathHandler {
	private static final String ROOT = "site/";
	private static final Map<String, String> TYPES = new HashMap<>();
	static {
		TYPES.put("html", "text/html");
		TYPES.put("js", "text/javascript");
		TYPES.put("mjs", "text/javascript");
		TYPES.put("css", "text/css");
		TYPES.put("json", "application/json");
		TYPES.put("svg", "image/svg+xml");
		TYPES.put("png", "image/png");
		TYPES.put("jpg", "image/jpeg");
		TYPES.put("jpeg", "image/jpeg");
		TYPES.put("webp", "image/webp");
		TYPES.put("gif", "image/gif");
		TYPES.put("ico", "image/x-icon");
		TYPES.put("woff2", "font/woff2");
		TYPES.put("woff", "font/woff");
		TYPES.put("ttf", "font/ttf");
		TYPES.put("txt", "text/plain");
		TYPES.put("xml", "application/xml");
		TYPES.put("webmanifest", "application/manifest+json");
		TYPES.put("wasm", "application/wasm");
		TYPES.put("zip", "application/zip");
	}

	private final AssetManager assets;

	SiteHandler(Context ctx) { assets = ctx.getAssets(); }

	@Override
	public WebResourceResponse handle(String path) {
		if (path.startsWith("/")) path = path.substring(1);
		if (path.contains("..")) return notFound();
		if (path.isEmpty() || path.endsWith("/")) path += "index.html";
		InputStream in = open(path);
		if (in == null && !lastSegment(path).contains(".")) {
			path += "/index.html";
			in = open(path);
		}
		if (in == null) return notFound();
		WebResourceResponse r = new WebResourceResponse(type(path), encoding(path), in);
		Map<String, String> h = new HashMap<>();
		h.put("Cache-Control", "no-cache");
		r.setResponseHeaders(h);
		return r;
	}

	private WebResourceResponse notFound() {
		InputStream in = open("404.html");
		return new WebResourceResponse("text/html", "utf-8", 404, "Not Found", new HashMap<>(), in);
	}

	private InputStream open(String path) {
		try {
			return assets.open(ROOT + path, AssetManager.ACCESS_STREAMING);
		} catch (IOException e) {
			return null;
		}
	}

	private static String lastSegment(String p) {
		int i = p.lastIndexOf('/');
		return i < 0 ? p : p.substring(i + 1);
	}

	private static String ext(String p) {
		String last = lastSegment(p);
		int i = last.lastIndexOf('.');
		return i < 0 ? "" : last.substring(i + 1).toLowerCase(Locale.ROOT);
	}

	private static String type(String p) {
		String t = TYPES.get(ext(p));
		return t != null ? t : "application/octet-stream";
	}

	private static String encoding(String p) {
		String t = type(p);
		return t.startsWith("text/") || t.endsWith("json") || t.endsWith("xml") || t.endsWith("+json") ? "utf-8" : null;
	}
}
