# Phim4K CloudStream extension

CloudStream provider reconstructed from the Android TV APK behind `aftv.news/3621883`
(Phim4K 2.6.6).

## Build

Requires JDK 17 and an Android SDK with platform 35 installed.

```bash
./gradlew Phim4KProvider:make
```

The output is `Phim4KProvider/build/Phim4KProvider.cs3`.

## What it implements

- Main pages: latest movies/series and weekly top movies/series
- Movie and TV search
- Movie details, seasons and episodes
- Direct video links and subtitles
- Runtime dynamic-host configuration
- Phim4K CDN URL signing/resolution

Use only with content and services you are authorized to access.
