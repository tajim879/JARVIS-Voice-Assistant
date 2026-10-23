# JARVIS PC Agent (Phone Link)

Lets the JARVIS Android app control this PC over your **local Wi-Fi**: run commands (for example tests),
open websites and files, and read/write files inside one workspace folder.

## Launch (Windows .exe)
1. Download **JARVIS-PC-Agent.exe** from the repository's *Releases* page (or the *Actions* artifact).
2. Double-click it. If Windows Firewall asks, allow **Private networks**.
3. A window shows a **PAIRING CODE** line, for example `192.168.1.20:8765|AbCd...|a4568f...`.
4. On the phone: **Settings > Pro Capabilities > Phone Link**, paste the whole line, tap **Save**, then **Test**.

Command line options:

    JARVIS-PC-Agent.exe --port 8765 --root D:\Projects\JarvisWorkspace
    JARVIS-PC-Agent.exe --new-token      (create a new secret; pair the phone again)
    JARVIS-PC-Agent.exe --unsafe         (also allow dangerous commands; not recommended)

## Launch from Python (Windows, Linux, macOS)
    pip install cryptography
    python jarvis_pc_agent.py
or run `run_agent.bat` (Windows) / `./run_agent.sh` (Linux, macOS).

## How it stays safe
* HTTPS with a self-signed certificate. The phone trusts **only** the certificate fingerprint from the pairing code,
  and every request needs the secret token.
* Files are limited to the workspace folder (default `~/JarvisWorkspace`); `..` escapes are refused.
* Commands run inside the workspace. Format, `rm -rf /`, shutdown and similar commands are blocked unless `--unsafe`.
* The phone asks you to **Allow** each command before sending it (Security setting in the app).
* Works on the local network only. Do not expose the port to the internet.

## Say it to Jarvis
"Run the tests on my PC", "write hello.py on the PC", "open github.com on my PC", "is my PC connected?"
