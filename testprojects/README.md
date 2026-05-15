# Sonarwhale Test Projects

Four fully-featured REST API projects — one per language/framework — used to test every
aspect of the Sonarwhale JetBrains plugin:

| Directory | Language | Framework | IDE | Port |
|-----------|----------|-----------|-----|------|
| `csharp/SonarwhaleTestApi` | C# | ASP.NET Core 10 (Minimal + MVC) | Rider | 5000 |
| `python/fastapi-test` | Python | FastAPI | PyCharm / IntelliJ | 8000 |
| `python/flask-test` | Python | Flask | PyCharm / IntelliJ | 5001 |
| `java/sonarwhale-test-api` | Java | Spring Boot 3.5 | IntelliJ IDEA | 8080 |

---

## What each project covers

### HTTP methods
`GET` · `POST` · `PUT` · `PATCH` · `DELETE` · `HEAD` · `OPTIONS`

### Parameter locations
`path` · `query` · `header` · `cookie` · `request body` (JSON + multipart/form-data)

### Auth types (one controller/router per type)
| Auth | Header / mechanism |
|------|--------------------|
| Bearer JWT | `Authorization: Bearer <token>` |
| API Key | `X-Api-Key: <key>` |
| Basic | `Authorization: Basic <base64>` |
| OAuth2 client credentials | (simulated via JWT scope) |
| None | Public endpoints |

### Scanner edge cases per language

**C# (`CSharpScanner`)**
- `[HttpGet]` with no template (maps to controller prefix)
- `[HttpGet(path="/search")]` — named `path=` argument
- `[HttpGet("{id:int}/avatar")]` — regex / type constraint in template
- `[AcceptVerbs("GET","HEAD")]` — multi-verb attribute
- `[HttpGet("~/api/products/all")]` — tilde `~` absolute override
- `[NonAction]` — must **not** produce a gutter icon
- `[Area("admin")]` + explicit `[Route]` (no `[controller]` token)

**Python (`PythonScanner`)**
- `@router.get("/")` — router root (prefix contributes full path)
- `@router.get("/{user_id}")` — path param
- `@app.options("/api/users")` — OPTIONS on app-level route
- `@bp.route("/<int:user_id>", methods=["GET","HEAD"])` — Flask type prefix + multi-method
- `<string:name>` Flask type-prefix → normalised to `{name}`

**Java (`JavaScanner`)**
- `@GetMapping` (no path arg) — inherits class-level `@RequestMapping`
- `@GetMapping(path="/search")` — named `path=` argument
- `@GetMapping("/{id:[0-9]+}/avatar")` — regex constraint
- `@PostMapping(consumes=MediaType.APPLICATION_JSON_VALUE)` — named `consumes=`, no path
- `@RequestMapping(value="/{id}", method=RequestMethod.HEAD)` — old-style annotation
- `@GetMapping(value="/{id}/variants", produces=...)` — multi-line with named `value=`
- `@RequestMapping(value="/by-name/{name}", method=RequestMethod.GET)` — old-style GET

---

## Running the projects

### C# — ASP.NET Core 10

**Prerequisites:** .NET 10 SDK  
```bash
cd csharp/SonarwhaleTestApi
dotnet run
# OpenAPI spec:  http://localhost:5000/openapi/v1.json
# Scalar UI:     http://localhost:5000/scalar/v1
```

**Test credentials**
- JWT: `POST /api/auth/login` with `{"username":"alice","password":"password123"}`
- API Key: `X-Api-Key: test-api-key-12345`
- Basic: `admin:admin123` (Base64 → `YWRtaW46YWRtaW4xMjM=`)

---

### Python — FastAPI

**Prerequisites:** Python 3.11+
```bash
cd python/fastapi-test
python -m venv .venv && source .venv/bin/activate
pip install fastapi uvicorn "python-jose[cryptography]" "passlib[bcrypt]" python-multipart
uvicorn main:app --reload --port 8000
# OpenAPI spec:  http://localhost:8000/openapi.json
# Swagger UI:    http://localhost:8000/docs
```

