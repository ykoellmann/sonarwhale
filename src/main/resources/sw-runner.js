#!/usr/bin/env node
// Sonarwhale sw-runner.js — Node.js runner that exposes the `sw` API to pre/post scripts.
// Launched by the plugin with --inspect-brk=127.0.0.1:<port> for IDE debugging, or without
// the flag for normal (non-debug) script execution.
//
// Usage: node [--inspect-brk=...] sw-runner.js <contextJsonPath> <scriptPath>
//
// Flow:
//  1. Read sw_context.json  (env, request, optional response)
//  2. Expose `sw` global
//  3. require() the user script so the IDE debugger can set breakpoints inside it
//  4. Write sw_context.out.json  (mutated env, request, testResults)

'use strict';

const fs     = require('fs');
const path   = require('path');
const vm     = require('vm');
const Module = require('module');

// ── Args ──────────────────────────────────────────────────────────────────────
const [,, contextJsonPath, scriptPath] = process.argv;
if (!contextJsonPath || !scriptPath) {
    process.stderr.write('Usage: sw-runner.js <contextJsonPath> <scriptPath>\n');
    process.exit(1);
}

// ── Load context ──────────────────────────────────────────────────────────────
const ctx = JSON.parse(fs.readFileSync(contextJsonPath, 'utf8'));

const envMap   = Object.assign({}, ctx.env || {});
const request  = Object.assign({}, ctx.request, {
    headers: Object.assign({}, ctx.request.headers)
});
const testResults = [];

// ── sw.http ───────────────────────────────────────────────────────────────────
// Synchronous HTTP via spawnSync so user scripts can write `var res = sw.http.post(...)`
// without async/await — matching the behaviour of the Rhino engine in run mode.
const { spawnSync } = require('child_process');

const httpLogs = [];

const SYNC_HTTP_SCRIPT = `
'use strict';
const http = require('http'), https = require('https');
const a = JSON.parse(process.env.SW_HTTP_ARGS);
let parsed;
try { parsed = new URL(a.url); }
catch (e) { process.stdout.write(JSON.stringify({ status: 0, headers: {}, body: '', error: 'Invalid URL: ' + a.url })); process.exit(0); }
const lib = parsed.protocol === 'https:' ? https : http;
const bodyBuf = a.body ? Buffer.from(a.body, 'utf8') : null;
const hdrs = Object.assign({}, a.headers || {});
if (bodyBuf) hdrs['Content-Length'] = bodyBuf.length;
const req = lib.request({
    hostname: parsed.hostname,
    port: parsed.port || (parsed.protocol === 'https:' ? 443 : 80),
    path: parsed.pathname + (parsed.search || ''),
    method: a.method.toUpperCase(),
    headers: hdrs
}, res => {
    const chunks = [];
    res.on('data', c => chunks.push(c));
    res.on('end', () => {
        const body = Buffer.concat(chunks).toString('utf8');
        const respHeaders = {};
        for (const [k, v] of Object.entries(res.headers || {}))
            respHeaders[k] = Array.isArray(v) ? v[0] : v;
        process.stdout.write(JSON.stringify({ status: res.statusCode, headers: respHeaders, body }));
    });
});
req.on('error', err => { process.stdout.write(JSON.stringify({ status: 0, headers: {}, body: '', error: err.message })); });
if (bodyBuf) req.write(bodyBuf);
req.end();
`;

function swHttpRequest(method, url, body, headers) {
    const start = Date.now();
    const args = JSON.stringify({ method, url, body: body || null, headers: headers || {} });
    const result = spawnSync(process.execPath, ['-e', SYNC_HTTP_SCRIPT], {
        env: Object.assign({}, process.env, { SW_HTTP_ARGS: args }),
        timeout: 30000,
        encoding: 'utf8'
    });
    const duration = Date.now() - start;
    if (result.error || result.status !== 0) {
        const err = (result.stderr || String(result.error || 'unknown error')).trim();
        process.stderr.write('[sw.http] subprocess failed: ' + err + '\n');
        httpLogs.push({ method, url, status: 0, durationMs: duration, requestHeaders: headers || {}, requestBody: body || null, responseHeaders: {}, responseBody: '', error: err });
        return { status: 0, headers: {}, body: '', error: err, json: () => null };
    }
    try {
        const parsed = JSON.parse(result.stdout);
        httpLogs.push({ method, url, status: parsed.status, durationMs: duration, requestHeaders: headers || {}, requestBody: body || null, responseHeaders: parsed.headers || {}, responseBody: parsed.body || '', error: null });
        return Object.assign(parsed, { json: () => JSON.parse(parsed.body) });
    } catch (e) {
        process.stderr.write('[sw.http] failed to parse response: ' + e.message + ' | stdout: ' + result.stdout + '\n');
        httpLogs.push({ method, url, status: 0, durationMs: duration, requestHeaders: headers || {}, requestBody: body || null, responseHeaders: {}, responseBody: result.stdout, error: e.message });
        return { status: 0, headers: {}, body: result.stdout, error: 'Failed to parse response: ' + e.message, json: () => null };
    }
}

