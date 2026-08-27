# Phim4K provider maintenance notes

## Source and output

- Provider source: `Phim4K/src/main/kotlin/com/phim4k/cloudstream/Phim4KProvider.kt`
- Plugin entry point: `com.phim4k.cloudstream.Phim4KPlugin`
- Module: `Phim4K`
- Build output: `Phim4K/build/Phim4K.cs3`
- Repository metadata: `build/plugins.json`

## Runtime configuration

The upstream Android client uses a rotating API/CDN configuration. The provider
implements the same runtime configuration refresh and must not be reduced to a
single hard-coded API hostname. Availability can change without notice.

## Maintenance checklist

1. Make provider changes under `Phim4K/src/main/kotlin/`.
2. Increment `version` in `Phim4K/build.gradle.kts` before publishing.
3. Push to `master` or run the **Build** workflow manually.
4. Confirm that `builds/Phim4K.cs3` and `builds/plugins.json` changed together.
5. Test the repository URL and a title load in a current CloudStream build.

The old references to a `Phim4KProvider` module and a
`Phim4KCloudstream-source.zip` archive were removed because those paths are not
part of this repository.
