# How to release a new version

## Prerequisites (one-time setup)

The plugin must already exist on JetBrains Marketplace (done).
The following secrets must be set in **GitHub → Settings → Secrets and variables → Actions**:

| Secret | Description |
|--------|-------------|
| `PUBLISH_TOKEN` | From plugins.jetbrains.com → your profile → My Tokens |
| `CERTIFICATE_CHAIN` | Contents of `chain.crt` (generated with OpenSSL) |
| `PRIVATE_KEY` | Contents of `private.pem` (generated with OpenSSL) |
| `PRIVATE_KEY_PASSWORD` | Passphrase chosen during key generation |
| `GH_DEPLOY_TOKEN` | GitHub PAT with `repo` scope (triggers website rebuild) |

## Release steps

### 1. Update `CHANGELOG.md`

Move items from `[Unreleased]` into a new versioned section:

```
## [1.1.0] - 2026-07-01
### feat
- Your new feature
### fix
- Your bug fix
```

### 2. Bump version and release date in `gradle.properties`

```properties
PluginVersion=1.1.0
PluginReleaseDate=20260701
```

`PluginReleaseDate` format: `yyyyMMdd` — update this with every release.

### 3. Commit and push

```bash
git add CHANGELOG.md gradle.properties
git commit -m "release 1.1.0"
git push
```

### 4. Push a version tag

```bash
git tag v1.1.0
git push origin v1.1.0
```

### 5. Done

GitHub Actions automatically:
- Builds the plugin with obfuscation baked in
- Signs and publishes to JetBrains Marketplace
- Creates a GitHub Release with changelog notes and `mapping.txt`

## Changelog format reference

```
## [Unreleased]
### feat
- New thing

## [1.1.0] - 2026-07-01
### feat
- Completed feature
### fix
- Bug fixed
### break
- Breaking change
```
