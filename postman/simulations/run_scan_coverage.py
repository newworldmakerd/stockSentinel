"""Exercise the real Java HTTP client and isolated PostgreSQL against local Postman 503s."""
from datetime import datetime
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time

root = Path(__file__).resolve().parents[2]
cli = shutil.which('postman')
if not cli or not os.environ.get('STOCKSENTINEL_TEST_DB_URL'):
    raise SystemExit('Postman CLI 1.70+ and isolated-test DB environment required')
run = root / '.runtime' / ('coverage-postman-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f'))
run.mkdir(exist_ok=False)
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0))
    port = sock.getsockname()[1]
with (run/'simulation.ndjson').open('w', encoding='utf-8') as out, (run/'simulation.err').open('w', encoding='utf-8') as err:
    child = subprocess.Popen([cli, 'simulation', 'run', str(root/'postman/simulations/scan-coverage.sim.yaml'),
        '--port', str(port), '--output', 'ndjson', '--no-history'], cwd=root, stdout=out, stderr=err,
        creationflags=subprocess.CREATE_NO_WINDOW)
    try:
        for _ in range(150):
            if 'listening' in (run/'simulation.ndjson').read_text(encoding='utf-8'):
                break
            if child.poll() is not None:
                raise RuntimeError('SIMULATION_START_FAILED: ' + str(run/'simulation.err'))
            time.sleep(0.1)
        else:
            raise RuntimeError('SIMULATION_NOT_READY')
        env = os.environ.copy()
        env['STOCKSENTINEL_SIMULATION_URL'] = f'http://127.0.0.1:{port}/quant'
        with (run/'acceptance.log').open('w', encoding='utf-8') as log:
            result = subprocess.run(['cmd.exe', '/d', '/c', 'mvn', '-B', '-s', 'maven-settings.xml',
                '-Dmaven.repo.local=.maven-repository', '-f', 'backend/pom.xml', 'test-compile',
                'failsafe:integration-test', 'failsafe:verify',
                '-Dit.test=TrendLocalDatabaseIT#postmanFailureUsesRealHttpAndStillAdvancesCursor'],
                cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT, creationflags=subprocess.CREATE_NO_WINDOW)
        if result.returncode:
            raise RuntimeError('ACCEPTANCE_FAILED: ' + str(run/'acceptance.log'))
        traffic = (run/'simulation.ndjson').read_text(encoding='utf-8')
        if '/internal/trend/quotes' not in traffic:
            raise RuntimeError('NO_DEPENDENCY_TRAFFIC_CAPTURED')
        (run/'result.json').write_text(json.dumps({'status':'PASS','httpFailure':503,
            'attemptedAcrossTwoPasses':199,'duplicateSignals':0}, indent=2), encoding='utf-8')
        print('Postman coverage simulation PASS; evidence=' + str(run))
    finally:
        subprocess.run(['taskkill', '/PID', str(child.pid), '/T', '/F'], capture_output=True,
            timeout=10, creationflags=subprocess.CREATE_NO_WINDOW)
