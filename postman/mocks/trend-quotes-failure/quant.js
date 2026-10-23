const http = require('http');
http.createServer((req, res) => {
  if (req.method !== 'POST' || req.url !== '/internal/trend/quotes') {
    res.writeHead(501); return res.end();
  }
  let body = '';
  req.on('data', x => { body += x; });
  req.on('end', () => {
    const symbols = JSON.parse(body).symbols;
    if (!Array.isArray(symbols) || symbols.length > 100) {
      res.writeHead(422); return res.end();
    }
    res.writeHead(503, {'Content-Type': 'text/html'});
    res.end('<html>simulated quote upstream unavailable</html>');
  });
}).listen(process.env.PORT);