// ── sw global object ──────────────────────────────────────────────────────────
const sw = {
    env: {
        get: (key)        => envMap[key],
        set: (key, value) => { envMap[key] = String(value); }
    },
    request: {
        get url()     { return request.url; },
        get method()  { return request.method; },
        get headers() { return request.headers; },
        get body()    { return request.body; },
        setHeader: (key, value) => { request.headers[key] = String(value); },
        setBody:   (body)       => { request.body = String(body); },
        setUrl:    (url)        => { request.url  = String(url); }
    },
    http: {
        get:     (url, headers)               => swHttpRequest('GET',   url, null, headers),
        post:    (url, body, headers)         => swHttpRequest('POST',  url, body, headers),
        request: (method, url, body, headers) => swHttpRequest(method, url, body, headers)
    },
    test: (name, fn) => {
        try {
            const ret = fn();
            if (ret && typeof ret.then === 'function') {
                ret.then(
                    ()  => testResults.push({ name, passed: true,  error: null }),
                    (e) => testResults.push({ name, passed: false, error: String(e) })
                );
            } else {
                const passed = ret !== false;
                testResults.push({ name, passed, error: passed ? null : 'Test function returned false' });
            }
        } catch (e) {
            testResults.push({ name, passed: false, error: String(e) });
        }
    },
    expect: (actual) => ({
        toBe:       (expected) => { const p = actual === expected;                  testResults.push({ name: 'expect.toBe',        passed: p, error: p ? null : `Expected ${expected} but got ${actual}` }); },
        toEqual:    (expected) => { const p = String(actual) === String(expected);  testResults.push({ name: 'expect.toEqual',      passed: p, error: p ? null : `Expected ${expected} but got ${actual}` }); },
        toBeTruthy: ()         => { const p = !!actual && actual !== 'false' && actual !== '0'; testResults.push({ name: 'expect.toBeTruthy', passed: p, error: p ? null : `Expected truthy but got ${actual}` }); },
        toBeFalsy:  ()         => { const p = !actual || actual === 'false' || actual === '0';  testResults.push({ name: 'expect.toBeFalsy',  passed: p, error: p ? null : `Expected falsy but got ${actual}` }); },
        toContain:  (substr)   => { const p = String(actual).includes(String(substr)); testResults.push({ name: 'expect.toContain', passed: p, error: p ? null : `Expected "${actual}" to contain "${substr}"` }); }
    })
};

if (ctx.response) {
    const r = ctx.response;
    sw.response = {
        status:  r.status,
        headers: r.headers,
        body:    r.body,
        json:    () => JSON.parse(r.body)
    };
}

// ── Execute user script ───────────────────────────────────────────────────────
// We load the script via Module._compile so that:
//  - The file path is registered with the Node.js debugger (enables IDE breakpoints)
//  - The sw variable is injected into the script's scope via the module wrapper
//  - require/module/__filename/__dirname all work as expected
//
// This is the same technique used by ts-node, Jest, and other script runners
// that need to inject globals into required modules.

const absScriptPath = path.resolve(scriptPath);

// Inject `sw` into the global scope for the duration of script execution.
// Using global is the simplest approach that works with both require() and
// top-level code, without modifying the module wrapper.
global.sw = sw;

// Patch require so that TypeScript reference directives don't throw
const originalCompile = Module.prototype._compile;
Module.prototype._compile = function(content, filename) {
    // Strip `/// <reference path="..." />` lines that Node.js doesn't understand
    const cleaned = content.replace(/^\/\/\/\s*<reference[^>]*\/>\s*$/gm, '');
    return originalCompile.call(this, cleaned, filename);
};

let scriptError = null;
(async () => {
    try {
        // require() triggers the debugger to register the file's source map.
        // Breakpoints set in the IDE for this file will now work.
        delete require.cache[absScriptPath];  // force reload
        require(absScriptPath);

        // If the script returned a Promise (top-level async), wait for it.
        // Module exports are not awaited here — scripts should use sw.test() for async work.
    } catch (e) {
        scriptError = e;
        process.stderr.write(`Script error in ${path.basename(absScriptPath)}:\n${e.stack || e}\n`);
    }

    // Restore Module._compile
    Module.prototype._compile = originalCompile;

    // ── Write results ─────────────────────────────────────────────────────────
    const out = { env: envMap, request, testResults, httpLogs };
    const outPath = contextJsonPath.replace(/\.json$/, '.out.json');
    try {
        fs.writeFileSync(outPath, JSON.stringify(out, null, 2), 'utf8');
    } catch (e) {
        process.stderr.write(`Failed to write results: ${e.message}\n`);
    }

    process.exitCode = scriptError ? 1 : 0;
})();
