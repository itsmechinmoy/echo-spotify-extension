# Spotify Extension for Echo

A community-maintained Spotify extension for [Echo](https://github.com/brahmkshatriya/echo), enabling native browsing, playback, and library integration.

---

> [!WARNING]
> ### Legal Notice & Upstream Repository Status
> This repository is a community fork and continuation of the original [`brahmkshatriya/echo-spotify-extension`](https://github.com/brahmkshatriya/echo-spotify-extension), which was removed following a Cease & Desist / DMCA legal notice dated **October 15, 2026**, served by legal counsel **Ashurst Perkins Coie US LLP** on behalf of **Spotify USA Inc.** and **Spotify AB**.
>
> 📄 **[Download the Legal Notice (PDF)](https://files.catbox.moe/t1j29j.pdf)**

### Summary of Legal Claims in the Notice
The notice from Ashurst Perkins Coie LLP alleged:
1. **DMCA § 1201 (Trafficking / DRM Circumvention):** Allegations that third-party playback circumvents Spotify's Digital Rights Management (DRM) and advertising controls.
2. **Trademark Infringement:** Objections under the Lanham Act to the project title ("Spotify on Echo") and usage of the Spotify word mark.
3. **Breach of Spotify User Guidelines:** Claims that reverse-engineering APIs and creating derivative clients violates Spotify's Terms of Service and induces others to breach them.
4. **Copyright Infringement:** Allegations of unauthorized derivative works based on Spotify web and application code.

### Interoperability & Developer Rebuttal
Developers and members of the open-source community have raised important technical and legal distinctions regarding these claims:
- **No User Agreement Breach by Developers:** Developing an interoperability client does not constitute a breach of Spotify's User Guidelines when developers write independent clean-room code without accepting Spotify's end-user agreements. Under established contract law, terms of service bind only parties who have affirmatively agreed to them.
- **Interoperability & Reverse Engineering Exceptions:** Both US law (17 U.S.C. § 1201(f)) and EU law (Directive 2009/24/EC, Article 6) explicitly recognize reverse engineering and protocol analysis strictly for the purpose of achieving interoperability between independently created computer programs as protected fair use.
- **No Proprietary Binaries or DRM Circumvention:** This repository contains **no proprietary Spotify binaries, libraries, or leaked keys**. All former private libraries (`unplayplay.aar`) have been completely purged. Playback relies exclusively on standard Android DRM frameworks (ExoPlayer / Media3 Widevine CDM) where users access streams using their own authenticated session tokens.
- **Nominative Fair Use:** References to "Spotify" are used strictly in a descriptive, nominative capacity to identify the target platform for compatibility purposes, not as a commercial brand or endorsement.

---

## Features

- **Browsing & Discovery:** Search tracks, albums, artists, playlists, featured charts, and user library.
- **Music Videos:** Full support for official canvas and music video streaming with quality selection.
- **Connect Scrobbling:** Real-time state synchronization and listening scrobbles via Spotify Connect protocols.
- **Queue Harvesting:** Seamless background queue sync and track pre-fetching.
- **Clean Architecture:** 100% open-source Kotlin code with no obfuscated binary dependencies or private archives.

---

## Disclaimer

This project is an independent open-source project created strictly for educational, research, and interoperability purposes. It is **not** endorsed, sponsored, affiliated with, or supported by Spotify AB, Spotify USA Inc., or any of their affiliates. All registered trademarks, service marks, and trade names belong to their respective owners.
