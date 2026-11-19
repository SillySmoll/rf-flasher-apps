// The Java side of rf-android.js: WebUSB calls on Android's USB host API, the build service's HTTP requests, and
// saving downloaded files. Each call(id, op, args) answers window.__rfAndroid.done(id, ok, value) once it ends.
package app.rfflasher.android;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConfiguration;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

final class UsbBridge {
	private static final String ACTION_PERMISSION = "app.rfflasher.android.USB_PERMISSION";
	private static final int CONTROL_TIMEOUT_MS = 5000;
	private static final int OUT_TIMEOUT_MS = 5000;
	private static final int READ_POLL_MS = 100;
	private static final int OUT_CHUNK = 16384;
	private static final int FTDI = 0x0403;
	// what the build service sees as the page's origin: the web site's, as from Chrome on radiofoundry.org
	private static final String SITE_ORIGIN = "https://radiofoundry.org";

	private final MainActivity act;
	private final UsbManager usb;
	private final ExecutorService pool = Executors.newCachedThreadPool();
	private final Map<String, Dev> open = new ConcurrentHashMap<>();
	private final Map<String, CompletableFuture<Boolean>> permissionWaits = new ConcurrentHashMap<>();
	private final OkHttpClient http = new OkHttpClient.Builder()
			.connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build();

	static final class UsbError extends Exception {
		final String name;
		UsbError(String name, String message) { super(message); this.name = name; }
	}

	// An open device: its connection, claimed interfaces, and a reader per IN endpoint in use
	private final class Dev {
		final UsbDevice device;
		UsbDeviceConnection conn;
		final Map<Integer, UsbInterface> claimed = new ConcurrentHashMap<>();
		final Map<Integer, UsbInterface> current = new ConcurrentHashMap<>();	// interface number -> its selected alternate
		final Map<Integer, Reader> readers = new ConcurrentHashMap<>();
		final Map<Integer, ExecutorService> writers = new ConcurrentHashMap<>();
		Dev(UsbDevice d) { device = d; }
	}

	// Keeps one bulk IN transfer pending at all times, as the page does with several queued WebUSB transfers: a chip
	// like FTDI's drops bytes when nothing is reading. Each read is handed to the page as one transfer result.
	private final class Reader implements Runnable {
		final Dev dev;
		final UsbEndpoint ep;
		final LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
		volatile boolean stop;
		volatile String error;
		Reader(Dev dev, UsbEndpoint ep) { this.dev = dev; this.ep = ep; }

		@Override public void run() {
			byte[] buf = new byte[Math.max(4096, ep.getMaxPacketSize())];
			boolean ftdi = dev.device.getVendorId() == FTDI;
			while (!stop) {
				UsbDeviceConnection c = dev.conn;
				if (c == null) break;
				int n = c.bulkTransfer(ep, buf, buf.length, READ_POLL_MS);
				if (n < 0) {	// a timeout, or the device went away
					if (!present(dev.device)) { error = "The device was disconnected."; break; }
					continue;
				}
				// an FTDI chip sends its 2 status bytes every few ms while idle: nothing for the page
				if (n == 0 || (ftdi && n <= 2)) continue;
				byte[] chunk = new byte[n];
				System.arraycopy(buf, 0, chunk, 0, n);
				queue.add(chunk);
			}
			stop = true;
			queue.add(new byte[0]);	// wakes a waiting transferIn
		}

		byte[] take() throws Exception {
			for (;;) {
				byte[] b = queue.poll(500, TimeUnit.MILLISECONDS);
				if (b != null && (b.length > 0 || !stop)) return b;
				if (stop && queue.isEmpty()) throw new UsbError(error != null ? "NetworkError" : "AbortError",
						error != null ? error : "The transfer was cancelled.");
			}
		}
	}

