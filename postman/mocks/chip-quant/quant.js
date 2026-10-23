const http = require('http');
http.createServer((req, res) => {
  const u = new URL(req.url, 'http://localhost');
  if (u.pathname !== '/internal/chips/snapshot') { res.writeHead(501); return res.end(); }
  const symbol = u.searchParams.get('symbol');
  if (symbol === '000002') { res.writeHead(503, {'Content-Type':'text/html'}); return res.end('<html>simulated outage</html>'); }
  res.writeHead(200, {'Content-Type':'application/json'});
  res.end(JSON.stringify({symbol,name:'四川美丰',as_of:u.searchParams.get('as_of'),source:'CHIP_EASTMONEY',version:'akshare-cyq-210-v1',
    adjustment:'NONE',history_size:210,algorithm_hash:'a'.repeat(64),window_hash:'b'.repeat(64),average_cost:'10',close:'10.2',volume:'10000',
    profit_ratio:'0.55',low90:'9',high90:'11',low70:'9.4',high70:'10.6',concentration90:'0.10',concentration70:'0.06',
    average20_amount:'100000000',observed_at:'2026-10-21T10:21:05+08:00'}));
}).listen(process.env.PORT);
