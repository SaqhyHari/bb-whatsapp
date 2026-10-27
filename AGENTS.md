# AGENTS.md

Guide for AI coding agents (and a cheat-sheet for humans). Read [README.md](README.md) first for what the project is.

## Ground rules

- **Ask the user before each download** (tools, SDK pieces, libraries). `scripts/setup-tools.sh` downloads ~350 MB; run it only after they agree, and only with `--accept-android-licenses` if they have accepted Google's SDK license.
- **Never send a WhatsApp message to a real contact to test.** Use the user's own "(You)" chat and say you are doing so.
- **Never commit or print secrets or personal data**: `server/data/` (WhatsApp session, DB, media), bearer tokens, pairing codes, `android/keys/`, phone numbers, real LAN IPs, contact names, photos in `dropbox/shared/`. Use placeholders (`192.168.1.50`, `<choose-a-pin>`) in docs and examples. Check `git status` before committing; `.gitignore` already covers these.
- Keep the bridge **home-LAN only**. Don't weaken the private-IP guard, token auth, or the loopback-only `/admin` without the user asking.
- Every APK file name carries its version: `bbwhatsapp-<x.y.z>.apk`. Bump `android/VERSION` for each build the user will install.
- Older APKs go to `dropbox/shared/old-apks/`; only the latest stays in `dropbox/shared/`.
- Don't claim something works on the phone unless you saw it. State what was built vs. verified.

## Setup (what an agent should do)

1. `./scripts/setup-tools.sh --accept-android-licenses` (macOS arm64; ask first). Else see README, Other platforms.
2. `cd server && PATH=../tools/node/bin:$PATH npm install`, then `PATH=../tools/node/bin:$PATH node server.js` (run in the background; log to a scratch file).
3. The user scans the WhatsApp QR at `http://localhost:3000/admin` (agents cannot do this).
4. `./android/build.sh --publish`, then `cd dropbox && python3 server.py --port 8000 --pin <pin>` so the phone can download the APK from `http://<lan-ip>:8000/?pin=<pin>`.
5. The user pairs the phone: admin page, "Show pairing code", app "Scan QR".

## Layout and entry points

- `server/server.js`: whole bridge. Endpoints: `POST /api/pair`, `GET /api/ping|status|chats|updates`, `GET/POST /api/chats/:id/messages`, `POST /api/chats/:id/photo` (raw JPEG/PNG body, `?caption=`), `GET /api/media/:id[?fmt=wav]`, `GET /api/avatar/:chatId`; loopback-only `/admin`, `/admin/state`, `POST /admin/pair-code`, `DELETE /admin/devices/:id`.
- `server/patches/apply.js`: idempotent edits to `node_modules/whatsapp-web.js`, run on every start (see gotchas). Re-run `npm install` and the server re-applies them.
- `server/admin.html`: WhatsApp link QR, pairing code and QR, device list.
- `android/src/com/hari/bbwa/`: `ChatListActivity`, `ChatActivity`, `PollService` (15 s polling, notifications), `CaptureActivity` (camera), `PickImageActivity`, `ScanActivity` (QR), `Api` (HTTP client), `Media`/`Avatars` (caches), `UI` (helpers, no layouts XML).
- `android/build.sh`: aapt2 compile/link, javac `-source 8` against `android-17`, d8 `--min-api 16`, zipalign, apksigner **v1 only**. Reads `android/VERSION`.
- `dropbox/server.py`: stdlib file server with PIN, thumbnails via macOS `sips`.

## Gotchas learned the hard way

**Android side (BB10 runs roughly Android 4.2/4.3, API 17)**
- No Gradle, no AndroidX, no lambdas needing newer APIs; Java 8 source but only API 17 classes. `java.nio.charset.StandardCharsets` is API 19 and crashes on the device: that is why ZXing is pinned to **3.3.0** (3.5.x references it).
- Package id `com.hari.bbwa` and the `bbchat://` pairing scheme are intentionally unchanged so new APKs install over old ones with the same signing key. Don't rename them.
- Opus audio is unsupported on the device; voice notes are converted server-side.
- The camera (`android.hardware.Camera`) reports no exposure-compensation range (min = max = 0) and ignores metering areas; exposure control was tried and abandoned. Focus areas work.
- Photo storage paths in BB10's runtime vary; `PickImageActivity` scans many roots and shows a "Looked in:" diagnostic when empty. `ACTION_GET_CONTENT` also works.
- The "complete setup" nag that buzzes the phone is a BB10 OS matter, parked.
- Error text shown to the user comes from `Api.why()`; keep it truthful (don't map every failure to "cannot reach the server").

**Bridge side (whatsapp-web.js 1.34.7 vs current WhatsApp Web)**
- Message keys expose `$1` instead of `_serialized`; `apply.js` adds fallbacks. Media download needs an explicit `mimetype`. Outgoing media must not spread the media model's own properties (`parent`, `collection`, `__x_*`).
- `fetchMessages` is not chronological: the server over-fetches (limit x3, max 200), sorts by timestamp and keeps the newest N.
- Drop messages whose `viewMode === 'HIDDEN'` (internal, type `unknown`) and type `album`. **Do not filter on `invis`**: real call logs and messages carry it.
- Use `node:sqlite` (Node 22+). Data lives in `server/data/` (`DATA_DIR` env to move it). Env: `PORT` (3000), `HOST` (0.0.0.0).
- Tokens are bearer tokens stored as SHA-256; pairing codes are 6 digits, short-lived.

## Verify before claiming done

- Bridge: `curl http://localhost:3000/api/ping` returns `{"ok":true}`; no stack traces in the log.
- App: `./android/build.sh` ends with `Verified using v1 scheme ... true` and prints the versioned APK path.
- Anything that needs the phone (install, camera, notifications) is the user's to confirm: say it is untested until they do.

## Ideas not done yet

Sending voice notes (WhatsApp needs Ogg Opus), scroll-back for older messages, link previews, silencing muted chats like archived ones, emoji image fallback (Twemoji) if glyphs don't render, remote access away from home, an always-on host.
