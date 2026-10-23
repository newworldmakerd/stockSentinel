"""Actual Python SDK calculation and Java client against local Postman dependencies."""
from datetime import datetime, date
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import sys
import time

root = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(root/'quant-service'))
from app.providers import chips

cli = shutil.which('postman')
if not cli or not os.environ.get('STOCKSENTINEL_TEST_DB_URL'):
    raise SystemExit('Postman CLI and isolated PostgreSQL test environment required')
run = root/'.runtime'/('chips-postman-'+datetime.now().strftime('%Y%m%d-%H%M%S-%f'))
run.mkdir(exist_ok=False)
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
with (run/'simulation.ndjson').open('w',encoding='utf-8') as out, (run/'simulation.err').open('w',encoding='utf-8') as err:
    child = subprocess.Popen([cli,'simulation','run',str(root/'postman/simulations/chips.sim.yaml'),
        '--port',str(port),'--output','ndjson','--no-history'],cwd=root,stdout=out,stderr=err,
        creationflags=subprocess.CREATE_NO_WINDOW)
    try:
        for _ in range(150):
            if 'listening' in (run/'simulation.ndjson').read_text(encoding='utf-8'): break
            if child.poll() is not None: raise RuntimeError('SIMULATION_START_FAILED: '+str(run/'simulation.err'))
            time.sleep(.1)
        else: raise RuntimeError('SIMULATION_NOT_READY')
        chips.URL = f'http://127.0.0.1:{port}/east/api/qt/stock/kline/get'
        result = chips.fetch('000731',date(2025,10,20))
        assert result['concentration90'] <= .10 and result['concentration70'] <= .06
        env = os.environ.copy();env['STOCKSENTINEL_CHIP_SIMULATION_URL'] = f'http://127.0.0.1:{port}/quant'
        with (run/'acceptance.log').open('w',encoding='utf-8') as log:
            code = subprocess.run(['cmd.exe','/d','/c','mvn','-B','-s','maven-settings.xml',
                '-Dmaven.repo.local=.maven-repository','-f','backend/pom.xml','test-compile',
                'failsafe:integration-test','failsafe:verify',
                '-Dit.test=TrendLocalDatabaseIT#postmanChipsRealHttpMatchesAndCapturesHtmlFailure'],
                cwd=root,env=env,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW).returncode
        if code: raise RuntimeError('ACCEPTANCE_FAILED: '+str(run/'acceptance.log'))
        events = [json.loads(line) for line in (run/'simulation.ndjson').read_text(encoding='utf-8').splitlines()]
        requests = [e for e in events if e.get('event') == 'request']
        assert any(e.get('routeKey') == 'east' and e.get('statusCode') == 200 for e in requests)
        assert any(e.get('routeKey') == 'quant' and e.get('statusCode') == 503 for e in requests)
        assert any(e.get('routeKey') == 'quant' and e.get('statusCode') == 200 for e in requests)
        evidence = dict(status='PASS',pythonActualSdkAndHttp=True,javaActualHttp=True,
            concentration90=str(result['concentration90']),concentration70=str(result['concentration70']),
            html503Recorded=True,realMarketCalls=False,realWecomMessages=False)
        (run/'result.json').write_text(json.dumps(evidence,indent=2),encoding='utf-8')
        print('Postman chips simulation PASS; evidence='+str(run))
    finally:
        subprocess.run(['taskkill','/PID',str(child.pid),'/T','/F'],capture_output=True,timeout=10,creationflags=subprocess.CREATE_NO_WINDOW)
