// Products tag pre-script — injects the API key required for all product endpoints

var apiKey = sw.env.get('api_key') || 'test-api-key-12345';
sw.request.setHeader('X-Api-Key', apiKey);
