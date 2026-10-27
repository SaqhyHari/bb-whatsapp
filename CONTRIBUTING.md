# Contributing

Thanks for looking. This is a small hobby project for old BlackBerry hardware.

- **Bug reports:** say your device and OS version, the app version (`android/VERSION`), bridge OS and Node version, and what you did. Remove phone numbers, names, tokens and IP addresses from logs and screenshots.
- **Constraints:** the app targets API 17 with no AndroidX or Gradle; read the "Gotchas" in [AGENTS.md](AGENTS.md) first. The bridge stays home-LAN only; changes that weaken the private-IP guard, token auth or loopback-only `/admin` will not be accepted.
- **Never include** real chats, `server/data/`, keystores or WhatsApp artwork in an issue or pull request.
- **Pull requests:** keep them small, explain what you tested and on which device, and note AI assistance if used.
