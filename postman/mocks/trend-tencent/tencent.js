const http = require('http');
http.createServer((req, res) => {
  const u = new URL(req.url, 'http://localhost');
  if (u.pathname !== '/ifzqgtimg/appstock/app/newfqkline/get') { res.writeHead(501); return res.end(); }
  const p = u.searchParams.get('param') || '';
  if (!p.startsWith('sh000300,day,') || !p.endsWith(',640,')) { res.writeHead(422); return res.end(); }
  const end = Date.parse('2025-10-20T00:00:00Z');
  const day = Array.from({length: 201}, (_, i) => [new Date(end - (200-i)*86400000).toISOString().slice(0,10), '1', String(100+i), '1', '1', '1']);
  res.writeHead(200, {'Content-Type': 'application/json'});
  res.end(JSON.stringify({code: 0, data: {sh000300: {qt: {sh000300: ['1', '沪深300', '000300']}, day}}}));
}).listen(process.env.PORT);
