# Phim4K provider maintenance notes

## Design

The extension follows the standard CloudStream structure used by providers such
as the VegaMovies reference:

- a module `build.gradle.kts` containing CloudStream metadata and a publishable version;
- a plugin entry point that registers a `MainAPI`; and
- a provider implementing main page, search, load, and `loadLinks`.

Phim4K differs from a page-scraping provider: the Android client uses a JSON API
and a rotating API/CDN configuration. The provider keeps that configuration
refresh and resolves playback immediately before CloudStream opens a link.

## Source and output

- Provider: `Phim4K/src/main/kotlin/com/phim4k/cloudstream/Phim4KProvider.kt`
- Plugin entry point: `com.phim4k.cloudstream.Phim4KPlugin`
- Module: `Phim4K`
- Plugin output: `Phim4K/build/Phim4K.cs3`
- Generated metadata: `build/plugins.json`

## Version 3 changes

- Adds the app-compatible HTTP user agent for API and resolver requests.
- Reloads rotating configuration once after authorization/host failures.
- Retries the signed resolver once with a refreshed CDN host.
- Returns an empty home page or `null` detail response on temporary API errors
  instead of propagating a parsing failure.
- Checks out `builds` after compilation in GitHub Actions, reducing publish
  races with a manual `builds`-branch update.

## Publish checklist

1. Change source under `Phim4K/src/main/kotlin/`.
2. Increment `version` in `Phim4K/build.gradle.kts`.
3. Push to `master` and wait for the **Build** workflow.
4. Confirm that `builds/Phim4K.cs3` and `builds/plugins.json` are updated together.
5. Test the repository and title loading in a current CloudStream build.
