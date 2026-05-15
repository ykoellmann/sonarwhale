# Sonarwhale Test Projects — Implementation Plan

## Ziel

Ein `testprojects/`-Ordner mit vollständigen, lauffähigen API-Projekten für jede
unterstützte Sprache/Framework-Kombination. Jedes Projekt implementiert dieselbe
fachliche API (Users / Products / Orders / Auth / Admin / Health), sodass
Sonarwhale-Features sprachübergreifend direkt verglichen werden können.

**Zwei Testziele gleichzeitig:**
1. **Scanner-Korrektheit** (Gutter-Icons, Jump-to-Source): Jede Quellcodedatei enthält
   gezielt die Edge Cases, die `CSharpScanner`, `PythonScanner` und `JavaScanner` abdecken.
2. **OpenAPI-Parsing-Abdeckung**: Die generierten Specs enthalten alle Parameter-Typen,
   Auth-Schemes, Schemas und HTTP-Methoden, die `OpenApiParser` versteht.

---

## Verzeichnisstruktur

```
testprojects/
├── README.md
├── csharp/
│   └── SonarwhaleTestApi/          ← ASP.NET Core 8 Web API
├── python/
│   ├── fastapi-test/               ← FastAPI
│   └── flask-test/                 ← Flask
└── java/
    └── sonarwhale-test-api/        ← Spring Boot 3.x
```

---

## API-Oberfläche (identisch in allen Projekten)

### Auth (kein Auth erforderlich)

| Method | Path | Beschreibung |
|--------|------|--------------|
| POST | `/api/auth/login` | username + password → JWT access + refresh token |
| POST | `/api/auth/refresh` | refresh token → neuer access token |
| POST | `/api/auth/logout` | token invalidieren (204 No Content) |

### Users (Bearer JWT)

| Method | Path | Param-Typen | Beschreibung |
|--------|------|-------------|--------------|
| GET | `/api/users` | QUERY: page, pageSize, search, sort | Paginated list |
| GET | `/api/users/{id}` | PATH: id (int) | User by ID |
| POST | `/api/users` | Body: JSON object | Create user (201) |
| PUT | `/api/users/{id}` | PATH + Body | Full update |
| PATCH | `/api/users/{id}` | PATH + Body (partial) | Partial update |
| DELETE | `/api/users/{id}` | PATH | Delete (204) |
| GET | `/api/users/{id}/avatar` | PATH, HEADER: Accept | Get avatar |
| POST | `/api/users/{id}/avatar` | PATH + multipart/form-data | Upload avatar |

### Products (API Key im Header)

| Method | Path | Param-Typen | Beschreibung |
|--------|------|-------------|--------------|
| GET | `/api/products` | QUERY: category, minPrice, maxPrice, page, pageSize | List |
| GET | `/api/products/{id}` | PATH | Get by ID |
| POST | `/api/products` | Body: JSON | Create |
| PUT | `/api/products/{id}` | PATH + Body | Update |
| DELETE | `/api/products/{id}` | PATH | Delete |
| GET | `/api/products/search` | QUERY: q, tags (array), inStock | Full-text search |
| GET | `/api/products/{id}/variants` | PATH | Nested resource list |

### Orders (OAuth2 Client Credentials)

| Method | Path | Beschreibung |
|--------|------|--------------|
| GET | `/api/orders` | List mit QUERY: status, from, to, userId |
| GET | `/api/orders/{id}` | Order mit nested items array |
| POST | `/api/orders` | Komplexer nested Body: items[], shippingAddress{} |
| PATCH | `/api/orders/{id}/status` | Status-Update (enum) |
| DELETE | `/api/orders/{id}` | Soft-delete / Cancel (204) |

### Admin (Basic Auth)

| Method | Path | Beschreibung |
|--------|------|--------------|
| GET | `/api/admin/users` | Admin-seitige Userliste (inkl. gesperrte) |
| POST | `/api/admin/users/{id}/ban` | User sperren |
| DELETE | `/api/admin/users/{id}` | Hartes Löschen |
| GET | `/api/admin/stats` | Systemstatistiken |

### Public / Health (kein Auth)

