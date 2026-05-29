// Users tag post-script — common assertions for all /api/users responses

var s = sw.response.status;

sw.test('Status is not 401 Unauthorized', function () {
    sw.expect(s).toBe(s === 401 ? 0 : s);
});

sw.test('Content-Type is JSON', function () {
    var ct = sw.response.headers['content-type'] || '';
    sw.expect(ct).toContain('application/json');
});

if (s === 401) {
    sw.env.set('jwt_token', '');
    console.warn('[Users Post] 401 received — JWT cleared, will re-login on next request');
}
