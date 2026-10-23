# 🎧 JARVIS Voice Assistant

🇬🇧 **English** | 🇧🇩 [বাংলা](#-বাংলা)

A **fully free** Android voice assistant built with Kotlin and Google Gemini Live, plus a Windows **PC Agent** (`.exe`) so your phone can work with your computer. This repository is a **playlist** of the project: 11 parts, from Part 1 to the final **Part 11**.

> ⬇️ **Download:** open the **Releases** page for the latest `JARVIS-v….apk` (Android 8.0+) and `JARVIS-PC-Agent.exe` (Windows). Or open **Actions** and download the newest build artifact.

---

## 🇬🇧 English

### 📀 Tracklist

| # | Part | App version | What it adds |
|---|------|-------------|--------------|
| 1-6 | [Part 1](Part%201) … [Part 6](Part%206) | 1.1.5 → 1.8.7 | The original build-up: voice, personas, phone control, memory, vision |
| 7 | [Part 7](Part%207) | 1.8.7 | Complete base version |
| 8 | [Part 8](Part%208) | 1.9.0 | Currency tab, multilingual voice tools, 15 UI languages |
| 9 | [Part 9](Part%209) | 1.10.0 | Web research popup, wake-word fixes, notes, notifications, navigation |
| 10 | [Part 10](Part%2010) | 1.11.0 | Math tab, Jarvis Eyes (front camera), web search fix |
| 11 | [Part 11](Part%2011) | 2.0.0 | ✅ **Final:** Pro Capabilities, soft female Trina, PC Agent `.exe` |

### ✨ Features

**Voice and AI**
- Real-time voice conversation (Gemini Live), hands-free mode, wake word "Hey Jarvis" with a diagnostics screen
- Owner voice lock (offline speaker recognition with Vosk)
- Personalities: Jarvis Pro, **Trina** (soft, cute **female** voice, default user name **Tajim**), Girlfriend, Boyfriend, Companion, Professional, Focus
- Understands and answers in any language; the UI is translated into 15 languages

**Home screen tabs:** Screen share, Memories, Language, **Currency**, **Math**
- 💱 **Currency:** live crypto prices (CoinGecko, about every 30 s) and 160+ fiat currencies with a converter
- 🧮 **Math:** solve any kind of math from text, a gallery picture or a **live camera** picture. Plain calculations are answered instantly on the phone, harder ones step by step
- 👁 **Jarvis Eyes:** switch on the **front camera** so Jarvis can see you and your surroundings while the app is open; screen sharing lets it see the screen too

**Phone control:** open apps, calls, WhatsApp, YouTube, alarms, timers, calendar events, SMS drafts, Wi-Fi/Bluetooth/DND screens, battery and device status, navigation, notification reader, notes, to-dos, expenses, Quick Settings tile

**🔎 Web research popup:** ask Jarvis to search, research or analyse anything; the answer opens as a themed popup with sources (Gemini + Google Search, with a plain-results fallback)

### 🛠 Pro Capabilities (Settings > Pro Capabilities)

| Capability | What it does |
|---|---|
| 👁 **Vision** | Answers questions about a screenshot, a picture, or the camera/screen view |
| 💻 **Coding Agent** | Writes code, edits project files and runs tests: JavaScript on the phone in a safe sandbox, any language on your PC through Phone Link |
| ⏰ **Automation** | Reminders, timers and **scheduled tasks** (e.g. every morning at 8 tell me the gold price) that run by themselves, even after a reboot |
| 🖥 **Phone Link** | Control your PC from the phone over local Wi-Fi: run commands, open things, read and write files in one workspace folder |
| 🔒 **Security** | Never runs destructive commands without your permission. An Allow/Deny card appears before PC commands, file overwrites, deletes and plugin calls; a security log records every decision |
| 📴 **Offline Mode** | With no internet, the wake word still starts a few English voice commands: flashlight, time, date, battery, volume, timer, open an app |
| 🧠 **Smart Planning** | Breaks a big task into small steps and saves them as to-dos |
| 🧩 **Plugin System** | Add new tools without updating the app: a plugin is a small JSON file describing one HTTPS call ([examples](Part%2011/plugins/examples)) |

### 🖥 PC Agent: launch the `.exe`

1. Download **JARVIS-PC-Agent.exe** from **Releases** (or Actions > *Build JARVIS PC Agent (exe)* > artifact `JARVIS-PC-Agent-exe`).
2. **Double-click** it. If Windows Firewall asks, allow **Private networks**.
3. A window prints a **PAIRING CODE** line like `192.168.1.20:8765|AbCd…|a4568f…`.
4. On the phone open **Settings > Pro Capabilities > Phone Link**, paste the whole line, tap **Save**, then **Test**.

Command line options:

```
JARVIS-PC-Agent.exe --port 8765 --root D:\Projects\JarvisWorkspace
JARVIS-PC-Agent.exe --new-token      # new secret, pair the phone again
JARVIS-PC-Agent.exe --unsafe         # also allow dangerous commands (not recommended)
```

No Windows? Run the Python version: `pip install cryptography` then `python "Part 11/pc-agent/jarvis_pc_agent.py"` (details in [pc-agent/README.md](Part%2011/pc-agent/README.md)).

Safety: HTTPS with a self-signed certificate, the phone trusts only the pairing fingerprint, every request needs the secret token, files stay inside the workspace folder, and the worst commands are blocked by the agent itself. Use it on your own local network only.

### ▶️ Build from source
Every part is a separate Android Studio project (Android 8.0+). Pushing to `main` builds the APK with GitHub Actions; pushing a tag such as `v2.0.0` also publishes the APK and the PC Agent `.exe` to **Releases**:

```
git tag v2.0.0
git push origin v2.0.0
```

For local builds run `./gradlew :app:assembleDebug` inside the part folder, then open the app and add your **Gemini API key** in Settings.

### 💸 Is it really free?
The app is **completely free**: no subscription, no hidden charges. The AI runs on Google Gemini, so you need your own **Gemini API key** (free from Google AI Studio; the free tier has daily limits). Fiat rates are updated daily, crypto about every 30 seconds.

### ⚠️ Honest limits
- The front camera works only while the JARVIS app is open on screen (Android blocks hidden camera use). Screen sharing needs your permission each time.
- Wi-Fi/Bluetooth cannot be switched silently by Android; Jarvis opens the right settings screen.
- Offline Mode understands a short list of English commands only.
- Accessibility and screen capture are sensitive permissions. Grant them only if you trust the app.
- The release build uses debug signing. Use your own keystore before distributing.

### 📜 License
Released under the [MIT License](LICENSE). Bundled libraries (Vosk, Three.js, CameraX and others) keep their own licenses.

### 👤 Credits
- **Tajim Uddin**: Architect & Lead Developer (@dev.tajim00)

### 🔗 Links
- Telegram channel: https://t.me/jarvis52360
- Telegram discussion: https://t.me/jarvis_discussion00
- Instagram: https://www.instagram.com/dev.tajim
- YouTube: https://youtube.com/@dev.tajim00

---

## 🇧🇩 বাংলা

**JARVIS** একটি **সম্পূর্ণ ফ্রি** Android voice assistant (Kotlin + Google Gemini Live), সাথে একটি Windows **PC Agent** (`.exe`) যাতে ফোন থেকে কম্পিউটার চালানো যায়। এই repository প্রজেক্টের একটা "playlist": Part 1 থেকে শেষ **Part 11** পর্যন্ত ১১টা ধাপ।

> ⬇️ **ডাউনলোড:** **Releases** পেজ থেকে সর্বশেষ `JARVIS-v….apk` (Android 8.0+) ও `JARVIS-PC-Agent.exe` (Windows) নামাও। অথবা **Actions** থেকে সর্বশেষ build artifact নামাও।

### ✨ ফিচার
- 🎙️ রিয়েল-টাইম ভয়েস কথোপকথন (Gemini Live), hands-free মোড, "Hey Jarvis" wake word (diagnostics স্ক্রিনসহ), owner voice lock
- 🎭 পার্সোনালিটি: Jarvis Pro, **Trina** (নরম, কিউট **নারী কণ্ঠ**, ডিফল্ট ইউজার নাম **Tajim**), Girlfriend, Boyfriend, Companion, Professional, Focus
- 🌍 যেকোনো ভাষা বোঝে ও সেই ভাষায় উত্তর দেয়; UI ১৫টা ভাষায়
- 🏠 Home এর ৫টা tab: Screen, Memories, Language, **Currency** (লাইভ crypto ও ১৬০+ মুদ্রার কনভার্টার), **Math** (লেখা, গ্যালারির ছবি বা **লাইভ ক্যামেরা** থেকে যেকোনো অঙ্ক সমাধান)
- 👁 **Jarvis Eyes:** সামনের ক্যামেরা চালু করলে অ্যাপ খোলা থাকাকালীন জার্ভিস আপনাকে ও চারপাশ দেখে; স্ক্রিন শেয়ারে স্ক্রিনও দেখে
- 🔎 **ওয়েব রিসার্চ popup:** search, research বা analysis বললে থিম-মেলানো popup এ সোর্সসহ উত্তর আসে
- 📱 ফোন নিয়ন্ত্রণ: অ্যাপ খোলা, কল, WhatsApp, YouTube, অ্যালার্ম, টাইমার, ক্যালেন্ডার, SMS, ব্যাটারি/ডিভাইস স্ট্যাটাস, নেভিগেশন, notification পড়া, নোট, to-do, খরচের হিসাব, Quick Settings tile

### 🛠 প্রো ক্ষমতা (Settings > Pro Capabilities)
| ক্ষমতা | কী করে |
|---|---|
| 👁 **Vision** | স্ক্রিনশট, ছবি বা ক্যামেরা/স্ক্রিন দেখে প্রশ্নের উত্তর দেয় |
| 💻 **Coding Agent** | কোড লেখে, প্রজেক্ট ফাইল এডিট করে ও টেস্ট চালায়: ফোনে JavaScript, আর Phone Link দিয়ে পিসিতে যেকোনো ভাষা |
| ⏰ **Automation** | রিমাইন্ডার, টাইমার ও **নির্ধারিত কাজ** (যেমন প্রতিদিন সকাল ৮টায় সোনার দাম বলো), ফোন রিস্টার্টের পরেও চলে |
| 🖥 **Phone Link** | একই ওয়াই-ফাই দিয়ে ফোন থেকে পিসি নিয়ন্ত্রণ: কমান্ড চালানো, কিছু খোলা, ফাইল পড়া ও লেখা |
| 🔒 **Security** | অনুমতি ছাড়া কোনো ক্ষতিকর কমান্ড চালায় না। পিসি কমান্ড, ফাইল ওভাররাইট, ডিলিট ও প্লাগইনের আগে Allow/Deny কার্ড আসে; প্রতিটি সিদ্ধান্ত লগে থাকে |
| 📴 **Offline Mode** | ইন্টারনেট না থাকলেও wake word দিয়ে কিছু ইংরেজি কমান্ড চলে: ফ্ল্যাশলাইট, সময়, তারিখ, ব্যাটারি, ভলিউম, টাইমার, অ্যাপ খোলা |
| 🧠 **Smart Planning** | বড় কাজকে ছোট ছোট ধাপে ভাগ করে to-do তে রাখে |
| 🧩 **Plugin System** | অ্যাপ আপডেট ছাড়াই নতুন টুল যোগ: একটি HTTPS কলের বর্ণনা দেওয়া ছোট JSON ফাইলই প্লাগইন |

### 🖥 PC Agent `.exe` চালু করার নিয়ম
1. **Releases** থেকে **JARVIS-PC-Agent.exe** নামাও (বা Actions থেকে artifact)।
2. **ডাবল-ক্লিক** করো। Windows Firewall জিজ্ঞেস করলে **Private networks** এ Allow দাও।
3. যে উইন্ডো খুলবে তাতে **PAIRING CODE** এর একটা লাইন থাকবে।
4. ফোনে **Settings > Pro Capabilities > Phone Link** এ পুরো লাইনটা paste করে **Save**, তারপর **Test** চাপো।

```
JARVIS-PC-Agent.exe --port 8765 --root D:\Projects\JarvisWorkspace
```
Windows না থাকলে Python দিয়ে: `pip install cryptography` তারপর `python jarvis_pc_agent.py`।

### ⚠️ সীমা
- সামনের ক্যামেরা শুধু JARVIS অ্যাপ খোলা থাকলে চলে (Android লুকিয়ে ক্যামেরা চালাতে দেয় না); স্ক্রিন শেয়ারে প্রতিবার অনুমতি লাগে।
- Wi-Fi/Bluetooth Android নিজে থেকে অন-অফ করতে দেয় না, তাই জার্ভিস সঠিক settings স্ক্রিন খোলে।
- Offline Mode শুধু কিছু ইংরেজি কমান্ড বোঝে।
- AI এর জন্য নিজের **Gemini API key** লাগবে (Google AI Studio থেকে ফ্রি, দৈনিক সীমা আছে)।

### 👤 Credits ও 🔗 Links
**Tajim Uddin** (@dev.tajim00): Architect & Lead Developer. Telegram: https://t.me/jarvis52360 · আলোচনা: https://t.me/jarvis_discussion00 · Instagram: https://www.instagram.com/dev.tajim · YouTube: https://youtube.com/@dev.tajim00

📜 License: [MIT](LICENSE)
