"""Local Postman CLI simulation acceptance; no auth, cloud history or real sources."""
from datetime import date, datetime
from decimal import Decimal
import json
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time

root = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(root / 'quant-service'))
from app.providers import trend_index

cli = shutil.which('postman')
if not cli:
    raise SystemExit('Postman CLI 1.70+ required; simulation not run')
run = root / '.runtime' / ('index-postman-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f'))
run.mkdir(exist_ok=False)
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0))
    port = sock.getsockname()[1]
with (run/'simulation.ndjson').open('w', encoding='utf-8') as out, (run/'simulation.err').open('w', encoding='utf-8') as err:
    child = subprocess.Popen([cli, 'simulation', 'run', str(root/'postman/simulations/trend-index.sim.yaml'),
        '--port', str(port), '--output', 'ndjson', '--no-history'], cwd=root, stdout=out, stderr=err,
        creationflags=subprocess.CREATE_NO_WINDOW)
    try:
        for _ in range(150):
            if 'listening' in (run/'simulation.ndjson').read_text(encoding='utf-8'):
                break
            if child.poll() is not None:
                raise RuntimeError('SIMULATION_START_FAILED: inspect ' + str(run/'simulation.err'))
            time.sleep(0.1)
        else:
            raise RuntimeError('SIMULATION_NOT_READY')
        base = f'http://127.0.0.1:{port}'
        trend_index.PRIMARY_URL = base+'/east/api/qt/stock/kline/get'
        trend_index.BACKUP_URL = base+'/tencent/ifzqgtimg/appstock/app/newfqkline/get'
        result = trend_index.fetch_index(date(2025,10,20))
        assert result['source'] == trend_index.TENCENT and result['fallback_used']
        assert result['ma200'] == Decimal('200.5')
        assert result['source_failures'] == [{'source': trend_index.EASTMONEY, 'code': 'SOURCE_UNAVAILABLE'}]
        (run/'result.json').write_text(json.dumps({'status':'PASS','source':result['source'],'ma200':str(result['ma200'])},indent=2),encoding='utf-8')
        print('Postman simulation PASS: actual provider HTTP failed primary and used backup; logs=' + str(run))
    finally:
        subprocess.run(['taskkill','/PID',str(child.pid),'/T','/F'],capture_output=True,timeout=10,
                       creationflags=subprocess.CREATE_NO_WINDOW)
