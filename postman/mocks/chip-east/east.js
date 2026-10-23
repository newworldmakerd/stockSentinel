const http = require('http');
http.createServer((req, res) => {
  const u = new URL(req.url, 'http://localhost');
  if (u.pathname !== '/api/qt/stock/kline/get') { res.writeHead(501); return res.end(); }
  if (u.searchParams.get('secid') !== '0.000731' || u.searchParams.get('fqt') !== '0' || u.searchParams.get('lmt') !== '210') {
    res.writeHead(422); return res.end();
  }
  const end = u.searchParams.get('end');
  const at = new Date(`${end.slice(0,4)}-${end.slice(4,6)}-${end.slice(6,8)}T00:00:00Z`);
  const klines = Array.from({length:210}, (_, i) => {
    const d = new Date(at); d.setUTCDate(at.getUTCDate() - 209 + i);
    return `${d.toISOString().slice(0,10)},10,10,10.5,9.5,100000,200000000,10,0,0,5`;
  });
  res.writeHead(200, {'Content-Type':'application/json'});
  res.end(JSON.stringify({data:{code:'000731',market:0,name:'四川美丰',klines}}));
}).listen(process.env.PORT);
