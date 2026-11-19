// RadioFoundry Flasher — desktop wrapper for the RadioFoundry web installer.
// Serves the site built from github.com/radiofoundry/radiofoundry (MIT) from a local folder on 127.0.0.1 and
// gives the page what Chrome would: Web Serial (programming cable) and WebUSB (DFU bootloader), with a picker.
"use strict";
const { app, BrowserWindow, session, dialog, shell, Menu } = require("electron");
const http = require("http");
const fs = require("fs");
const path = require("path");

const SITE = path.join(__dirname, "site");
const PREFERRED_PORT = 48731;	// fixed so the origin (and the page's saved settings) stays the same between runs
const API_HOST = "api.radiofoundry.org";
const SITE_ORIGIN = "https://radiofoundry.org";

const TYPES = { ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".mjs": "text/javascript; charset=utf-8",
	".css": "text/css; charset=utf-8", ".json": "application/json", ".svg": "image/svg+xml", ".png": "image/png",
	".jpg": "image/jpeg", ".webp": "image/webp", ".ico": "image/x-icon", ".woff2": "font/woff2", ".woff": "font/woff",
	".ttf": "font/ttf", ".bin": "application/octet-stream", ".zip": "application/zip", ".txt": "text/plain; charset=utf-8",
	".xml": "application/xml", ".webmanifest": "application/manifest+json", ".wasm": "application/wasm" };

let origin = "";

function serve(req, res) {
	let p;
	try { p = decodeURIComponent(new URL(req.url, "http://x").pathname); } catch { res.writeHead(400).end(); return; }
	let file = path.normalize(path.join(SITE, p));
	if (!file.startsWith(SITE)) { res.writeHead(403).end(); return; }
	try { if (fs.statSync(file).isDirectory()) file = path.join(file, "index.html"); } catch { /* not found below */ }
	fs.readFile(file, (err, data) => {
		if (err) {
			fs.readFile(path.join(SITE, "404.html"), (e2, nf) => res.writeHead(404, { "Content-Type": TYPES[".html"] }).end(e2 ? "Not found" : nf));
			return;
		}
		res.writeHead(200, { "Content-Type": TYPES[path.extname(file).toLowerCase()] || "application/octet-stream", "Cache-Control": "no-cache" });
		res.end(data);
	});
}

function listen(port) {
	return new Promise((ok, fail) => {
		const srv = http.createServer(serve);
		srv.once("error", fail);
		srv.listen(port, "127.0.0.1", () => ok(srv.address().port));
	});
}

const ours = (url) => { try { return new URL(url).origin === origin; } catch { return false; } };

// One picker for both kinds of device: the page asked for a cable (Web Serial) or the bootloader (WebUSB)
async function pick(win, title, items, label) {
	if (!items.length) {
		await dialog.showMessageBox(win, { type: "warning", title, message: "No matching device found.",
			detail: "Check the cable is plugged in and the radio is on (or in update mode), then try again.\n\n" +
				"Windows: a programming cable needs its COM-port driver (CH340/FTDI/CP210x); the radio's bootloader " +
				"(0483:DF11) needs the WinUSB driver from Zadig — see the RadioFoundry install guide." });
		return -1;
	}
	const r = await dialog.showMessageBox(win, { type: "question", title, message: title,
		buttons: [...items.map(label), "Cancel"], cancelId: items.length, defaultId: 0, noLink: true });
	return r.response < items.length ? r.response : -1;
}

const hex = (n) => (n == null ? "????" : Number(n).toString(16).padStart(4, "0"));

