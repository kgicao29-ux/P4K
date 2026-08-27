# Phim4K CloudStream extension

CloudStream provider for the Phim4K catalog. The provider obtains its API and
CDN host configuration at runtime, so it does not rely on a single legacy host.

## Install

Add this repository in CloudStream using:

```text
https://raw.githubusercontent.com/kgicao29-ux/P4K/master/repo.json
```

Then install or update **Phim4K** from the repository list. If CloudStream has
kept an older copy, remove the existing Phim4K extension, restart CloudStream,
and install it again.

## Build

Requires JDK 17 and an Android SDK with platform 35 installed.

```bash
./gradlew :Phim4K:make
```

The extension output is:

```text
Phim4K/build/Phim4K.cs3
```

To also generate the repository metadata:

```bash
./gradlew make makePluginsJson
```

## Publishing updates

Increment `version` in `Phim4K/build.gradle.kts` whenever you publish a new
extension. The GitHub Actions build publishes `Phim4K.cs3` and `plugins.json`
to the `builds` branch.

Use only content and services that you are authorized to access.
