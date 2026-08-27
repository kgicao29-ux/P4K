# Phim4K provider maintenance notes

## Design

The extension follows the usual CloudStream provider shape: module metadata, a
plugin entry point that registers `MainAPI`, and a provider handling catalog,
search, metadata, and `loadLinks`.

The Phim4K API uses rotating API/CDN configuration. It is not a page-scraping
provider, so the configuration refresh and signed resolver are both necessary.

## Source and output

- Provider: `Phim4K/src/main/kotlin/com/phim4k/cloudstream/Phim4KProvider.kt`
- Plugin entry point: `com.phim4k.cloudstream.Phim4KPlugin`
- Module: `Phim4K`
- Plugin output: `Phim4K/build/Phim4K.cs3`
- Generated metadata: `build/plugins.json`

## Version 4 fix

The signed resolver's daily key must be represented as:

```text
<original-secret>:<AES-GCM ciphertext-and-tag as hex>
```

The earlier Kotlin implementation used the date as the prefix. That creates a
syntactically valid request but the CDN routes it to the short demo/"old
version" clip. Version 4 uses the same secret-prefix and big-endian timestamp
format as the working Nuvio addon.

It also keeps the v3 resilience changes: an app-compatible request user agent,
configuration refresh after auth/host errors, and one retry with a refreshed CDN
host.

## Verification

On 2026-08-27, the live configuration, movie catalog, TV catalog, search,
detail, signed resolver, and a 1 KiB range request all succeeded. The corrected
resolver returned a real video response (`HTTP 206`) rather than the 1.3 MB
demo-proxy response.

## Publish checklist

1. Change source under `Phim4K/src/main/kotlin/`.
2. Increment `version` in `Phim4K/build.gradle.kts`.
3. Push to `master` and wait for the **Build** workflow.
4. Confirm that `builds/Phim4K.cs3` and `builds/plugins.json` update together.
5. Remove/reinstall the provider in CloudStream if its prior version is cached.
