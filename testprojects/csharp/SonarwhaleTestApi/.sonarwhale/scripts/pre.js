/// <reference path="sw.d.ts" />
// Global pre-script — runs before every request

console.log('[→] ' + sw.request.method + ' ' + sw.request.url);
