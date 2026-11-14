# Phase 1 — Website reverse engineering: epaperwala11.blogspot.com

Investigated 2026-11-14 with curl, the Blogger feed API and headless Chromium (Playwright).
Every claim below was verified against the live site; nothing is inferred from the site's own "how-to" text alone.

## 1. Architecture

```
                         epaperwala11.blogspot.com  (Google Blogger, custom theme)
                                       │
          ┌────────────────────────────┼─────────────────────────────┐
          │                            │                             │
   Home / ?m=1 (HTML, ~390 KB,   /feeds/posts/default            /YYYY/MM/<slug>.html
   inline CSS+JS, ad scripts)    ?alt=json  (Atom→JSON,          one post per day
          │                      FULL post bodies, 91 posts)     ("13/11", "DATE - 01/09/2026")
          │                            │                             │
          └──────────── same post body markup ───────────────────────┘
                                       │
                 div.ep-main-wrapper > div.ep-resource-item × 10
                   data-category = newspaper | editorial
                   span.ep-card-avatar  = code  (TH, IE, TOI, BL, LM, ET, FE, BS, EN, HI)
                   span.ep-card-title   = title ("The H. Edition")
                   span.ep-card-sub     = "English · National" (new) or "[TH]" (old template)
                   a.ep-access-btn@href = one of:
                       https://devuploads.com/<12-char id>     (current, 616 links)
                       https://zdrive.to/<id>                  (Aug–Sep, 155 links, now 404)
                       https://drive.google.com/file/d/<id>/…  (mid-Sep, 23 links, mostly expired)
                       YOUR_LINK_HERE                          (not yet uploaded → "Unavailable")
                       OFF:<reason>                            ("Sunday Off", festival holidays)
                                       │
                                       ▼
                         third-party file host (see §3)
```

* **Edition = one Blogger post per day**, containing ~10 "papers" (8 newspapers + 2 editorial packs).
* Post titles are inconsistent free text (`13/11`, `2 /10 `, `DATE - Tue, 29/09/2026`) → the app uses the
  feed's `published` timestamp, never the title, for dates.
* There are no Blogger labels on edition posts. Labels are used only for side-channel posts
  (`notification` popups, `status-*` badges per the hidden edit notes) — non-edition posts are recognised
  structurally (no `.ep-resource-item`) and skipped.
* The "✅ / ❌" badge is computed client-side by `epUpdateAvailability()` purely from the `href` value
  (`YOUR_LINK_HERE`, empty or `OFF[:reason]` ⇒ unavailable). This is deterministic, so the app
  reproduces it from static HTML; **no JavaScript rendering is needed for discovery**.
* The site states each edition is kept for ~48 h; older file links die on the host side.

## 2. Structured sources

| Source | Result |
|---|---|
| `/feeds/posts/default?alt=json&max-results=N&start-index=K` | **Used.** JSON, full post HTML in `content.$t`, `published`, `id`, alternate link. 91 posts total. Supports paging. |
| `/feeds/posts/summary` | Exists but truncates the body — unusable for links. |
| Post HTML page | Same `.ep-resource-item` markup inside `.post-body`; used as fallback when the feed body has no items (e.g. if the owner switches feed to "Short"). |
| Home page HTML | Heavy (390 KB) and paginated; not needed. |

## 3. Download hosts and redirect chains

### devuploads.com (all current posts)

```
Blog "Open" ──► devuploads.com/<id>   (200, plain HTML; filename + size visible:
      │                                "13_nov_bl.pdf", "32.8 MB"; hidden form op=download1)
      │         ├─ ad scripts: sads.adsboosters.xyz, scripts.cleverwebserver.com,
      │         │  googlesyndication, doubleclick, amazon-adsystem, tiktok, licdn …
      │         ├─ SECURITY CHECK: Cloudflare Turnstile, hCaptcha fallback, ALTCHA
      │         │  proof-of-work ("DlGuard"); "Free Download" stays disabled until it passes
      │         └─ "Free Download" also opens data-dl-open = devuploads.com/next/<token>
      │            in a NEW TAB  ← the "ad tab" the blog tells users to close
      ▼
  countdown ("Please wait %s seconds…") ──► file served (host not observable without passing the check)
```

