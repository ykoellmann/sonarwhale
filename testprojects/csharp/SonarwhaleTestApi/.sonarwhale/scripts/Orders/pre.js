/// <reference path="../sw.d.ts" />
// Orders tag pre-script — ensures a valid, non-expired JWT is attached to every request

function isTokenValid(token) {
    try {
        var parts = token.split('.');
        if (parts.length !== 3) return false;
        var payload = parts[1].replace(/-/g, '+').replace(/_/g, '/');
        while (payload.length % 4 !== 0) payload += '=';
        var decoded = java.util.Base64.getDecoder().decode(payload);
        var claims = JSON.parse(new java.lang.String(decoded, 'UTF-8'));
        if (!claims.exp) return true;
        return claims.exp > Math.floor(Date.now() / 1000) + 30;
    } catch (e) {
        return false;
    }
}

var token = sw.env.get('jwt_token');

if (!isTokenValid(token)) {
    if (token) console.log('[Orders Pre] JWT expired or invalid — re-logging in');
    else console.log('[Orders Pre] No JWT found — logging in as user/user123');
    var baseUrl = sw.env.get('base_url') || 'http://localhost:5157';
    var res = sw.http.post(
        baseUrl + '/api/auth/login',
        JSON.stringify({ username: 'user', password: 'user123' }),
        { 'Content-Type': 'application/json' }
    );
    if (res.status === 200) {
        token = res.json().accessToken;
        sw.env.set('jwt_token', token);
        console.log('[Orders Pre] JWT obtained');
    } else {
        console.error('[Orders Pre] Auto-login failed (' + res.status + ')');
        token = null;
    }
}

if (token) {
    sw.request.setHeader('Authorization', 'Bearer ' + token);
}
