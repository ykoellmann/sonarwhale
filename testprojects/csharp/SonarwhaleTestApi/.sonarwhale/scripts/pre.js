// Global pre-script — runs before every request
// Sets X-Api-Key (Products) and Bearer JWT (Users, Orders).
// Admin/pre.js overrides Authorization with Basic Auth for admin endpoints.
// ── API Key (Products controller) ────────────────────────────────────────────
var apiKey = sw.env.get('api_key') || 'test-api-key-12345';
sw.request.setHeader('X-Api-Key', apiKey);


// ── Bearer JWT (Users, Orders controllers) ───────────────────────────────────
var token = sw.env.get('jwt_token');

if (!token) {
    var baseUrl  = sw.env.get('base_url') || 'http://localhost:5245';
    var username = sw.env.get('username') || 'user';
    var password = sw.env.get('password') || 'user123';

    var res = sw.http.post(
        baseUrl + '/api/Auth/login',
        JSON.stringify({ username: username, password: password }),
        { 'Content-Type': 'application/json' }
    );

    if (res.status === 200) {
        var body = res.json();
        if (body.accessToken) {
            sw.env.set('jwt_token', body.accessToken);
            token = body.accessToken;
        }
        if (body.refreshToken) {
            sw.env.set('refresh_token', body.refreshToken);
        }
    }
}

if (token) {
    sw.request.setHeader('Authorization', 'Bearer ' + token);
}