function setupSession(win) {
	const ses = win.webContents.session;

	ses.setPermissionCheckHandler((_wc, permission, requestingOrigin) =>
		ours(requestingOrigin) || requestingOrigin === origin ? true : permission === "clipboard-sanitized-write");
	ses.setPermissionRequestHandler((wc, _permission, cb) => cb(ours(wc.getURL())));
	ses.setDevicePermissionHandler((d) => (d.deviceType === "usb" || d.deviceType === "serial") && d.origin === origin);
	ses.setUSBProtectedClassesHandler((d) => d.protectedClasses.filter((c) => c === "audio" || c === "video"));

	ses.on("select-serial-port", async (event, portList, _wc, callback) => {
		event.preventDefault();
		const i = await pick(win, "Choose the programming cable", portList, (p) =>
			`${p.displayName || p.portName || "Serial port"}${p.portName && p.displayName ? " (" + p.portName + ")" : ""}  ${hex(p.vendorId)}:${hex(p.productId)}`);
		callback(i < 0 ? "" : portList[i].portId);
	});

	ses.on("select-usb-device", async (event, details, callback) => {
		event.preventDefault();
		const list = details.deviceList || [];
		const i = await pick(win, "Choose the radio", list, (d) =>
			`${d.productName || "USB device"}${d.manufacturerName ? " — " + d.manufacturerName : ""}  ${hex(d.vendorId)}:${hex(d.productId)}`);
		callback(i < 0 ? undefined : list[i].deviceId);
	});

	// The build service expects the web site's origin; the page here runs on 127.0.0.1, so its requests go out with the
	// site's origin and the answers are let back in to this page (what a browser on radiofoundry.org would see).
	const filter = { urls: [`https://${API_HOST}/*`] };
	ses.webRequest.onBeforeSendHeaders(filter, (d, cb) => {
		const h = d.requestHeaders;
		h.Origin = SITE_ORIGIN;
		h.Referer = SITE_ORIGIN + "/flash/";
		cb({ requestHeaders: h });
	});
	ses.webRequest.onHeadersReceived(filter, (d, cb) => {
		const h = {};
		for (const [k, v] of Object.entries(d.responseHeaders || {}))
			if (!/^access-control-allow-(origin|credentials|headers|methods)$/i.test(k)) h[k] = v;
		h["Access-Control-Allow-Origin"] = [origin];
		h["Access-Control-Allow-Headers"] = ["Content-Type"];
		h["Access-Control-Allow-Methods"] = ["GET, POST, PATCH, PUT, DELETE, OPTIONS"];
		const preflight = d.method === "OPTIONS";
		cb({ responseHeaders: h, statusLine: preflight ? "HTTP/1.1 204 No Content" : undefined });
	});

	// Backups and saved files: always ask where to put them
	ses.on("will-download", (_e, item) => {
		item.setSaveDialogOptions({ title: "Save", defaultPath: path.join(app.getPath("documents"), item.getFilename()) });
	});
}

function createWindow(port) {
	const win = new BrowserWindow({
		width: 1200, height: 900, minWidth: 420, minHeight: 500, title: "RadioFoundry Flasher",
		icon: path.join(__dirname, "icon.png"), autoHideMenuBar: true, backgroundColor: "#121417",
		webPreferences: { contextIsolation: true, sandbox: true, nodeIntegration: false, spellcheck: false },
	});
	setupSession(win);
	// links off the local site open in the user's browser
	win.webContents.setWindowOpenHandler(({ url }) => {
		if (ours(url)) return { action: "allow" };
		if (/^https?:/i.test(url)) shell.openExternal(url);
		return { action: "deny" };
	});
	win.webContents.on("will-navigate", (e, url) => {
		if (!ours(url)) { e.preventDefault(); if (/^https?:/i.test(url)) shell.openExternal(url); }
	});
	win.webContents.on("page-title-updated", (e) => e.preventDefault());
	win.loadURL(`${origin}/flash/`);
	return win;
}

function menu() {
	const go = (p) => () => BrowserWindow.getFocusedWindow()?.loadURL(origin + p);
	Menu.setApplicationMenu(Menu.buildFromTemplate([
		{ label: "&File", submenu: [{ label: "Installer", accelerator: "CmdOrCtrl+1", click: go("/flash/") },
			{ label: "Codeplug editor", accelerator: "CmdOrCtrl+2", click: go("/codeplug/") },
			{ label: "Docs", accelerator: "CmdOrCtrl+3", click: go("/docs/") },
			{ type: "separator" }, { role: "quit" }] },
		{ label: "&View", submenu: [{ role: "reload" }, { role: "forceReload" }, { role: "toggleDevTools" }, { type: "separator" },
			{ role: "resetZoom" }, { role: "zoomIn" }, { role: "zoomOut" }, { type: "separator" }, { role: "togglefullscreen" }] },
		{ label: "&Help", submenu: [
			{ label: "RadioFoundry on GitHub", click: () => shell.openExternal("https://github.com/radiofoundry/radiofoundry") },
			{ label: "radiofoundry.org", click: () => shell.openExternal("https://radiofoundry.org/") }] },
	]));
}

if (!app.requestSingleInstanceLock()) app.quit();
else {
	app.on("second-instance", () => { const w = BrowserWindow.getAllWindows()[0]; if (w) { if (w.isMinimized()) w.restore(); w.focus(); } });
	app.whenReady().then(async () => {
		let port;
		try { port = await listen(PREFERRED_PORT); } catch { port = await listen(0); }
		origin = `http://127.0.0.1:${port}`;
		menu();
		createWindow(port);
	});
	app.on("window-all-closed", () => app.quit());
}
