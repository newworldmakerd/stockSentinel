const http = require('http');
http.createServer((req, res) => {
  const u = new URL(req.url, 'http://localhost');
  if (u.pathname !== '/api/qt/stock/kline/get') { res.writeHead(501); return res.end(); }
  if (u.searchParams.get('secid') !== '1.000300') { res.writeHead(422); return res.end(); }
  res.writeHead(500, {'Content-Type': 'text/html'});
  res.end('<html>simulated primary outage</html>');
}).listen(process.env.PORT);