| Method | Path | Beschreibung |
|--------|------|--------------|
| GET | `/health` | Health check — testet `inherit.off` / [AllowAnonymous] |
| GET | `/api/version` | Version info |
| OPTIONS | `/api/users` | CORS preflight (testet OPTIONS-Methode) |
| HEAD | `/api/users/{id}` | Existenzprüfung (testet HEAD-Methode) |

---

## Abgedeckte Feature-Matrix

### HTTP-Methoden
`GET` `POST` `PUT` `PATCH` `DELETE` `HEAD` `OPTIONS`

### Parameter-Locations
`PATH` `QUERY` `HEADER` `COOKIE`

### Auth-Typen (laut `AuthMode`/`AuthType`)
| AuthType | Wo verwendet |
|----------|-------------|
| `NONE` | Auth-Controller, Health, Version |
| `BEARER` | Users (JWT) |
| `API_KEY` (Header) | Products (`X-Api-Key`) |
| `API_KEY` (Query) | Products-Search (`?api_key=`) |
| `BASIC` | Admin |
| `OAUTH2` (client_credentials) | Orders |

### Request-Body-Typen
- JSON object (Users, Products, Orders)
- Nested JSON object (Orders: `shippingAddress{}`)
- Array in Body (Orders: `items[]`)
- `multipart/form-data` (Avatar-Upload)
- `application/x-www-form-urlencoded` (Auth-Login alternativ)

### Response-Typen & Status-Codes
`200` `201` `204` `400` `401` `403` `404` `409` `422` `500`

### Schema-Typen (für `ApiSchema.toJsonTemplate()`)
- Primitive: `string`, `integer`, `number`, `boolean`
- Objekt mit Properties
- Nested Objekt
- Array von Objekten
- Enum-Felder
- Nullable-Felder
- `$ref`-Referenzen (zur Parser-Robustheit)

---

## Projekt 1: C# — ASP.NET Core 8

**Framework:** ASP.NET Core 8 Minimal Hosting + Controller-based + Minimal API mixed  
**OpenAPI:** `Microsoft.AspNetCore.OpenApi` (built-in, `/openapi/v1.json`) + Swashbuckle optional  
**Auth:** JWT Bearer via `Microsoft.AspNetCore.Authentication.JwtBearer`

### Dateistruktur

```
csharp/SonarwhaleTestApi/
├── SonarwhaleTestApi.csproj
├── Program.cs                          ← Minimal API endpoints + app setup
├── Controllers/
│   ├── UsersController.cs              ← [Route("api/[controller]")], Bearer
│   ├── ProductsController.cs           ← [Route("api/[controller]")], API Key
│   ├── OrdersController.cs             ← [Route("api/[controller]")], OAuth2
│   ├── AuthController.cs               ← [AllowAnonymous], Login/Refresh/Logout
│   └── AdminController.cs              ← [Area("admin")], [Route("api/admin")]
├── Models/
│   ├── User.cs
│   ├── Product.cs
│   ├── Order.cs
│   └── Auth.cs
├── appsettings.json
└── appsettings.Development.json
```

### Scanner-Stress-Tests (CSharpScanner Edge Cases)

**UsersController.cs** — deckt ab:
```csharp
[ApiController]
[Route("api/[controller]")]
[Authorize]
public class UsersController : ControllerBase
{
    [HttpGet]                                    // → GET /api/users (kein Template)
    [HttpGet("{id:int}")]                        // → GET /api/users/{id} (Constraint)
    [HttpGet("{id:int}/avatar")]                 // → GET /api/users/{id}/avatar
    [HttpPost]                                   // → POST /api/users
    [HttpPut("{id:int}")]                        // → PUT /api/users/{id}
    [HttpPatch("{id:int}")]                      // → PATCH /api/users/{id}
    [HttpDelete("{id:int}")]                     // → DELETE /api/users/{id}
    [HttpPost("{id:int}/avatar")]                // → POST /api/users/{id}/avatar
    
    [NonAction]                                  // ← darf KEIN Gutter-Icon bekommen
    public void PrivateHelper() {}
}
```

**ProductsController.cs** — deckt ab:
```csharp
[AcceptVerbs("GET", "HEAD")]                    // AcceptVerbs-Parsing
[HttpGet("~/absolute/route")]                   // Tilde-Override
[HttpGet("search")]                             // Suffix-only route
```

