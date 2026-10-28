# BB Whatsapp

WhatsApp messaging and a simple file drop-box for a **BlackBerry Q20 Classic (BB10 10.3.x)**, running entirely on your own home network.

BB10 can run Android apps but has no Google Play and no modern WhatsApp. This project fills the gap with three small pieces:

| Piece | Folder | What it is |
|---|---|---|
| **Bridge server** | [`server/`](server) | Node app on a computer at home. It drives a headless Chromium running real WhatsApp Web (you link it by scanning a QR code, exactly like "Linked devices") and exposes a small private REST API. |
| **Android app "BB Whatsapp"** | [`android/`](android) | Plain-Java APK (no Gradle, no AndroidX) that runs in BB10's Android runtime (about Android 4.2/4.3) and talks to the bridge. |
| **Drop-box** | [`dropbox/`](dropbox) | A tiny Python web page for moving files between the phone and the computer over Wi-Fi (USB storage and SMB did not work for us). |

Your main WhatsApp stays on your phone of choice. The Q20 is just another linked device, like WhatsApp Web.

> **Heads-up:** this uses WhatsApp Web through an unofficial library ([whatsapp-web.js](https://github.com/pedroslopez/whatsapp-web.js)). WhatsApp's terms do not permit unofficial clients and accounts can be restricted or banned. Use it for ordinary personal messaging, at your own risk. It is not affiliated with WhatsApp, Meta or BlackBerry.

## What works

- Chat list with profile pictures, Chats / Archived tabs, unread counts
- Search chats (box under the tabs, by name or last message, archived chats included)
- Trackpad friendly: the Chats/Archived tabs can be reached with the cursor, and the **BlackBerry key** opens the menu (the message menu in a chat, the three-dots menu in the chat list)
- Press and hold a message (or put the cursor on it and press the BlackBerry key) to reply to it, edit your own text (within WhatsApp's 15 minutes), forward it to another chat, delete it for you, or delete your own for everyone (within WhatsApp's time limit); send and receive text; photos and stickers shown inline; reply quotes; modern emoji drawn as pictures (Twemoji, bundled) in messages, the chat list and the message box, with a picker that has categories, flags and recently used
- Voice notes: **play** them (the bridge converts Opus to WAV, because Android 4.3 can't play Opus) and **send** them (the phone records plain audio, the bridge encodes Ogg Opus with Chromium's WebCodecs, no ffmpeg)
- Dark theme (three-dots menu, Dark theme / Light theme); the chat list shows its last known contents instantly while it refreshes
- Delivery ticks on your own messages: clock, one tick (sent), two ticks (delivered), blue ticks (read)
- Videos: download and play
- Send photos from the camera or gallery
- Background notifications (polling every 15 s); archived chats stay silent
- Scroll back through older messages ("Load older messages", up to 300)
- Pair by scanning a QR code shown on the bridge's admin page

## What does not (yet)

- Calls, link previews
- Exposure control in the in-app camera: the Q20's Android camera layer reports no exposure range
- Anything away from home: the bridge only accepts private-network addresses (see Security)

## Screenshots

_Screenshots to be added._ <!-- TODO(author): chat list, a conversation, the admin page. Blur or crop names, numbers and photos. -->

## Requirements

- A computer that stays on while you use the phone. Tested on macOS (Apple Silicon). The bridge is plain Node and should run on Linux/Windows; the helper scripts are macOS-only.
- Node 22+ (24 tested), Python 3, and Chromium (installed by `npm install` via Puppeteer).
- The BlackBerry on the same Wi-Fi.

## Setup without AI

1. **Get the tools** (macOS arm64; about 350 MB, project-local, no sudo):
   ```bash
   ./scripts/setup-tools.sh --accept-android-licenses
   ```
   Other platforms: install Node 22+, a JDK 17, and the Android SDK (`platforms;android-17`, `build-tools;34.0.0`), put ZXing core **3.3.0** at `android/libs/core-3.3.0.jar`, and adjust the paths at the top of `android/build.sh`.
2. **Start the bridge**:
   ```bash
   cd server
   PATH=../tools/node/bin:$PATH npm install
   PATH=../tools/node/bin:$PATH node server.js
   ```
   Or let the helper run it as a background process that belongs to no terminal (logs in `logs/`):
   ```bash
   ./scripts/bb.sh start | stop | restart | status [bridge|dropbox]
   ```
   For the drop-box, put your PIN in `dropbox/.pin` (git-ignored) first.
3. **Link WhatsApp**: open <http://localhost:3000/admin> on the same computer and scan the QR with your phone (WhatsApp, Settings, Linked devices). `/admin` only answers on `localhost`.
4. **Build the app**:
   ```bash
   ./android/build.sh --publish     # creates android/build/bbwhatsapp-<version>.apk and copies it to dropbox/shared/
   ```
   The launcher icon is not included in the repo; the build draws a plain placeholder. To use your own, save a PNG as `android/tools/icon-source.png` and run `python3 -I android/tools/make_icon_from_image.py` in `android/`.
5. **Get the APK onto the phone**: start the drop-box and open it in the BlackBerry's browser, then tap the APK to install:
   ```bash
   cd dropbox && python3 server.py --port 8000 --pin <choose-a-pin>
   # phone browser: http://<computer-ip>:8000/?pin=<choose-a-pin>
   ```
6. **Pair**: on the admin page press *Show pairing code*, then in the app tap *Scan QR* (or type the address `http://<computer-ip>:3000` and the 6-digit code). Use `http://`, not `https://`.

Updating the app later: bump `android/VERSION`, run `./android/build.sh --publish`, install the new APK over the old one (same package id and signing key, so data is kept). Keep `android/keys/debug.jks`; a different key means uninstall first.

## Troubleshooting

- **Pairing fails or the app says it can't connect:** use `http://`, never `https://`, and the computer's LAN address (not `localhost`). The phone and computer must be on the same Wi-Fi, and the computer's firewall must allow port 3000.
- **Sideloading the APK:** on the BlackBerry enable *Settings, Security and Privacy, Development Mode* so Android APKs may be installed, then open the APK from the browser download.
- **"Not ready" / no chats:** the bridge is not linked to WhatsApp yet, or is still starting. Check the state on `http://localhost:3000/admin`.
- **Installing over an old version fails:** the signing key changed. Uninstall the old app first (you will pair again).
- **Voice notes silent:** they are converted on the bridge; check the bridge log for errors.

## Setup with AI

Open this folder in an AI coding agent (Claude Code, Codex, Cursor, etc.) and say something like:

> Read AGENTS.md and README.md, then set this project up on my machine. Ask me before each download and before anything that touches my WhatsApp account.

[`AGENTS.md`](AGENTS.md) holds the layout, commands, conventions and the hard-won gotchas an agent needs. The agent can run the same steps as above; you only need to scan the QR codes and install the APK on the phone.

## Security

- **`server/data/` is as sensitive as a password.** It holds the linked WhatsApp session, the message database, cached media and pairing token hashes. It is git-ignored; never commit, sync or share it.
- The API requires a per-device bearer token (stored only as a SHA-256 hash), is limited to private-network client addresses, and `/admin` only answers on loopback. It speaks plain HTTP, so keep it on a network you trust. Do not port-forward it.
- Revoke a phone any time from the admin page. Pairing codes are single-use.
- The drop-box PIN is a convenience lock, not strong security. Use it on your home Wi-Fi only.
- The APK is signed with a throwaway local key generated on your machine (`android/keys/`, git-ignored; password defaults to `android` unless you set `KS_PASS`). Nothing secret depends on it; it only identifies *your* builds for updates.
- The bridge never needs to be reachable from the internet. Do not expose it with port-forwarding, a public tunnel or a cloud host: it has no HTTPS, and anyone who could pair or steal a token could read your messages. The drop-box has no private-network guard at all, so the same applies, and always set `--pin`.
- Build the APK yourself rather than trusting a binary from a stranger. Any APK attached to a release was built from the tagged source with the author's own key; compare it with your own build if in doubt.

## Repository layout

```
server/      WhatsApp bridge (Express, whatsapp-web.js, SQLite) + admin page + library patches
android/     BB Whatsapp app sources, resources, build.sh (aapt2, javac, d8, apksigner; no Gradle)
dropbox/     Wi-Fi file drop-box (Python standard library only)
scripts/     setup-tools.sh
AGENTS.md    Guide for AI agents (also a good developer cheat-sheet)
```

## Credits

**Built by [SaqhyHari](https://github.com/SaqhyHari) together with [Claude](https://claude.com/claude-code)** (Anthropic's AI coding assistant), which wrote most of the code and docs in this repository. Claude is credited as co-author on every commit.

This project also stands on other people's work:

- **[whatsapp-web.js](https://github.com/pedroslopez/whatsapp-web.js)** by Pedro S. Lopez and contributors (Apache-2.0), the library the bridge is built on, and **[Puppeteer](https://pptr.dev)** (Apache-2.0) which drives Chromium.
- **[NovelProfessor/whatsapp-server](https://github.com/NovelProfessor/whatsapp-server)** by Christopher Gomes (ISC), the server for a Nokia J2ME WhatsApp client. Its idea of a WhatsApp-Web-backed REST bridge with a SQLite message store, polled by a tiny old-phone client, inspired this project's architecture. No code was copied; our server was written from scratch with authentication and a private-network guard added.
- **[ZXing](https://github.com/zxing/zxing)** core 3.3.0 by Sean Owen and the ZXing authors (Apache-2.0) for QR scanning in the app.
- **[Twemoji](https://github.com/jdecked/twemoji)** graphics (Copyright 2019 Twitter, Inc and other contributors; maintained by the jdecked fork) licensed [CC-BY 4.0](https://creativecommons.org/licenses/by/4.0/), bundled resized to 48 px in `android/assets/emoji/` so emoji show on Android 4.3. The licence text is in that folder.
- **[Express](https://expressjs.com)** (MIT) and **[node-qrcode](https://github.com/soldair/node-qrcode)** (MIT) in the bridge.
- **[Eclipse Temurin](https://adoptium.net)** JDK 17, **Node.js**, and the **Android command-line tools / SDK** (Google, Android SDK license) are downloaded by the setup script, not redistributed.
- The Q20 has a community of owners keeping BB10 alive; thanks to everyone who documented the Android runtime's quirks.

"WhatsApp" is a trademark of Meta Platforms, Inc.; "BlackBerry" is a trademark of BlackBerry Limited. The launcher artwork used on the author's own phone is not included in this repository for that reason.

## Contributing

Issues and pull requests are welcome, especially test reports from other BB10 devices. See [CONTRIBUTING.md](CONTRIBUTING.md).

## Disclaimer

Provided as is, with no warranty. You are responsible for how you use your WhatsApp account. This project is not endorsed by or affiliated with WhatsApp, Meta, BlackBerry or any library author named above.

## License

[GNU GPL v3.0](LICENSE). Third-party components keep their own licences (see Credits).
