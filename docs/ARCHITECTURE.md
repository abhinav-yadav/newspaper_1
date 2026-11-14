# Architecture

Two Gradle modules. The brief suggested eight; most of them would hold one or two files, so they are
**packages** inside `app` instead. `core` is a separate module because it is pure Kotlin/JVM: the whole
extraction engine can be unit-tested, and run against the live site, without Android.

```
┌──────────────────────────── app (Android) ───────────────────────────────┐
│ ui/        Compose screens: Home, Discover, Downloads, Library, Settings  │
│            MainViewModel (open/resolve orchestration, UI models)          │
│ reader/    PdfDocument (platform PdfRenderer), ReaderScreen, ReaderVM     │
│ browser/   AssistedBrowser: sandboxed WebView + BrowserPolicy             │
│ storage/   DownloadController + DownloadWorker (WorkManager), PaperFiles  │
│ sync/      SyncWorker (periodic feed refresh)                             │
│ data/      Room (editions, papers, library, favorites, downloads),        │
│            PaperRepository, SettingsStore                                 │
│ EpaperApp  AppContainer: manual DI                                        │
└───────────────────────────────┬──────────────────────────────────────────┘
                                │
┌─────────────────────────── core (JVM) ────────────────────────────────────┐
│ ExtractionConfig  ← extraction_rules.json (selectors, host rules, blocklist)│
│ DiscoveryService  Blogger JSON feed → EditionParser → Edition/Paper        │
│ EditionParser     Jsoup; mirrors the site's own availability logic          │
│ LinkResolver      GOOGLE_DRIVE / DIRECT / BROWSER_ASSISTED strategies,      │
│                   manual redirects (cap + loop detection)                   │
│ PdfValidator      %PDF- header, %%EOF tail                                  │
│ Http              OkHttp client, retries with backoff, timeouts             │
└────────────────────────────────────────────────────────────────────────────┘
```

## Flow: tap "Read"

```
PaperCard.Read
  └─ MainViewModel.open(id)
       ├─ already downloaded / downloading ──────────────────────────► Reader
       ├─ status Pending / Off ─────────────► snackbar with the reason
       └─ PaperRepository.resolve(paper) → LinkResolver
            ├─ Document(url)      → DownloadController.enqueue ───────► Reader (shows progress)
            ├─ NeedsBrowser(url)  → AssistedBrowser
            │                         ├─ ads/trackers blocked, pop-up tabs swallowed
            │                         ├─ host's check runs in the real WebView
            │                         ├─ free-download button auto-pressed (config)
            │                         └─ DownloadListener / .pdf nav captured
            │                              → enqueue(url, cookies, UA, referer) ► Reader
            └─ Unavailable(reason) → snackbar + "Open post" (embedded browser)

DownloadWorker: Range resume → first chunk must be %PDF → %%EOF check → rename → cover thumbnail
ReaderViewModel: waits for COMPLETE → PdfDocument.open → restores last page
```

## Data model (Room)

| Table | Key | Notes |
|---|---|---|
| `editions` | Blogger post id | title, date (IST), url |
| `papers` | `<postId>-<position>` | title, code, subtitle, category, language, status, link, offReason, `removedReason` |
| `library` | paperId | bookmark, lastOpenedAt (history), lastPage, pageCount — never touched by refreshes |
| `favorites` | publication title | follow a paper across editions; favourites sort first on Home |
| `downloads` | paperId | url, captured headers, state, bytes, file path — one row per paper prevents duplicates |

`removedReason` is kept across refreshes only while the blog keeps the same link, so a re-uploaded file
is picked up automatically.

## Reliability decisions

* **Never a wrong document**: a file is accepted only after `%PDF-` is seen in the first bytes *and* `%%EOF`
  at the end. Content-Type is ignored (hosts lie both ways).
* Redirects are followed manually: cap from config (8), visited-set loop detection.
* Network: connect 15 s, read 30 s (60 s for downloads), 3 attempts with exponential backoff for 5xx/429/IO.
* Site changes: all selectors and host behaviour live in `extraction_rules.json`. If the markup changes,
  the parser yields "no papers" (not a crash) and the UI shows a clear message.
* Offline: Room is the source of truth; downloaded PDFs live in app-private storage.

## Security & privacy

* No accounts, analytics or third-party SDKs. History is local; backups and device transfer are excluded.
* Cleartext HTTP disabled. WebView: no file/content access, `intent:`/custom schemes blocked, pop-ups never
  displayed, ad/tracker sub-resources blocked, navigation off the host's domains only on a user gesture
  or if it is the document itself (HEAD-checked).
* JavaScript runs only inside the assisted browser, and only because the file host's check requires it.
  Discovery and parsing never execute site scripts.
* The app does not defeat the host's captcha/proof-of-work and does not bypass logins or paywalls.
