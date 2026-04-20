# Sonarwhale

A JetBrains IDE plugin for testing your API endpoints during development — without leaving the editor.

## What is Sonarwhale?

Sonarwhale is a development-time HTTP testing tool that sits inside your IDE. It connects to your project's OpenAPI spec, discovers your endpoints automatically, and lets you fire requests right from the editor. No tab switching, no copy-pasting routes, no keeping a separate tool in sync with your code.

It's not trying to replace Postman for managing large API collections or collaborating across teams. Sonarwhale is built for the developer who just added a new endpoint and wants to hit it *right now* — without setting anything up first.

## Why?

You write a new controller method, start the dev server, and want to test it. Today that means: open Postman, create a new request, type the URL, add headers, paste a body, maybe set up auth. By the time you've done all that, you've lost your flow.

Sonarwhale shortens that loop. Point it at your dev server once, and it reads your OpenAPI spec to discover all endpoints with their parameters, schemas, and auth requirements. Click an endpoint in the tool window or hit the gutter icon next to the code — the request is pre-filled and ready to send.

When your API changes, Sonarwhale picks it up on the next refresh and shows you what's different: new endpoints, modified signatures, removed routes. You always see the current state of your API without doing anything.

## Features

- **Automatic endpoint discovery** from your project's OpenAPI spec
- **Three source modes**: live dev server URL (with auto-discovery of common OpenAPI paths), local spec file (re-read on build), or static import
- **Pre-filled requests** — parameters, body schemas, and auth are populated from the spec
- **Gutter icons** next to endpoint definitions for one-click testing
- **Environment switching** — toggle between dev, staging, and other environments
- **Endpoint diff tracking** — see what changed between refreshes (added, modified, removed)
- **Fallback caching** — if the dev server is down, Sonarwhale keeps working from the last known state
- **Postman-compatible import/export** for when you do need to share collections
- **Local state** stored in `.idea/` — no account, no cloud, no setup

## How It Works

You configure an environment (e.g. `dev` at `localhost:5000`), and Sonarwhale fetches the OpenAPI spec, parses it, and populates the tool window. Refreshes happen automatically on build events, file saves, or at a configurable interval.

For code navigation — jump to definition, gutter icons — Sonarwhale uses a lightweight PSI bridge that matches OpenAPI routes back to their source locations. This is purely a navigation aid; all endpoint data comes from the OpenAPI spec.

## Framework Support

Sonarwhale works with any framework that generates an OpenAPI spec. Auto-discovery probes common paths out of the box:

| Framework | OpenAPI Path |
|---|---|
| ASP.NET Core (Swashbuckle) | `/swagger/v1/swagger.json` |
| ASP.NET Core (Microsoft.OpenApi) | `/openapi/v1.json` |
| FastAPI | `/openapi.json` |
| Spring Boot (springdoc) | `/v3/api-docs` |
| Express + swagger-jsdoc | `/api-docs` |

Custom paths can be configured per environment.

## Mascot

Roux the Narwhal — because narwhals have the best echolocation in nature, and Sonarwhale finds your endpoints like sonar finds submarines.

## Status

Early development. Currently targeting JetBrains Rider 2025.3+ with ASP.NET Core as the first fully supported stack.

## Pre/Post Scripts

Sonarwhale supports JavaScript scripts that run before and after each HTTP request. Scripts live as plain `.js` files in `.sonarwhale/scripts/` and are organized in a hierarchy: global → tag → endpoint → request. Click **⚡ Pre** or **⚡ Post** in the request toolbar to create a script for the current request.

A `sw.d.ts` type definition file is generated automatically so the IDE can provide autocomplete for the `sw` API.

### Auth: fetch a token before every request (global pre-script)

`.sonarwhale/scripts/pre.js`

```js
// Fetch a JWT token and inject it into every outgoing request.
const res = sw.http.post(
  sw.env.get("baseUrl") + "/auth/login",
  JSON.stringify({
    username: sw.env.get("username"),
    password: sw.env.get("password"),
  }),
  { "Content-Type": "application/json" }
);

if (res.status !== 200) {
  throw new Error("Login failed: " + res.status + " " + res.body);
}

const token = res.json().access_token;
sw.env.set("token", token);
sw.request.setHeader("Authorization", "Bearer " + token);
```

### Extract a created resource's ID (post-script)

`.sonarwhale/scripts/Users/POST__api_users/post.js`

```js
// After creating a user, store the new ID so other requests can use it.
if (sw.response.status === 201) {
  const id = sw.response.json().id;
  sw.env.set("lastCreatedUserId", String(id));
}
```

### Assert on response status and shape (post-script)

`.sonarwhale/scripts/Users/GET__api_users_{id}/Happy_Path/post.js`

```js
sw.test("returns 200", function () {
  if (sw.response.status !== 200) {
    throw new Error("Expected 200, got " + sw.response.status);
  }
});

sw.test("response has id and email", function () {
  const body = sw.response.json();
  if (!body.id)    throw new Error("Missing id");
  if (!body.email) throw new Error("Missing email");
});

// Store the email for use in a follow-up request
sw.env.set("lastEmail", sw.response.json().email);
```

### Add a dynamic timestamp header (endpoint pre-script)

`.sonarwhale/scripts/Orders/POST__api_orders/pre.js`

```js
// Some APIs require a request timestamp for idempotency checks.
sw.request.setHeader("X-Request-Timestamp", new Date().toISOString());
sw.request.setHeader("X-Request-Id", Math.random().toString(36).slice(2));
```

### Disable inherited auth for a public endpoint

Create an empty `.sonarwhale/scripts/Public/GET__health/inherit.off` file.
This stops the global `pre.js` (which sets `Authorization`) from running for this endpoint.

---

### Available API

| Object | What it does |
|---|---|
| `sw.env.get(key)` | Read an environment variable |
| `sw.env.set(key, value)` | Write an environment variable (persisted to the active environment) |
| `sw.request.setHeader(k, v)` | Add or override a request header |
| `sw.request.setBody(body)` | Replace the request body |
| `sw.request.setUrl(url)` | Replace the request URL |
| `sw.response.status` | HTTP status code (post-scripts only) |
| `sw.response.json()` | Parse the response body as JSON (post-scripts only) |
| `sw.http.get(url, headers?)` | Synchronous GET request |
| `sw.http.post(url, body, headers?)` | Synchronous POST request |
| `sw.http.request(method, url, body?, headers?)` | Any HTTP method |
| `sw.test(name, fn)` | Assert — shown in the Tests tab; throw or return false to fail |
| `sw.expect(value).toBe(expected)` | Inline assertion |

## License

TBD