#!/usr/bin/env python3
"""
JARVIS PC Agent  -  lets the JARVIS Android app control this PC over your local Wi-Fi (Phone Link).

 * Runs a small HTTPS server (self-signed certificate) on your local network.
 * The phone must know the secret TOKEN and the certificate FINGERPRINT (printed as a pairing code).
 * Files are only readable/writable inside the WORKSPACE folder; commands run inside it.
 * The phone asks you to Allow every command before it is sent (Security setting in the app).

Usage:
    python jarvis_pc_agent.py                  (or double-click JARVIS-PC-Agent.exe)
    python jarvis_pc_agent.py --port 8765 --root C:\\Users\\me\\JarvisWorkspace
Options:
    --unsafe      also allow commands that are normally blocked (format, rm -rf /, shutdown ...). Not recommended.
    --new-token   create a new secret token (the phone must be paired again)
Requires: Python 3.9+ and, only to create the certificate the first time, the "cryptography" package
          (pip install cryptography). The .exe build already contains it.
"""
import argparse
import hashlib
import hmac
import json
import os
import platform
import re
import secrets
import socket
import ssl
import subprocess
import sys
import time
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

VERSION = "1.0.0"
APP_DIR = Path.home() / ".jarvis_pc_agent"
CERT_FILE = APP_DIR / "cert.pem"
KEY_FILE = APP_DIR / "key.pem"
CONFIG_FILE = APP_DIR / "config.json"
PAIRING_FILE = APP_DIR / "pairing.txt"

MAX_BODY = 1_000_000
MAX_READ = 200_000

# Always blocked unless --unsafe (the phone also asks you first).
BLOCKED = re.compile(
    r"(?i)(\bformat\s+[a-z]:|\brm\s+-rf\s+(/|~|\*)|\bdel\s+/[sq]\s+.*[a-z]:\\|\brd\s+/s\s+/q\s+[a-z]:\\|\bmkfs\b|"
    r"\bdd\s+if=|:\(\)\s*\{|\bshutdown\b|\breboot\b|\bdiskpart\b|\breg\s+delete\s+hk|"
    r"remove-item\s+.*-recurse.*[a-z]:\\|\bcipher\s*/w)"
)

STATE = {"token": "", "root": Path.home() / "JarvisWorkspace", "unsafe": False}


def log(message: str) -> None:
    print(time.strftime("%H:%M:%S ") + message, flush=True)


# ----------------------------------------------------------------------------- setup

def load_token(force_new: bool) -> str:
    APP_DIR.mkdir(parents=True, exist_ok=True)
    if CONFIG_FILE.exists() and not force_new:
        try:
            return json.loads(CONFIG_FILE.read_text(encoding="utf-8"))["token"]
        except Exception:
            pass
    token = secrets.token_urlsafe(18)
    CONFIG_FILE.write_text(json.dumps({"token": token}), encoding="utf-8")
    return token


def ensure_certificate() -> None:
    if CERT_FILE.exists() and KEY_FILE.exists():
        return
    APP_DIR.mkdir(parents=True, exist_ok=True)
    try:
        import datetime
        from cryptography import x509
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import rsa
        from cryptography.x509.oid import NameOID

        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "JARVIS-PC-Agent")])
        now = datetime.datetime.now(datetime.timezone.utc)
        cert = (
            x509.CertificateBuilder()
            .subject_name(name)
            .issuer_name(name)
            .public_key(key.public_key())
            .serial_number(x509.random_serial_number())
            .not_valid_before(now - datetime.timedelta(days=1))
            .not_valid_after(now + datetime.timedelta(days=3650))
            .sign(key, hashes.SHA256())
        )
        KEY_FILE.write_bytes(
            key.private_bytes(
                serialization.Encoding.PEM,
                serialization.PrivateFormat.TraditionalOpenSSL,
                serialization.NoEncryption(),
            )
        )
        CERT_FILE.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
        return
    except ImportError:
        pass
    # Fallback: the openssl command line tool.
    try:
        subprocess.run(
            ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", str(KEY_FILE),
             "-out", str(CERT_FILE), "-days", "3650", "-subj", "/CN=JARVIS-PC-Agent"],
            check=True, capture_output=True,
        )
    except Exception:
        print("Cannot create the certificate. Run:  pip install cryptography   and start the agent again.")
        raise SystemExit(1)