	private final BroadcastReceiver receiver = new BroadcastReceiver() {
		@Override public void onReceive(Context ctx, Intent intent) {
			UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
			String action = intent.getAction();
			if (ACTION_PERMISSION.equals(action)) {
				// with no device in the intent (older Androids with an immutable intent), answer every waiter by asking again
				for (Map.Entry<String, CompletableFuture<Boolean>> w : permissionWaits.entrySet()) {
					if (d == null || d.getDeviceName().equals(w.getKey())) {
						UsbDevice now = usb.getDeviceList().get(w.getKey());
						w.getValue().complete(now != null && usb.hasPermission(now));
					}
				}
			} else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action) && d != null) {
				Dev dev = open.remove(d.getDeviceName());
				if (dev != null) closeDev(dev);
				try {
					JSONObject k = new JSONObject().put("key", d.getDeviceName());
					emit("disconnect", k);
				} catch (Exception ignored) { }
			} else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action) && d != null && usb.hasPermission(d)) {
				try { emit("connect", describe(d)); } catch (Exception ignored) { }
			}
		}
	};

	UsbBridge(MainActivity act) {
		this.act = act;
		this.usb = (UsbManager) act.getSystemService(Context.USB_SERVICE);
		IntentFilter f = new IntentFilter();
		f.addAction(ACTION_PERMISSION);
		f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
		f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
		if (Build.VERSION.SDK_INT >= 33) act.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
		else act.registerReceiver(receiver, f);
	}

	void shutdown() {
		try { act.unregisterReceiver(receiver); } catch (Exception ignored) { }
		for (Dev d : open.values()) closeDev(d);
		open.clear();
		pool.shutdownNow();
	}

	@JavascriptInterface
	public void call(final int id, final String op, final String argsJson) {
		pool.execute(() -> {
			try {
				Object r = dispatch(op, new JSONObject(argsJson == null ? "{}" : argsJson));
				answer(id, true, r == null ? "null" : (r instanceof String ? JSONObject.quote((String) r) : r.toString()));
			} catch (Throwable t) {
				Throwable e = t instanceof java.util.concurrent.ExecutionException && t.getCause() != null ? t.getCause() : t;
				if (e instanceof UsbError) answer(id, false, errorJson(((UsbError) e).name, e.getMessage()));
				else if (e instanceof SecurityException) answer(id, false, errorJson("SecurityError", "Access denied."));
				else answer(id, false, errorJson("NetworkError", String.valueOf(e.getMessage())));
			}
		});
	}

	private static String errorJson(String name, String message) {
		try { return new JSONObject().put("name", name).put("message", message == null ? "" : message).toString(); }
		catch (Exception e) { return "{}"; }
	}

	private void answer(int id, boolean ok, String json) {
		final String js = "window.__rfAndroid&&window.__rfAndroid.done(" + id + "," + ok + "," + json + ")";
		act.runOnUiThread(() -> act.web.evaluateJavascript(js, null));
	}

	private void emit(String type, JSONObject info) {
		final String js = "window.__rfAndroid&&window.__rfAndroid.event(" + JSONObject.quote(type) + "," + info + ")";
		act.runOnUiThread(() -> act.web.evaluateJavascript(js, null));
	}

	private Object dispatch(String op, JSONObject a) throws Exception {
		switch (op) {
			case "requestDevice": return requestDevice(a.optJSONArray("filters"));
			case "getDevices": return getDevices();
			case "open": return openDevice(a.getString("key"));
			case "close": return closeDevice(a.getString("key"));
			case "selectConfiguration": return selectConfiguration(dev(a), a.getInt("value"));
			case "claimInterface": return claimInterface(dev(a), a.getInt("n"));
			case "releaseInterface": return releaseInterface(dev(a), a.getInt("n"));
			case "selectAlternateInterface": return selectAlternate(dev(a), a.getInt("n"), a.getInt("alt"));
			case "controlTransfer": return controlTransfer(dev(a), a);
			case "transferIn": return transferIn(dev(a), a.getInt("ep"));
			case "transferOut": return transferOut(dev(a), a.getInt("ep"), Base64.decode(a.optString("data", ""), Base64.DEFAULT));
			case "clearHalt": return clearHalt(dev(a), a.getString("direction"), a.getInt("ep"));
			case "reset": return null;	// Android can't reset a device; the page only uses it as a courtesy
			case "http": return httpRequest(a);
			case "save": return act.saveFile(a.optString("name", "download.bin"), a.optString("mime", ""),
					Base64.decode(a.optString("data", ""), Base64.DEFAULT));
			default: throw new UsbError("NotSupportedError", "Unknown operation " + op);
		}
	}

	// ---- devices ----

	private boolean present(UsbDevice d) { return usb.getDeviceList().containsKey(d.getDeviceName()); }

	private UsbDevice find(String key) throws UsbError {
		UsbDevice d = usb.getDeviceList().get(key);
		if (d == null) throw new UsbError("NotFoundError", "The device was disconnected.");
		return d;
	}

	private Dev dev(JSONObject a) throws Exception {
		Dev d = open.get(a.getString("key"));
		if (d == null || d.conn == null) throw new UsbError("InvalidStateError", "The device must be opened first.");
		return d;
	}

	private static boolean matches(UsbDevice d, JSONObject f) {
		if (f.has("vendorId") && f.optInt("vendorId") != d.getVendorId()) return false;
		if (f.has("productId") && f.optInt("productId") != d.getProductId()) return false;
		if (f.has("serialNumber")) {
			String s = null;
			try { s = d.getSerialNumber(); } catch (SecurityException ignored) { }
			if (!f.optString("serialNumber").equals(s)) return false;
		}
		if (f.has("classCode")) {
			boolean any = d.getDeviceClass() == f.optInt("classCode") && classRest(f, d.getDeviceSubclass(), d.getDeviceProtocol());
			for (int i = 0; !any && i < d.getInterfaceCount(); i++) {
				UsbInterface it = d.getInterface(i);
				any = it.getInterfaceClass() == f.optInt("classCode") && classRest(f, it.getInterfaceSubclass(), it.getInterfaceProtocol());
			}
			return any;
		}
		return true;
	}

	private static boolean classRest(JSONObject f, int sub, int proto) {
		return (!f.has("subclassCode") || f.optInt("subclassCode") == sub) && (!f.has("protocolCode") || f.optInt("protocolCode") == proto);
	}

	private static String label(UsbDevice d) {
		String name = d.getProductName();
		String maker = d.getManufacturerName();
		String s = (name != null && !name.isEmpty() ? name : "USB device");
		if (maker != null && !maker.isEmpty()) s += " — " + maker;
		return s + String.format(Locale.ROOT, "  (%04x:%04x)", d.getVendorId(), d.getProductId());
	}

	private Object requestDevice(JSONArray filters) throws Exception {
		final List<UsbDevice> list = new ArrayList<>();
		for (UsbDevice d : usb.getDeviceList().values()) {
			boolean ok = filters == null || filters.length() == 0;
			for (int i = 0; !ok && i < filters.length(); i++) ok = matches(d, filters.getJSONObject(i));
			if (ok) list.add(d);
		}
		final CompletableFuture<UsbDevice> choice = new CompletableFuture<>();
		act.runOnUiThread(() -> {
			AlertDialog.Builder b = new AlertDialog.Builder(act).setOnCancelListener(x -> choice.complete(null));
			if (list.isEmpty()) {
				b.setTitle("No matching device").setMessage("Plug the radio or programming cable into this phone (a USB OTG "
						+ "adapter may be needed), turn the radio on or put it in update mode, then try again.")
						.setPositiveButton(android.R.string.ok, (x, w) -> choice.complete(null));
			} else {
				String[] names = new String[list.size()];
				for (int i = 0; i < names.length; i++) names[i] = label(list.get(i));
				b.setTitle("Choose the device").setItems(names, (x, w) -> choice.complete(list.get(w)))
						.setNegativeButton(android.R.string.cancel, (x, w) -> choice.complete(null));
			}
			b.show();
		});
		UsbDevice d = choice.get();
		if (d == null) throw new UsbError("NotFoundError", "No device selected.");
		if (!usb.hasPermission(d) && !askPermission(d)) throw new UsbError("SecurityError", "Access denied.");
		return describe(d);
	}

	private boolean askPermission(UsbDevice d) throws Exception {
		CompletableFuture<Boolean> w = new CompletableFuture<>();
		permissionWaits.put(d.getDeviceName(), w);
		try {
			Intent i = new Intent(ACTION_PERMISSION).setPackage(act.getPackageName());
			int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
			PendingIntent pi = PendingIntent.getBroadcast(act, 0, i, flags | PendingIntent.FLAG_UPDATE_CURRENT);
			usb.requestPermission(d, pi);
			return w.get();
		} finally {
			permissionWaits.remove(d.getDeviceName());
		}
	}

	private Object getDevices() throws Exception {
		JSONArray out = new JSONArray();
		for (UsbDevice d : usb.getDeviceList().values()) if (usb.hasPermission(d)) out.put(describe(d));
		return out;
	}

	private JSONObject describe(UsbDevice d) throws Exception {
		JSONObject o = new JSONObject();
		o.put("key", d.getDeviceName());
		o.put("vendorId", d.getVendorId());
		o.put("productId", d.getProductId());
		o.put("deviceClass", d.getDeviceClass());
		o.put("deviceSubclass", d.getDeviceSubclass());
		o.put("deviceProtocol", d.getDeviceProtocol());
		o.put("productName", d.getProductName() == null ? JSONObject.NULL : d.getProductName());
		o.put("manufacturerName", d.getManufacturerName() == null ? JSONObject.NULL : d.getManufacturerName());
		String serial = null;
		try { serial = d.getSerialNumber(); } catch (SecurityException ignored) { }
		o.put("serialNumber", serial == null ? JSONObject.NULL : serial);
		int[] v = version(d.getVersion());	// bcdDevice as "2.00"
		o.put("deviceVersionMajor", v[0]).put("deviceVersionMinor", v[1]).put("deviceVersionSubminor", v[2]);
		o.put("usbVersionMajor", 2).put("usbVersionMinor", 0).put("usbVersionSubminor", 0);

		JSONArray configs = new JSONArray();
		for (int c = 0; c < d.getConfigurationCount(); c++) {
			UsbConfiguration cfg = d.getConfiguration(c);
			Map<Integer, JSONArray> alts = new HashMap<>();
			List<Integer> order = new ArrayList<>();
			for (int i = 0; i < cfg.getInterfaceCount(); i++) {
				UsbInterface it = cfg.getInterface(i);
				if (!alts.containsKey(it.getId())) { alts.put(it.getId(), new JSONArray()); order.add(it.getId()); }
				JSONArray eps = new JSONArray();
				for (int e = 0; e < it.getEndpointCount(); e++) {
					UsbEndpoint ep = it.getEndpoint(e);
					eps.put(new JSONObject().put("endpointNumber", ep.getEndpointNumber())
							.put("direction", ep.getDirection() == UsbConstants.USB_DIR_IN ? "in" : "out")
							.put("type", type(ep.getType())).put("packetSize", ep.getMaxPacketSize()));
				}
				alts.get(it.getId()).put(new JSONObject().put("alternateSetting", it.getAlternateSetting())
						.put("interfaceClass", it.getInterfaceClass()).put("interfaceSubclass", it.getInterfaceSubclass())
						.put("interfaceProtocol", it.getInterfaceProtocol())
						.put("interfaceName", it.getName() == null ? JSONObject.NULL : it.getName()).put("endpoints", eps));
			}
			JSONArray ifaces = new JSONArray();
			for (Integer n : order) ifaces.put(new JSONObject().put("interfaceNumber", n).put("alternates", alts.get(n)));
			configs.put(new JSONObject().put("configurationValue", cfg.getId())
					.put("configurationName", cfg.getName() == null ? JSONObject.NULL : cfg.getName()).put("interfaces", ifaces));
		}
		o.put("configurations", configs);
		return o;
	}

	private static String type(int t) {
		switch (t) {
			case UsbConstants.USB_ENDPOINT_XFER_BULK: return "bulk";
			case UsbConstants.USB_ENDPOINT_XFER_INT: return "interrupt";
			case UsbConstants.USB_ENDPOINT_XFER_ISOC: return "isochronous";
			default: return "control";
		}
	}

	private static int[] version(String s) {
		int[] v = { 0, 0, 0 };
		if (s == null) return v;
		try {
			String[] p = s.split("\\.");
			v[0] = Integer.parseInt(p[0].trim());
			if (p.length > 1 && p[1].length() > 0) v[1] = Character.digit(p[1].charAt(0), 16);
			if (p.length > 1 && p[1].length() > 1) v[2] = Character.digit(p[1].charAt(1), 16);
		} catch (Exception ignored) { }
		return v;
	}

	private Object openDevice(String key) throws Exception {
		UsbDevice d = find(key);
		if (!usb.hasPermission(d)) throw new UsbError("SecurityError", "Access denied.");
		Dev dev = open.get(key);
		if (dev != null && dev.conn != null) return null;
		dev = new Dev(d);
		dev.conn = usb.openDevice(d);
		if (dev.conn == null) throw new UsbError("NetworkError", "Failed to open the device.");
		open.put(key, dev);
		return null;
	}

	private Object closeDevice(String key) {
		Dev dev = open.remove(key);
		if (dev != null) closeDev(dev);
		return null;
	}

	private void closeDev(Dev dev) {
		for (Reader r : dev.readers.values()) r.stop = true;
		dev.readers.clear();
		for (ExecutorService w : dev.writers.values()) w.shutdownNow();
		dev.writers.clear();
		UsbDeviceConnection c = dev.conn;
		dev.conn = null;
		if (c != null) {
			for (UsbInterface it : dev.claimed.values()) { try { c.releaseInterface(it); } catch (Exception ignored) { } }
			c.close();
		}
		dev.claimed.clear();
		dev.current.clear();
	}

	private UsbInterface iface(UsbDevice d, int n, int alt) throws UsbError {
		for (int i = 0; i < d.getInterfaceCount(); i++) {
			UsbInterface it = d.getInterface(i);
			if (it.getId() == n && it.getAlternateSetting() == alt) return it;
		}
		throw new UsbError("NotFoundError", "The interface number provided is not supported by the device in its current configuration.");
	}

	private Object selectConfiguration(Dev dev, int value) throws Exception {
		UsbConfiguration now = dev.device.getConfigurationCount() > 0 ? dev.device.getConfiguration(0) : null;
		if (now != null && now.getId() == value) return null;	// Android has the device configured already
		for (int c = 0; c < dev.device.getConfigurationCount(); c++) {
			UsbConfiguration cfg = dev.device.getConfiguration(c);
			if (cfg.getId() == value) {
				if (!dev.conn.setConfiguration(cfg)) throw new UsbError("NetworkError", "Unable to set device configuration.");
				return null;
			}
		}
		throw new UsbError("NotFoundError", "The configuration value provided is not supported by the device.");
	}

	private Object claimInterface(Dev dev, int n) throws Exception {
		if (dev.claimed.containsKey(n)) return null;
		UsbInterface it = iface(dev.device, n, 0);
		// force: Android's own drivers (cdc_acm) let go of it
		if (!dev.conn.claimInterface(it, true)) throw new UsbError("NetworkError", "Unable to claim interface.");
		dev.claimed.put(n, it);
		dev.current.put(n, it);
		return null;
	}

	private void stopReadersOf(Dev dev, UsbInterface it) {
		for (int e = 0; e < it.getEndpointCount(); e++) {
			UsbEndpoint ep = it.getEndpoint(e);
			if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
				Reader r = dev.readers.remove(ep.getEndpointNumber());
				if (r != null) r.stop = true;
			}
		}
	}

	private Object releaseInterface(Dev dev, int n) throws Exception {
		UsbInterface it = dev.claimed.remove(n);
		if (it == null) return null;
		UsbInterface cur = dev.current.remove(n);
		stopReadersOf(dev, cur != null ? cur : it);
		dev.conn.releaseInterface(it);
		return null;
	}

	private Object selectAlternate(Dev dev, int n, int alt) throws Exception {
		if (!dev.claimed.containsKey(n)) throw new UsbError("InvalidStateError", "The interface must be claimed first.");
		UsbInterface it = iface(dev.device, n, alt);
		UsbInterface was = dev.current.get(n);
		if (was != null) stopReadersOf(dev, was);
		if (!dev.conn.setInterface(it)) throw new UsbError("NetworkError", "Unable to set device interface.");
		dev.current.put(n, it);
		return null;
	}

	private UsbEndpoint endpoint(Dev dev, int number, int dir) throws UsbError {
		for (UsbInterface it : dev.current.values())
			for (int e = 0; e < it.getEndpointCount(); e++) {
				UsbEndpoint ep = it.getEndpoint(e);
				if (ep.getEndpointNumber() == number && ep.getDirection() == dir) return ep;
			}
		throw new UsbError("NotFoundError", "The specified endpoint is not part of a claimed and selected alternate interface.");
	}

	private Object controlTransfer(Dev dev, JSONObject a) throws Exception {
		JSONObject s = a.getJSONObject("setup");
		boolean in = "in".equals(a.optString("dir"));
		int rt = in ? UsbConstants.USB_DIR_IN : UsbConstants.USB_DIR_OUT;
		switch (s.optString("requestType", "standard")) {
			case "class": rt |= UsbConstants.USB_TYPE_CLASS; break;
			case "vendor": rt |= UsbConstants.USB_TYPE_VENDOR; break;
			default: rt |= UsbConstants.USB_TYPE_STANDARD;
		}
		switch (s.optString("recipient", "device")) {
			case "interface": rt |= 0x01; break;
			case "endpoint": rt |= 0x02; break;
			case "other": rt |= 0x03; break;
			default: break;
		}
		int req = s.getInt("request"), value = s.getInt("value"), index = s.getInt("index");
		JSONObject r = new JSONObject();
		if (in) {
			int len = a.optInt("length", 0);
			byte[] buf = new byte[len];
			int n = dev.conn.controlTransfer(rt, req, value, index, buf, len, CONTROL_TIMEOUT_MS);
			if (n < 0) {
				if (!present(dev.device)) throw new UsbError("NetworkError", "A transfer error has occurred.");
				return r.put("status", "stall");
			}
			return r.put("status", "ok").put("data", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP));
		}
		byte[] data = Base64.decode(a.optString("data", ""), Base64.DEFAULT);
		int n = dev.conn.controlTransfer(rt, req, value, index, data.length == 0 ? null : data, data.length, CONTROL_TIMEOUT_MS);
		if (n < 0) {
			if (!present(dev.device)) throw new UsbError("NetworkError", "A transfer error has occurred.");
			return r.put("status", "stall").put("bytesWritten", 0);
		}
		return r.put("status", "ok").put("bytesWritten", n);
	}

	private Object transferIn(Dev dev, int number) throws Exception {
		Reader r = dev.readers.get(number);
		if (r == null || r.stop) {
			synchronized (dev) {
				r = dev.readers.get(number);
				if (r == null || r.stop) {
					r = new Reader(dev, endpoint(dev, number, UsbConstants.USB_DIR_IN));
					dev.readers.put(number, r);
					Thread t = new Thread(r, "usb-in-" + number);
					t.setDaemon(true);
					t.start();
				}
			}
		}
		byte[] b = r.take();
		return new JSONObject().put("status", "ok").put("data", Base64.encodeToString(b, Base64.NO_WRAP));
	}

	private Object transferOut(final Dev dev, int number, final byte[] data) throws Exception {
		final UsbEndpoint ep = endpoint(dev, number, UsbConstants.USB_DIR_OUT);
		ExecutorService w = dev.writers.get(number);
		if (w == null) {
			synchronized (dev) {
				w = dev.writers.get(number);
				if (w == null) { w = Executors.newSingleThreadExecutor(); dev.writers.put(number, w); }
			}
		}
		// one write at a time per endpoint, in the order the page sent them
		return w.submit(() -> {
			int sent = 0;
			while (sent < data.length) {
				int len = Math.min(OUT_CHUNK, data.length - sent);
				UsbDeviceConnection c = dev.conn;
				if (c == null) throw new UsbError("NetworkError", "The device was closed.");
				int n = c.bulkTransfer(ep, data, sent, len, OUT_TIMEOUT_MS);
				if (n < 0) {
					if (!present(dev.device)) throw new UsbError("NetworkError", "A transfer error has occurred.");
					return new JSONObject().put("status", "stall").put("bytesWritten", sent);
				}
				sent += n;
				if (n == 0) break;
			}
			return new JSONObject().put("status", "ok").put("bytesWritten", sent);
		}).get();
	}

	private Object clearHalt(Dev dev, String direction, int number) throws Exception {
		int addr = number | ("in".equals(direction) ? 0x80 : 0);
		// CLEAR_FEATURE(ENDPOINT_HALT) to the endpoint
		int n = dev.conn.controlTransfer(0x02, 0x01, 0, addr, null, 0, CONTROL_TIMEOUT_MS);
		if (n < 0) throw new UsbError("NetworkError", "Unable to clear endpoint.");
		return null;
	}

	// ---- the build service ----

	private Object httpRequest(JSONObject a) throws Exception {
		String url = a.getString("url");
		if (!url.startsWith("https://api.radiofoundry.org/")) throw new UsbError("SecurityError", "Not allowed.");
		String method = a.optString("method", "GET").toUpperCase(Locale.ROOT);
		Request.Builder rb = new Request.Builder().url(url);
		JSONObject h = a.optJSONObject("headers");
		String contentType = null;
		if (h != null) {
			for (Iterator<String> it = h.keys(); it.hasNext(); ) {
				String k = it.next();
				if (k.equalsIgnoreCase("content-type")) contentType = h.getString(k);
				else if (!k.equalsIgnoreCase("origin") && !k.equalsIgnoreCase("referer") && !k.equalsIgnoreCase("host")) rb.header(k, h.getString(k));
			}
		}
		rb.header("Origin", SITE_ORIGIN).header("Referer", SITE_ORIGIN + "/flash/");
		RequestBody body = null;
		if (!a.isNull("body") || method.equals("POST") || method.equals("PUT") || method.equals("PATCH")) {
			byte[] bytes = a.isNull("body") ? new byte[0] : Base64.decode(a.getString("body"), Base64.DEFAULT);
			body = RequestBody.create(bytes, contentType == null ? null : MediaType.parse(contentType));
		}
		if (method.equals("GET") || method.equals("HEAD")) body = null;
		rb.method(method, body);
		try (Response res = http.newCall(rb.build()).execute()) {
			JSONObject out = new JSONObject();
			out.put("status", res.code()).put("statusText", res.message()).put("url", res.request().url().toString());
			JSONArray headers = new JSONArray();
			for (int i = 0; i < res.headers().size(); i++)
				headers.put(new JSONArray().put(res.headers().name(i)).put(res.headers().value(i)));
			out.put("headers", headers);
			ResponseBody rbody = res.body();
			byte[] bytes = rbody == null ? new byte[0] : rbody.bytes();
			out.put("body", Base64.encodeToString(bytes, Base64.NO_WRAP));
			return out;
		}
	}
}