**Test credentials**
- JWT: `POST /api/auth/login` with `{"username":"alice","password":"password123"}`
- API Key: `X-Api-Key: test-api-key-12345`
- Basic: `Authorization: Basic YWRtaW46YWRtaW4xMjM=` (admin:admin123)

---

### Python — Flask

**Prerequisites:** Python 3.11+
```bash
cd python/flask-test
python -m venv .venv && source .venv/bin/activate
pip install flask flask-cors
python app.py
# OpenAPI spec:  http://localhost:5001/openapi.json
# (no Swagger UI — use Sonarwhale's built-in HTTP client)
```

**Test credentials** — same as FastAPI above.

---

### Java — Spring Boot 3.5

**Prerequisites:** JDK 21+
```bash
cd java/sonarwhale-test-api
./mvnw spring-boot:run
# OpenAPI spec:  http://localhost:8080/v3/api-docs
# Swagger UI:    http://localhost:8080/swagger-ui.html
```

> **Note:** The repo ships a portable Temurin JDK 21 in `java/jdk-21.0.5+11/` (git-ignored,
> ~200 MB). If you don't have JDK 21 on `$PATH`, set `JAVA_HOME` first:
> ```bash
> export JAVA_HOME=$(pwd)/../jdk-21.0.5+11
> ./mvnw spring-boot:run
> ```

**Test credentials**
- JWT: `POST /api/auth/login` with `{"username":"alice","password":"password123"}`
- API Key: `X-Api-Key: test-api-key-12345`
- Basic: `admin:admin123`

---

## Sonarwhale environment setup

Add the following entries to your Sonarwhale environments (Settings → Sonarwhale → Environments):

```yaml
# environments.yaml (example)
environments:
  - name: "C# Test API"
    baseUrl: "http://localhost:5000"
    openApiUrl: "http://localhost:5000/openapi/v1.json"
    auth:
      bearer:
        token: "{{jwt_token}}"     # obtain from POST /api/auth/login
      apiKey:
        header: "X-Api-Key"
        value: "test-api-key-12345"
      basic:
        username: "admin"
        password: "admin123"

  - name: "FastAPI Test"
    baseUrl: "http://localhost:8000"
    openApiUrl: "http://localhost:8000/openapi.json"
    auth:
      bearer:
        token: "{{jwt_token}}"
      apiKey:
        header: "X-Api-Key"
        value: "test-api-key-12345"
      basic:
        username: "admin"
        password: "admin123"

  - name: "Flask Test"
    baseUrl: "http://localhost:5001"
    openApiUrl: "http://localhost:5001/openapi.json"
    auth:
      bearer:
        token: "{{jwt_token}}"
      apiKey:
        header: "X-Api-Key"
        value: "test-api-key-12345"

  - name: "Spring Boot Test"
    baseUrl: "http://localhost:8080"
    openApiUrl: "http://localhost:8080/v3/api-docs"
    auth:
      bearer:
        token: "{{jwt_token}}"
      apiKey:
        header: "X-Api-Key"
        value: "test-api-key-12345"
      basic:
        username: "admin"
        password: "admin123"
```

---

## API surface at a glance

| Resource | Endpoints | Auth |
|----------|-----------|------|
| `/health`, `/api/version` | GET | None |
| `/api/users` | OPTIONS, HEAD, GET(list), GET(/:id), POST, PUT(/:id), PATCH(/:id), DELETE(/:id) | Bearer JWT |
| `/api/products` | GET(list), GET(/search), GET(/:id), GET(/:id/variants), GET(/by-name/:name), POST, PUT(/:id), DELETE(/:id) | API Key |
| `/api/orders` | GET(list), GET(/:id), POST, PATCH(/:id/status), DELETE(/:id) | Bearer JWT |
| `/api/admin/users` | GET, POST(/:id/ban), DELETE(/:id) | Basic |
| `/api/admin/stats` | GET | Basic |
| `/api/auth/login` | POST | None |
| `/api/auth/refresh` | POST | Bearer JWT |