* Dead file: same URL returns 200 with "File Not Found" and no `fname` input → detectable with one GET.
* The captcha/proof-of-work is an **anti-bot access control**. Defeating it programmatically
  (solving Turnstile/hCaptcha, replaying tokens) is out of scope by the project's own rules
  (§9: "do not bypass … technical access restrictions"), and would break on every DlGuard change anyway.

### drive.google.com (older posts)

```
drive.google.com/file/d/<ID>/view ──► drive.usercontent.google.com/download?id=<ID>&export=download&confirm=t
                                       200 application/octet-stream, body starts with %PDF  (verified, 11.8 MB)
                                       404 text/html when the owner deleted it (verified)
```
Fully automatable; no captcha, no login for public files.

### zdrive.to (Aug–Sep posts)
All sampled links return `404 - Page Not Found` (zincdrive.com theme). Treated as a generic host.

## 4. The user-facing workflow today vs. in the app

| Step on the website | In the app |
|---|---|
| Open blog, scroll past banners/tutorial/Telegram promo | Opens directly on a list of today's papers |
| Find the paper, work out whether it's ✅ or ❌ | Status shown per card; unavailable/holiday cards say why |
| Tap Open → leaves to devuploads | Tap Read → stays in app |
| Ads + ad tab opens; "close it and come back" | Ad/tracker requests blocked, pop-up tabs swallowed invisibly |
| Pass security check, wait for timer | **Still required** (host's access control) — done in an in-app sheet |
| Find the real download button among "Premium" upsells | Free-download button auto-pressed once the check passes (configurable selector) |
| File downloads to phone, open with another app | File captured, `%PDF` validated, opened in the built-in reader, kept offline |
| Google Drive editions | Fully automatic: tap → reader |

## 5. Extraction methodology (implemented in `core/`)

1. **Discover**: Blogger JSON feed → `EditionParser` (Jsoup) → `Edition` + `Paper[]` with status
   `Available(url) | Pending | Off(reason)`. Fallback to the post HTML when the feed body has no items.
2. **Resolve** (`LinkResolver`, rule-driven by `HostRule` config, see `extraction_rules.json`):
   * `GOOGLE_DRIVE`: id → usercontent download URL → validate.
   * `DIRECT`: follow redirects manually (cap 8, loop detection) → if PDF → done; if HTML, scan for
     `.pdf`/Drive links one level deep.
   * `BROWSER_ASSISTED` (devuploads): one GET to check the file still exists and read name/size;
     then hand off to the sandboxed in-app browser which captures the download.
   * Unknown hosts default to `DIRECT`, then browser-assisted.
3. **Validate**: a document is accepted only if the first bytes are `%PDF-` (Content-Type is ignored —
   Drive serves `application/octet-stream`). HTML or anything else is rejected, never shown as a paper.

## 6. Feasibility and limitations

* Discovery: 100 % automatable and robust (structured feed + stable class names; several fallbacks).
* Google Drive / direct PDF hosts: 100 % automatable.
* devuploads: **cannot be fully automated legitimately** because of the captcha gate. The app reduces it
  to one in-app sheet with no ads, no tabs and no hunting for buttons; in typical Android WebViews
  Turnstile/ALTCHA pass without interaction, so in the common case the user just waits for the timer.
  The post-captcha part of the flow could not be observed from the build sandbox (the check does not
  load headlessly), so capture is implemented generically (WebView `DownloadListener` + `.pdf` navigation
  interception + `%PDF` validation) rather than against a hard-coded final URL.
* Links expire after ~48 h on the host side — the app marks such papers "removed from host" after a
  check, and offline copies remain readable.
* Copyright: the blog redistributes newspapers that are normally subscription e-papers. The app only
  automates navigation a normal visitor performs, keeps the source link visible and does not
  redistribute files; whether reading them is lawful for a given user is outside what the app can decide.
