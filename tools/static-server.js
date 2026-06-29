const http = require('http');
const fs = require('fs');
const path = require('path');

const port = Number(process.env.PORT || 8766);
const root = path.resolve(process.env.AFFILIATE_FACTORY_ROOT || path.join(__dirname, '..'));
const types = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.webp': 'image/webp',
  '.mp4': 'video/mp4',
};

http.createServer((req, res) => {
  const url = new URL(req.url, 'http://127.0.0.1');
  const requested = url.pathname === '/' ? '/index.html' : url.pathname;
  const filePath = path.resolve(root, `.${decodeURIComponent(requested)}`);

  if (!filePath.startsWith(root)) {
    res.writeHead(403);
    res.end('Forbidden');
    return;
  }

  fs.readFile(filePath, (err, data) => {
    if (err) {
      res.writeHead(404);
      res.end('Not found');
      return;
    }

    res.writeHead(200, {
      'Content-Type': types[path.extname(filePath).toLowerCase()] || 'application/octet-stream',
    });
    res.end(data);
  });
}).listen(port, '127.0.0.1', () => {
  console.log(`Affiliate Factory preview: http://127.0.0.1:${port}/`);
});
