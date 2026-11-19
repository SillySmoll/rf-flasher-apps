RadioFoundry Flasher for Windows (unofficial desktop wrapper)
=============================================================
Built from github.com/radiofoundry/radiofoundry (MIT), commit 9e9e6961, with Electron 38.0.0.

Run: unzip anywhere and double-click RadioFoundryFlasher.exe. No install needed.
     (Windows SmartScreen may warn because the exe is unsigned: More info > Run anyway.)

Needs internet: firmware images are built by RadioFoundry's build service (api.radiofoundry.org), same as the website.

Drivers:
 - Programming cable (backup/restore/codeplug): its normal COM-port driver (CH340, FTDI or CP210x).
 - Radio bootloader ("Digital Radio in USB mode", 0483:DF11): WinUSB via Zadig, once per PC.
   See Docs > Installing > "Windows: the bootloader driver" inside the app.

When the page asks for a device, a small picker lists the matching ports/devices.
File menu: Installer (Ctrl+1), Codeplug editor (Ctrl+2), Docs (Ctrl+3).
Usage reporting is the website's own; turn it off from "Turn Off Reporting" at the bottom of the page.