def fingerprint() -> str:
    der = ssl.PEM_cert_to_DER_cert(CERT_FILE.read_text(encoding="ascii"))
    return hashlib.sha256(der).hexdigest()


def lan_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))  # no packet is sent
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return socket.gethostbyname(socket.gethostname())


# ----------------------------------------------------------------------------- helpers

def safe_path(relative: str) -> Path:
    root = STATE["root"].resolve()
    rel = (relative or ".").replace("\\", "/").lstrip("/")
    target = (root / rel).resolve()
    if target != root and root not in target.parents:
        raise ValueError("path is outside the workspace")
    return target


def open_on_pc(target: str) -> str:
    target = (target or "").strip()
    if target.lower().startswith(("https://", "http://")):
        webbrowser.open(target)
        return "opened in the browser"
    if re.fullmatch(r"[A-Za-z0-9 ._-]{1,40}", target) and "/" not in target and "\\" not in target:
        if os.name == "nt":
            subprocess.Popen(["cmd", "/c", "start", "", target])
            return "started " + target
    path = safe_path(target)
    if not path.exists():
        raise ValueError("nothing named that in the workspace")
    if os.name == "nt":
        os.startfile(str(path))  # type: ignore[attr-defined]
    elif sys.platform == "darwin":
        subprocess.Popen(["open", str(path)])
    else:
        subprocess.Popen(["xdg-open", str(path)])
    return "opened " + path.name


# ----------------------------------------------------------------------------- server

class Handler(BaseHTTPRequestHandler):
    server_version = "JarvisPCAgent/" + VERSION

    def log_message(self, fmt, *args):  # quiet default logging
        pass

    def _send(self, code: int, payload: dict) -> None:
        data = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _authorised(self) -> bool:
        supplied = self.headers.get("X-Jarvis-Token", "")
        if hmac.compare_digest(supplied.encode(), STATE["token"].encode()):
            return True
        time.sleep(0.6)  # slow down guessing
        log("rejected a request with a wrong token from " + self.client_address[0])
        self._send(401, {"ok": False, "error": "bad token"})
        return False

    def do_GET(self):
        if self.path == "/ping":
            self._send(200, {"ok": True, "agent": "JARVIS PC Agent", "version": VERSION})
        else:
            self._send(404, {"ok": False, "error": "not found"})

    def do_POST(self):
        if not self._authorised():
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            if length > MAX_BODY:
                self._send(413, {"ok": False, "error": "request too large"})
                return
            body = json.loads(self.rfile.read(length).decode("utf-8") or "{}") if length else {}
            routes = {
                "/info": self.r_info, "/run": self.r_run, "/open": self.r_open,
                "/files/list": self.r_list, "/files/read": self.r_read, "/files/write": self.r_write,
            }
            route = routes.get(self.path)
            if route is None:
                self._send(404, {"ok": False, "error": "not found"})
                return
            self._send(200, route(body))
        except Exception as exc:  # report, never crash the server
            self._send(400, {"ok": False, "error": str(exc)[:300]})

    # -- routes
    def r_info(self, body):
        return {
            "ok": True, "name": socket.gethostname(), "system": platform.platform(),
            "python": platform.python_version(), "workspace": str(STATE["root"]),
            "agent_version": VERSION, "unsafe_mode": STATE["unsafe"],
        }

    def r_run(self, body):
        cmd = str(body.get("cmd", "")).strip()
        if not cmd:
            raise ValueError("empty command")
        if BLOCKED.search(cmd) and not STATE["unsafe"]:
            log("BLOCKED dangerous command: " + cmd)
            raise ValueError("blocked by the agent (start it with --unsafe to allow this kind of command)")
        cwd = safe_path(str(body.get("cwd", "") or "."))
        timeout = max(1, min(int(body.get("timeout", 40)), 120))
        log("RUN  " + cmd + "   (in " + str(cwd) + ")")
        try:
            done = subprocess.run(
                cmd, shell=True, cwd=str(cwd), capture_output=True, text=True,
                timeout=timeout, encoding="utf-8", errors="replace",
            )
            return {"ok": True, "code": done.returncode, "stdout": done.stdout[-8000:], "stderr": done.stderr[-4000:]}
        except subprocess.TimeoutExpired:
            return {"ok": True, "code": 124, "stdout": "", "stderr": "timed out after %d seconds" % timeout}

    def r_open(self, body):
        log("OPEN " + str(body.get("target", "")))
        return {"ok": True, "message": open_on_pc(str(body.get("target", "")))}

    def r_list(self, body):
        folder = safe_path(str(body.get("path", ".")))
        if not folder.is_dir():
            raise ValueError("not a folder")
        items = []
        for entry in sorted(folder.iterdir(), key=lambda p: (p.is_file(), p.name.lower()))[:300]:
            items.append({"name": entry.name, "type": "file" if entry.is_file() else "dir",
                          "size": entry.stat().st_size if entry.is_file() else 0})
        return {"ok": True, "path": str(folder), "items": items}

    def r_read(self, body):
        path = safe_path(str(body.get("path", "")))
        if not path.is_file():
            raise ValueError("file not found")
        text = path.read_text(encoding="utf-8", errors="replace")
        return {"ok": True, "path": str(path), "content": text[:MAX_READ], "truncated": len(text) > MAX_READ}

    def r_write(self, body):
        path = safe_path(str(body.get("path", "")))
        content = str(body.get("content", ""))
        if len(content.encode("utf-8")) > MAX_BODY:
            raise ValueError("content too large")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        log("WRITE " + str(path))
        return {"ok": True, "path": str(path), "bytes": len(content.encode("utf-8"))}


