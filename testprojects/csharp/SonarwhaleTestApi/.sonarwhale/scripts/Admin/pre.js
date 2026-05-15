/// <reference path="../sw.d.ts" />
// Admin tag pre-script — injects Basic Auth for all admin endpoints

var user = sw.env.get('admin_user') || 'admin';
var pass = sw.env.get('admin_pass') || 'admin123';
var encoded = java.util.Base64.getEncoder().encodeToString(
    new java.lang.String(user + ':' + pass).getBytes('UTF-8')
);
sw.request.setHeader('Authorization', 'Basic ' + encoded);
