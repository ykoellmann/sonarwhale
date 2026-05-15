/// <reference path="../sw.d.ts" />
// Auth tag post-script — captures JWT token after a successful login

if (sw.response.status === 200) {
    try {
        var body = sw.response.json();
        if (body.accessToken) {
            sw.env.set('jwt_token', body.accessToken);
            console.log('[Auth] JWT token stored in env');
        }
        if (body.refreshToken) {
            sw.env.set('refresh_token', body.refreshToken);
        }
    } catch (e) {
        // Response is not JSON — not a login endpoint, skip
    }
}
