# Phim4K 2.6.6 — APK/API analysis and CloudStream extension

**Analyzed:** 2026-08-27 (UTC+7)  
**Input:** `https://aftv.news/3621883`  
**Result:** redirects to `phim4k-2.6.6-cho-tivi.apk`

## APK identity

| Property | Value |
|---|---|
| App label | Phim4K |
| Package | `com.oxootv.spagreen` |
| Version | `2.6.6` (`versionCode 76`) |
| Minimum Android | API 21 |
| Target Android | API 33 |
| APK SHA-256 | `9e72a0ab7e8ae1f31b8c1086efdffa6b4842aff114e8ea1fc7162501571fb537` |

The APK is a native Android/Java application using Retrofit/OkHttp. API and CDN configuration is partly stored in `libapi_config.so` and partly delivered by an encrypted runtime configuration endpoint.

## API found

The app fetches an AES-GCM encrypted configuration from:

```text
GET https://ltv.cryboiz.workers.dev/api/add
```

At test time, decrypting that response produced these active hosts:

```text
API: apip4k.dpdns.org
CDN: sv1.p4k.dpdns.org
```

The main catalog base is:

```text
https://apip4k.dpdns.org/rest-api/v130/
```

The app supplies both an `Authorization: Basic …` header and an `API-KEY` header. The extension reproduces the same client-side request setup and obtains the current API token from the dynamic configuration rather than assuming that the host will remain fixed.

### Useful routes

| Method | Route | Purpose |
|---|---|---|
| GET | `movies?page={n}` | Movie catalog |
| GET | `tvseries?page={n}` | TV catalog |
| GET | `top_views?period=weekly&type={movie|tvseries}&limit=24&page={n}` | Popular content |
| GET | `search?q={query}&page=1&type={movie|tvseries}` | Search |
| GET | `single_details?type={movie|tvseries}&id={id}` | Metadata, sources, seasons, episodes, subtitles |
| GET | `home_content_for_android` | Original app home payload |
| GET | `all_genre` | Genre list |
| GET | `all_country` | Country list |
| GET | `config` | App configuration |

The APK also defines two secondary/free provider clients:

- Free provider 1: `danh-sach/phim-moi-cap-nhat`, `phim/{slug}`, `v1/api/tim-kiem`
- Free provider 2: `api/films/phim-moi-cap-nhat`, `api/film/{slug}`, `api/films/search`

Their configured `free1.phim4k.online` and `free2.phim4k.online` names did not resolve during testing, so the extension uses the working main API instead.

## Protected stream flow

A detail response supplies legacy source URLs such as:

```text
https://cdn.phim4k.lol/{file-id}
```

The legacy hostname no longer resolves. The original app extracts `{file-id}`, derives a daily AES-GCM/HMAC-SHA256 signing key, and calls the active CDN resolver:

```text
GET https://sv1.p4k.dpdns.org/{file-id}?token={hmac}&ts={masked-time}
```

That returns JSON containing the playable URL. The CloudStream extension implements this same resolution flow at playback time, so links are generated immediately before CloudStream opens them.

## Extension features

- Latest movie and TV rows
- Weekly popular movie and TV rows
- Movie and TV search
- Full movie details
- TV seasons and episodes
- Posters, plot, year, genres, cast, rating, runtime, trailer, and recommendations
- Subtitle callbacks
- Multiple movie sources
- Dynamic API/CDN configuration refresh
- Signed CDN URL resolution
- Direct `VIDEO`, HLS, and DASH link typing

## Verification

A live smoke test on 2026-08-27 completed the complete chain:

| Check | Result |
|---|---|
| Dynamic configuration | HTTP 200 |
| Movie catalog | HTTP 200; 24 items |
| Detail request | HTTP 200 |
| CDN resolver | HTTP 200 |
| Resolved stream range | HTTP 206, `video/mp4`, 1,024 bytes read |
| CloudStream Gradle build | Successful |
| `.cs3` ZIP integrity | Successful |

### Built file

```text
Phim4KProvider.cs3
SHA-256: 2d577bdd48db5419689f22660002af21a4000e32125918d1c8ad4675991eb474
```

The package contains `manifest.json` and `classes.dex`; its registered entry point is:

```text
com.phim4k.cloudstream.Phim4KPlugin
```

## Source/build

Source folder: `cloudstream-phim4k/`  
Source archive: `Phim4KCloudstream-source.zip`

Build with JDK 17 and Android SDK 35:

```bash
./gradlew Phim4KProvider:make
```

Output:

```text
Phim4KProvider/build/Phim4KProvider.cs3
```

## Limitations

- These are undocumented app endpoints and can change or be disabled without notice.
- Dynamic configuration reduces host-rotation breakage, but an app update could rotate the signing algorithm or embedded secrets.
- Availability and legality of individual media vary by location. Use the extension only for content and services you are authorized to access.