def main() -> None:
    parser = argparse.ArgumentParser(description="JARVIS PC Agent (Phone Link)")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--root", default=str(Path.home() / "JarvisWorkspace"))
    parser.add_argument("--unsafe", action="store_true")
    parser.add_argument("--new-token", action="store_true")
    args = parser.parse_args()

    STATE["root"] = Path(args.root).expanduser()
    STATE["root"].mkdir(parents=True, exist_ok=True)
    STATE["unsafe"] = args.unsafe
    STATE["token"] = load_token(args.new_token)
    ensure_certificate()

    pairing = "%s:%d|%s|%s" % (lan_ip(), args.port, STATE["token"], fingerprint())
    PAIRING_FILE.write_text(pairing, encoding="utf-8")

    print("=" * 66)
    print(" JARVIS PC Agent %s" % VERSION)
    print(" Workspace folder : %s" % STATE["root"])
    print(" Listening on     : https://%s:%d  (same Wi-Fi only)" % (lan_ip(), args.port))
    print()
    print(" PAIRING CODE (copy the whole line into the JARVIS app:")
    print(" Settings > Pro Capabilities > Phone Link > Paste pairing code > Save):")
    print()
    print("   " + pairing)
    print()
    print(" The code is also saved in: %s" % PAIRING_FILE)
    if args.unsafe:
        print(" WARNING: --unsafe is ON (dangerous commands are not blocked by the agent).")
    print(" Keep this window open. Press Ctrl+C to stop.")
    print("=" * 66, flush=True)

    server = ThreadingHTTPServer(("0.0.0.0", args.port), Handler)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(str(CERT_FILE), str(KEY_FILE))
    server.socket = context.wrap_socket(server.socket, server_side=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception as error:  # keep a double-clicked window open so the message can be read
        print("The agent stopped: %s" % error)
        try:
            input("Press Enter to close...")
        except Exception:
            pass