**AdminController.cs** — deckt ab:
```csharp
[Area("admin")]                                 // Area-Routing
[Route("api/admin")]                            // Expliziter Prefix (kein [controller])
[Authorize(Policy = "AdminOnly")]
```

**Program.cs** — Minimal API:
```csharp
app.MapGet("/health", () => ...)               // kein Auth
app.MapGet("/api/version", () => ...)
app.MapOptions("/api/users", () => ...)
app.MapMethods("/api/users/{id}", ["HEAD"], ...)
```

---

## Projekt 2: Python — FastAPI

**Framework:** FastAPI  
**OpenAPI:** auto-generated `/openapi.json`  
**Auth:** `python-jose` (JWT) + API Key dependency

### Dateistruktur

```
python/fastapi-test/
├── main.py                             ← FastAPI app + router registration
├── routers/
│   ├── users.py                        ← APIRouter(prefix="/api/users", tags=["Users"])
│   ├── products.py                     ← APIRouter(prefix="/api/products", tags=["Products"])
│   ├── orders.py                       ← APIRouter(prefix="/api/orders", tags=["Orders"])
│   ├── auth.py                         ← APIRouter(prefix="/api/auth", tags=["Auth"])
│   └── admin.py                        ← APIRouter(prefix="/api/admin", tags=["Admin"])
├── models/
│   ├── user.py                         ← Pydantic models
│   ├── product.py
│   ├── order.py
│   └── auth.py
├── security.py                         ← JWT + API Key dependencies
├── requirements.txt
└── README.md
```

### Scanner-Stress-Tests (PythonScanner Edge Cases)

**routers/users.py** — deckt ab:
```python
router = APIRouter(prefix="/api/users", tags=["Users"])

@router.get("/")                          # root (prefix ist der Pfad)
@router.get("/{user_id}")                 # path param
@router.post("/")
@router.put("/{user_id}")
@router.patch("/{user_id}")
@router.delete("/{user_id}")
@router.get("/{user_id}/avatar")
@router.post("/{user_id}/avatar")         # multipart
```

**main.py** — direkte app-Endpoints (ohne Router):
```python
@app.get("/health")                       # kein Router-Prefix
@app.get("/api/version")
@app.options("/api/users")
```

### Flask (zweites Python-Projekt)

```
python/flask-test/
├── app.py
├── blueprints/
│   ├── users.py                         # Blueprint(url_prefix="/api/users")
│   ├── products.py
│   └── auth.py
└── requirements.txt
```

**blueprints/users.py** — Flask-spezifische Scanner-Cases:
```python
bp = Blueprint("users", __name__, url_prefix="/api/users")

@bp.route("/", methods=["GET"])           # GET /api/users
@bp.route("/<int:user_id>", methods=["GET", "PUT", "DELETE"])  # multi-method + Flask params
@bp.route("/", methods=["POST"])
@app.route("/health")                     # direkt auf app
```

---

## Projekt 3: Java — Spring Boot 3

**Framework:** Spring Boot 3.x + Spring Web MVC  
**OpenAPI:** springdoc-openapi (`/v3/api-docs`)  
**Auth:** Spring Security + JWT (`jjwt`)

### Dateistruktur

```
java/sonarwhale-test-api/
├── pom.xml
└── src/main/java/dev/sonarwhale/testapi/
    ├── SonarwhaleTestApiApplication.java
    ├── controller/
    │   ├── UsersController.java          ← @RestController + @RequestMapping("/api/users")
    │   ├── ProductsController.java
    │   ├── OrdersController.java
    │   ├── AuthController.java           ← @RequestMapping("/api/auth")
    │   ├── AdminController.java          ← @RequestMapping("/api/admin")
    │   └── HealthController.java
    ├── model/
    │   ├── User.java
    │   ├── Product.java
    │   ├── Order.java
    │   └── dto/
    ├── security/
    │   ├── JwtFilter.java
    │   └── SecurityConfig.java
    └── config/
        └── OpenApiConfig.java            ← springdoc security schemes
```

### Scanner-Stress-Tests (JavaScanner Edge Cases)

