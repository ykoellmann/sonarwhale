# Changelog

## [Unreleased]

## [1.0.1] - 2026-06-02

### feat
- Pre/Post Scripts are now partially available in the Free tier: global-level scripts (`pre.js` / `post.js` in `.sonarwhale/scripts/`) can use `sw.env` and `sw.request`
- Script hierarchy (tag / endpoint / request level) and advanced APIs (`sw.test`, `sw.expect`, `sw.http`, `sw.response`) require Sonarwhale Premium
- Free-tier scripts that attempt to use premium APIs receive a clear message in the console output instead of a raw JavaScript error

## [1.0.0] - 2026-06-02
### feat
- Initial release
- OpenAPI/Swagger endpoint discovery (Server URL, file, static import)
- Built-in HTTP client with params, headers, and body editor
- Multiple environments with variable substitution ({{varName}})
- Saved requests per endpoint
- Pre/Post scripts (JavaScript via Rhino)
- Auth configuration (Bearer, Basic, API Key, OAuth2 Client Credentials)
- Gutter icons and Jump-to-Source for C#, Java, Python
- Postman Collection export (Premium)
- Run history (Free: last 10 runs, Premium: unlimited)
- Freemium licensing via JetBrains Marketplace
