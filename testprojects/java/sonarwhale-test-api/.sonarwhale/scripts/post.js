// Global post-script — runs after every request

var s = sw.response.status;
console.log('[←] ' + s + ' (' + sw.request.method + ' ' + sw.request.url + ')');

sw.test('No server error (5xx)', function () {
    sw.expect(s).toBe(s < 500 ? s : 0);
});
