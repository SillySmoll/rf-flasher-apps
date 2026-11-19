// RadioFoundry Flasher for Android: gives the installer page what Chrome on Android gives it.
//  - navigator.usb (WebUSB) on top of Android's USB host API, through the RFBridge Java object. The page's own
//    WebUSB serial driver (usbserial.js: CDC-ACM and FTDI) and its DFU code then run unchanged.
//  - fetch() to the RadioFoundry build service goes through the app, so it isn't blocked as a cross-origin request.
//  - <a download> of a blob (backups, codeplugs) opens Android's "save as" screen.
(() => {
	"use strict";
	if (window.__rfAndroid || !window.RFBridge) return;
	const B = window.RFBridge;
	let seq = 0;
	const waiting = new Map();

	const toB64 = (u8) => {
		let s = "";
		for (let i = 0; i < u8.length; i += 0x8000) s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
		return btoa(s);
	};
	const fromB64 = (s) => {
		const bin = atob(s || "");
		const u8 = new Uint8Array(bin.length);
		for (let i = 0; i < bin.length; i++) u8[i] = bin.charCodeAt(i);
		return u8;
	};
	const bytesOf = (data) => {
		if (data == null) return new Uint8Array(0);
		if (data instanceof Uint8Array) return data;
		if (ArrayBuffer.isView(data)) return new Uint8Array(data.buffer, data.byteOffset, data.byteLength);
		if (data instanceof ArrayBuffer) return new Uint8Array(data);
		return new TextEncoder().encode(String(data));
	};
	const call = (op, args) => new Promise((res, rej) => {
		const id = ++seq;
		waiting.set(id, { res, rej });
		B.call(id, op, JSON.stringify(args || {}));
	});

	// ---- WebUSB ----
	const devices = new Map();	// key -> USBDevice, one object per device as in Chrome

	class USBDevice {
		constructor(info) { this._key = info.key; this.opened = false; this._update(info); }
		_update(i) {
			for (const k of ["vendorId", "productId", "deviceClass", "deviceSubclass", "deviceProtocol", "productName",
				"manufacturerName", "serialNumber", "deviceVersionMajor", "deviceVersionMinor", "deviceVersionSubminor",
				"usbVersionMajor", "usbVersionMinor", "usbVersionSubminor"]) this[k] = i[k] ?? (typeof i[k] === "string" ? "" : i[k]);
			this.configurations = (i.configurations || []).map((c) => ({
				configurationValue: c.configurationValue, configurationName: c.configurationName || null,
				interfaces: c.interfaces.map((itf) => {
					const alternates = itf.alternates.map((a) => ({ ...a, endpoints: a.endpoints.map((e) => ({ ...e })) }));
					return { interfaceNumber: itf.interfaceNumber, alternates, alternate: alternates[0], claimed: false };
				}),
			}));
			this.configuration = this.configurations[0] || null;
		}
		_iface(n) { return this.configuration?.interfaces.find((i) => i.interfaceNumber === n); }
		async open() { await call("open", { key: this._key }); this.opened = true; }
		async close() {
			await call("close", { key: this._key });
			this.opened = false;
			for (const i of this.configuration?.interfaces || []) i.claimed = false;
		}
		async forget() { try { await this.close(); } catch { /* gone */ } }
		async selectConfiguration(value) {
			await call("selectConfiguration", { key: this._key, value });
			this.configuration = this.configurations.find((c) => c.configurationValue === value) || this.configuration;
		}
		async claimInterface(n) { await call("claimInterface", { key: this._key, n }); const i = this._iface(n); if (i) i.claimed = true; }
		async releaseInterface(n) { await call("releaseInterface", { key: this._key, n }); const i = this._iface(n); if (i) i.claimed = false; }
		async selectAlternateInterface(n, alt) {
			await call("selectAlternateInterface", { key: this._key, n, alt });
			const i = this._iface(n);
			if (i) i.alternate = i.alternates.find((a) => a.alternateSetting === alt) || i.alternate;
		}
		async controlTransferIn(setup, length) {
			const r = await call("controlTransfer", { key: this._key, setup, dir: "in", length });
			return { status: r.status, data: r.status === "ok" ? new DataView(fromB64(r.data).buffer) : undefined };
		}
		async controlTransferOut(setup, data) {
			const r = await call("controlTransfer", { key: this._key, setup, dir: "out", data: toB64(bytesOf(data)) });
			return { status: r.status, bytesWritten: r.bytesWritten };
		}
		async transferIn(endpointNumber, length) {
			const r = await call("transferIn", { key: this._key, ep: endpointNumber, length });
			return { status: r.status, data: new DataView(fromB64(r.data).buffer) };
		}
		async transferOut(endpointNumber, data) {
			const r = await call("transferOut", { key: this._key, ep: endpointNumber, data: toB64(bytesOf(data)) });
			return { status: r.status, bytesWritten: r.bytesWritten };
		}
		async clearHalt(direction, endpointNumber) { await call("clearHalt", { key: this._key, direction, ep: endpointNumber }); }
		async reset() { await call("reset", { key: this._key }); }
		async isochronousTransferIn() { throw new DOMException("Isochronous transfers are not supported.", "NotSupportedError"); }
		async isochronousTransferOut() { throw new DOMException("Isochronous transfers are not supported.", "NotSupportedError"); }
	}

	const deviceFor = (info) => {
		let d = devices.get(info.key);
		if (d) d._update(info); else devices.set(info.key, d = new USBDevice(info));
		return d;
	};

	class USB extends EventTarget {
		constructor() { super(); this.onconnect = null; this.ondisconnect = null; }
		async requestDevice(options = {}) {
			if (!options || !Array.isArray(options.filters)) throw new TypeError("requestDevice needs a filters list.");
			return deviceFor(await call("requestDevice", { filters: options.filters }));
		}
		async getDevices() { return (await call("getDevices", {})).map(deviceFor); }
	}
	const usb = new USB();
	try { Object.defineProperty(Navigator.prototype, "usb", { get: () => usb, configurable: true }); }
	catch { Object.defineProperty(navigator, "usb", { value: usb, configurable: true }); }
	window.USBDevice = window.USBDevice || USBDevice;

	const fire = (type, device) => {
		const e = new Event(type);
		e.device = device;
		usb.dispatchEvent(e);
		const h = usb["on" + type];
		if (typeof h === "function") h.call(usb, e);
	};

	// ---- fetch to the build service ----
	const realFetch = window.fetch.bind(window);
	const PROXIED = new Set(["api.radiofoundry.org"]);
	window.fetch = async (input, init = {}) => {
		const req = input instanceof Request ? input : null;
		let url;
		try { url = new URL(req ? req.url : String(input), location.href); } catch { return realFetch(input, init); }
		if (!PROXIED.has(url.host)) return realFetch(input, init);
		const method = String(init.method || req?.method || "GET").toUpperCase();
		const headers = {};
		new Headers(init.headers || req?.headers || {}).forEach((v, k) => { headers[k] = v; });
		let body = init.body;
		if (body === undefined && req && method !== "GET" && method !== "HEAD") body = await req.arrayBuffer();
		let b64 = null;
		if (body != null) {
			if (body instanceof Blob) body = await body.arrayBuffer();
			else if (body instanceof URLSearchParams) { body = body.toString(); headers["content-type"] ||= "application/x-www-form-urlencoded;charset=UTF-8"; }
			else if (body instanceof FormData) throw new TypeError("FormData bodies are not supported here.");
			b64 = toB64(bytesOf(body));
		}
		const signal = init.signal || req?.signal;
		const abort = () => signal.reason || new DOMException("The operation was aborted.", "AbortError");
		if (signal?.aborted) throw abort();
		let r;
		try {
			const p = call("http", { url: url.href, method, headers, body: b64 });
			r = signal ? await Promise.race([p, new Promise((_, rej) => signal.addEventListener("abort", () => rej(abort()), { once: true }))]) : await p;
		} catch (e) {
			if (e?.name === "AbortError" || e?.name === "TimeoutError") throw e;
			throw new TypeError("Failed to fetch");
		}
		const empty = method === "HEAD" || r.status === 204 || r.status === 205 || r.status === 304;
		const res = new Response(empty ? null : fromB64(r.body), { status: r.status, statusText: r.statusText || "", headers: r.headers });
		Object.defineProperty(res, "url", { value: r.url || url.href });
		return res;
	};

	// ---- downloads ----
	const realClick = HTMLAnchorElement.prototype.click;
	HTMLAnchorElement.prototype.click = function () {
		const href = this.href || "";
		if (this.hasAttribute("download") && (href.startsWith("blob:") || href.startsWith("data:"))) {
			const name = this.getAttribute("download") || "download.bin";
			realFetch(href).then((r) => r.blob()).then(async (b) =>
				call("save", { name, mime: b.type || "application/octet-stream", data: toB64(new Uint8Array(await b.arrayBuffer())) }))
				.catch((e) => console.error("save failed", e));
			return;
		}
		return realClick.call(this);
	};
	document.addEventListener("click", (e) => {
		const a = e.target?.closest?.("a[download]");
		if (!a || !(a.href.startsWith("blob:") || a.href.startsWith("data:"))) return;
		e.preventDefault();
		a.click();
	}, true);

	window.__rfAndroid = {
		done(id, ok, value) {
			const w = waiting.get(id);
			if (!w) return;
			waiting.delete(id);
			if (ok) w.res(value);
			else w.rej(new DOMException(value?.message || "Error", value?.name || "NetworkError"));
		},
		event(type, info) {
			if (type === "disconnect") {
				const d = devices.get(info.key);
				if (d) { d.opened = false; fire("disconnect", d); }
			} else if (type === "connect") fire("connect", deviceFor(info));
		},
	};
})();
