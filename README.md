# Spotify Extension for Echo

A community-maintained Spotify extension for [Echo](https://github.com/brahmkshatriya/echo), enabling native browsing, playback, and library integration.

---

> [!WARNING]
> ### Legal Notice & Upstream Repository Status
> This repository is an independent community fork and preservation of [`brahmkshatriya/echo-spotify-extension`](https://github.com/brahmkshatriya/echo-spotify-extension), which was removed following a Cease & Desist demand dated **October 15, 2026**, served by legal counsel **Ashurst Perkins Coie US LLP** on behalf of **Spotify USA Inc.** and **Spotify AB**.
>
> 📄 **[Download Upstream Cease & Desist Notice (PDF)](https://files.catbox.moe/t1j29j.pdf)**

### Summary of Claims in Upstream Legal Notice
The demand letter from Ashurst Perkins Coie LLP alleged:
1. **DMCA § 1201 (Trafficking / Circumvention):** Allegations that third-party streaming circumvents Spotify's Digital Rights Management (DRM) and advertising controls.
2. **Trademark Infringement:** Claims under the Lanham Act (15 U.S.C. §§ 1114, 1125) regarding the use of the "Spotify" mark in "Spotify on Echo".
3. **Breach of Spotify User Guidelines:** Claims that reverse engineering APIs and building alternative clients violates Spotify's Terms of Service and induces others to breach them.
4. **Copyright Infringement:** Allegations of unauthorized derivative works under 17 U.S.C. § 501.

---

### Legal & Technical Framework for Interoperability

Developers, legal scholars, and digital rights organizations ([EFF Coders' Rights Project](https://www.eff.org/issues/coders/reverse-engineering-faq)) have long established clear statutory protections and judicial precedents governing interoperable software:

#### 1. Contract Law: Absence of Privity & Unenforceability of Terms against Non-Signatories
* **Legal Authorities:** [*Specht v. Netscape Communications Corp.*, 306 F.3d 17 (2d Cir. 2002)](https://casetext.com/case/specht-v-netscape-communications-corp); [*Nguyen v. Barnes & Noble, Inc.*, 749 F.3d 899 (9th Cir. 2014)](https://casetext.com/case/nguyen-v-barnes-noble-inc); *DVD Copy Control Ass'n v. Bunner*, 31 Cal. 4th 864, 901 n.5 (Moreno, J., concurring).
* **Application:** Contractual terms (EULAs, Terms of Service, or User Guidelines) require mutual assent and privity of contract. Independent developers, protocol researchers, and open-source contributors who have not registered an account or affirmatively agreed to Spotify’s terms cannot be legally bound by them. As recognized in *Bunner*, private consumer form contracts cannot redefine statutory rights or restrict non-signatory third parties from analyzing software functionality.

#### 2. Reverse Engineering for Interoperability is Protected Fair Use
* **Statutory Protections:** [17 U.S.C. § 1201(f) (DMCA Reverse Engineering Exception)](https://www.law.cornell.edu/uscode/text/17/1201#f); [EU Software Directive 2009/24/EC, Article 6 (Decompilation for Interoperability)](https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32009L0024).
* **Judicial Precedents:**
  * [*Sega Enterprises Ltd. v. Accolade, Inc.*, 977 F.2d 1510 (9th Cir. 1992)](https://casetext.com/case/sega-enterprises-ltd-v-accolade-inc): The Ninth Circuit held that reverse engineering and intermediate analysis to discover functional interface specifications for interoperability is fair use as a matter of law.
  * [*Sony Computer Entertainment, Inc. v. Connectix Corp.*, 203 F.3d 596 (9th Cir. 2000)](https://casetext.com/case/sony-computer-entertainment-inc-v-connectix-corp): Affirmed that clean-room engineering to build an interoperable environment that allows media to play on alternative platforms is protected transformative fair use.
  * [*Google LLC v. Oracle America, Inc.*, 141 S. Ct. 1183 (2021)](https://www.supremecourt.gov/opinions/20pdf/18-956_d18f.pdf): The U.S. Supreme Court affirmed that reimplementing API interfaces strictly to allow programs to interoperate serves the fundamental constitutional purpose of copyright and constitutes fair use.
  * [*Lexmark Int'l, Inc. v. Static Control Components, Inc.*, 387 F.3d 522 (6th Cir. 2004)](https://casetext.com/case/lexmark-intern-v-static-control-components): Held that DMCA § 1201 anti-circumvention provisions cannot be weaponized to suppress competition or block interoperable compatibility.

#### 3. Clean-Room Architecture: No Proprietary Code or DRM Circumvention
* **Zero Proprietary Code:** This repository contains **no proprietary Spotify binaries, libraries, or copyrighted code**. All legacy private libraries (`unplayplay.aar`) have been completely purged from the codebase.
* **Standard Client Interoperability:** Audio streams are accessed using standard HTTP endpoints and decrypted using keys granted to the user's authenticated session. Music video playback utilizes Android's standard [Media3 / ExoPlayer](https://developer.android.com/media/media3) Widevine DASH architecture. The software acts strictly as an interoperable user agent (akin to an alternative web browser), and users supply their own valid authentication tokens.

#### 4. Trademark Nominative Fair Use
* **Legal Authorities:** [*New Kids on the Block v. News America Publishing, Inc.*, 971 F.2d 302 (9th Cir. 1992)](https://casetext.com/case/new-kids-on-the-block-v-news-america-pub-inc); [USPTO Trademark Nominative Fair Use Guide](https://www.uspto.gov/trademarks/basics/fair-use).
* **Application:** Under the established three-factor test for nominative fair use:
  1. The target product/service (Spotify) cannot be readily identified without reference to the name.
  2. Only so much of the mark is used as is reasonably necessary to designate compatibility.
  3. The project makes no suggestion of sponsorship, endorsement, or affiliation by Spotify USA Inc. or Spotify AB.

---

## Features

- **Browsing & Discovery:** Search tracks, albums, artists, playlists, featured charts, and user library.
- **Music Videos:** Official canvas and music video streaming with quality selection via DASH.
- **Connect Scrobbling:** Real-time state synchronization and listening scrobbles via Spotify Connect protocols.
- **Queue Harvesting:** Seamless background queue sync and track pre-fetching.
- **Clean Architecture:** 100% open-source Kotlin code with zero obfuscated binary blobs or private archives.

---

## Disclaimer

This project is an independent open-source interoperability tool created strictly for educational, research, and compatibility purposes under the doctrine of fair use. It is **not** endorsed, sponsored, affiliated with, or supported by Spotify AB, Spotify USA Inc., or any of their affiliates. All registered trademarks, service marks, and trade names belong to their respective owners.
