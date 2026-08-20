const http = require('http');
const { spawn } = require('child_process');

const PORT = Number(process.env.PORT || 10000);
const BACKEND_PORT = 10001;

const child = spawn(process.execPath, ['server.js'], {
  env: { ...process.env, PORT: String(BACKEND_PORT) },
  stdio: 'inherit'
});
child.on('exit', code => process.exit(code || 0));

function baseUrl(req) {
  const proto = String(req.headers['x-forwarded-proto'] || 'https').split(',')[0];
  const host = req.headers.host || 'localhost';
  return `${proto}://${host}`;
}

function configurePage(req) {
  const base = baseUrl(req);
  const manifest = `${base}/manifest.json`;
  return `<!doctype html><html><head><meta name="viewport" content="width=device-width"><title>USportz Configuration</title><style>body{margin:0;background:#08090c;color:#fff;font:16px system-ui;display:grid;place-items:center;min-height:100vh}main{width:min(620px,92vw);background:#12151b;padding:28px;border-radius:18px;box-sizing:border-box}h1{margin-top:0}input{width:100%;box-sizing:border-box;padding:13px;border-radius:9px;border:1px solid #343a46;background:#090b10;color:#fff;margin:8px 0 14px}a{display:block;text-align:center;padding:14px;border-radius:10px;background:#e21d2d;color:#fff;text-decoration:none;font-weight:700;margin-top:14px}.muted{color:#aeb6c2;line-height:1.5}</style></head><body><main><h1>🏆 USportz</h1><p class="muted">Your Render service is using the authorized Xtream credentials configured in Render. This page is the working Nuvio configuration endpoint.</p><label>Manifest URL</label><input readonly value="${manifest}"><a href="${manifest}">Open Manifest</a><p class="muted">In Nuvio, add the manifest URL above as an addon. Do not paste Xtream credentials into the manifest URL.</p></main></body></html>`;
}

const proxy = http.createServer((req, res) => {
  if (req.url === '/configure' || req.url === '/configure/') {
    const body = configurePage(req);
    res.writeHead(200, { 'content-type': 'text/html; charset=utf-8', 'cache-control': 'no-store' });
    return res.end(body);
  }

  const upstream = http.request({
    hostname: '127.0.0.1',
    port: BACKEND_PORT,
    path: req.url,
    method: req.method,
    headers: { ...req.headers, host: `127.0.0.1:${BACKEND_PORT}` }
  }, upstreamRes => {
    res.writeHead(upstreamRes.statusCode || 502, upstreamRes.headers);
    upstreamRes.pipe(res);
  });

  upstream.on('error', err => {
    res.writeHead(502, { 'content-type': 'application/json; charset=utf-8' });
    res.end(JSON.stringify({ ok: false, error: 'USportz backend unavailable', detail: err.message }));
  });

  req.pipe(upstream);
});

proxy.listen(PORT, '0.0.0.0', () => console.log(`USportz proxy listening on 0.0.0.0:${PORT}`));