**UsersController.java** — deckt ab:
```java
@RestController
@RequestMapping("/api/users")
public class UsersController {

    @GetMapping                              // kein Pfad → nur Prefix
    @GetMapping("/{id}")                     // path param
    @GetMapping(path = "/search")            // named `path=` arg
    @PostMapping(consumes = "application/json")  // named arg, kein path
    @PutMapping("/{id}")
    @PatchMapping("/{id}")
    @DeleteMapping("/{id}")
    @GetMapping("/{id:[0-9]+}/avatar")       // Regex-Constraint
}
```

**ProductsController.java** — deckt ab:
```java
// Altes @RequestMapping-Style:
@RequestMapping(value = "/search", method = RequestMethod.GET)

// Multi-line Annotation:
@GetMapping(
    value = "/{id}/variants",
    produces = MediaType.APPLICATION_JSON_VALUE
)
```

---

## Implementierungsreihenfolge

### Phase A — C# (primäres Ziel, meiste Scanner-Coverage)
1. Projekt-Setup: `dotnet new webapi -n SonarwhaleTestApi`
2. `AuthController.cs` + JWT-Setup
3. `UsersController.cs` — alle 8 Endpoints + NonAction
4. `ProductsController.cs` — AcceptVerbs, Tilde, Search-Route
5. `AdminController.cs` — Area-Routing
6. `Program.cs` — Minimal API Endpoints (Health, Version, OPTIONS, HEAD)
7. Models + OpenAPI-Konfiguration
8. Manuelle Verifikation: Plugin lädt Spec, alle Gutter-Icons erscheinen

### Phase B — Python FastAPI
1. Projekt-Setup: `pip install fastapi uvicorn python-jose[cryptography]`
2. Pydantic-Models
3. `routers/auth.py` zuerst (unabhängig)
4. `routers/users.py` + Security-Dependencies
5. Restliche Router
6. Verifikation gegen `/openapi.json`

### Phase C — Python Flask
1. `pip install flask flask-jwt-extended flasgger`
2. Auth-Blueprint
3. Users-Blueprint (deckt Flask-spezifische Scanner-Patterns ab)
4. Verifikation mit Flasgger-Spec

### Phase D — Java Spring Boot
1. Spring Initializr: Web + Security + springdoc
2. Security-Konfiguration (JWT Filter)
3. Auth-Controller
4. UsersController — alle @XxxMapping-Varianten
5. ProductsController — Regex-Constraints, named args
6. Verifikation gegen `/v3/api-docs`

---

## Sonarwhale-Konfiguration für Testprojekte

Nach dem Start jedes Servers werden folgende Environments in `.idea/sonarwhale/environments.json`
eingetragen (manuell oder via Settings-Dialog):

```json
[
  { "name": "csharp-dev", "source": { "type": "ServerUrl", "host": "http://localhost", "port": 5000 } },
  { "name": "fastapi-dev", "source": { "type": "ServerUrl", "host": "http://localhost", "port": 8000 } },
  { "name": "flask-dev",   "source": { "type": "ServerUrl", "host": "http://localhost", "port": 5001 } },
  { "name": "java-dev",    "source": { "type": "ServerUrl", "host": "http://localhost", "port": 8080 } }
]
```

---

## Dinge, die bewusst NICHT implementiert werden

- Persistente Datenbank (alles in-memory / hardcoded) — kein Setup-Overhead
- Docker / docker-compose — jedes Projekt soll mit einem einzelnen Befehl starten
- Tests des Projekts selbst — die Projekte sind Testobjekte für Sonarwhale, keine eigenständigen TDD-Projekte
- GraphQL, WebSocket, gRPC — Sonarwhale ist ein HTTP/REST-Tool

---

## Offene Entscheidungen

- **Swagger UI mitbündeln?** Nützlich zum Vergleichen, aber nicht zwingend. Empfehlung: ja, für FastAPI und Spring Boot auto-included anyway, für C# optional aktivieren.
- **Flask Spec-Qualität**: Flasgger-Specs sind oft unvollständig. Alternative: `flask-openapi3`. Entscheidung wenn Flask-Projekt anlegen.
- **Java Build-Tool**: Maven (pom.xml) oder Gradle? Empfehlung: Maven — Spring Initializr-Standard, Rider-kompatibler.
