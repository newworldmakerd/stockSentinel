"""Disposable local management preview: isolated schema, synthetic bars, no external APIs.

Usage: python scripts/custom_manager_preview.py start|stop
Build the frozen JAR referenced by .runtime/custom-staged-release.txt first.
Database credentials stay in child environments, never in arguments or files.
"""
from datetime import datetime
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import sys
import time
from urllib.request import urlopen
import uuid

ROOT = Path(__file__).resolve().parents[1]
POINTER = ROOT/'.runtime/custom-preview-state.txt'
PSQL = Path(r'C:\Program Files\PostgreSQL\18\bin\psql.exe')


def secret(name):
    if os.environ.get(name): return os.environ[name]
    import winreg
    for hive,key in [(winreg.HKEY_LOCAL_MACHINE,r'SYSTEM\CurrentControlSet\Control\Session Manager\Environment'),(winreg.HKEY_CURRENT_USER,'Environment')]:
        try:
            with winreg.OpenKey(hive,key) as reg:
                value=winreg.QueryValueEx(reg,name)[0]
                if value:return value
        except OSError:pass
    raise RuntimeError('MISSING_ENVIRONMENT: '+name)


def sql(text):
    env=os.environ.copy();env['PGPASSWORD']=secret('POSTGRES_PASSWORD')
    result=subprocess.run([str(PSQL),'-h','127.0.0.1','-U','myrag_user','-d','myrag','-X','-q','-v','ON_ERROR_STOP=1'],
        input=text,text=True,encoding='utf-8',env=env,capture_output=True,creationflags=subprocess.CREATE_NO_WINDOW)
    if result.returncode:raise RuntimeError('PREVIEW_SQL_FAILED (no credentials logged)')


def start():
    release=Path((ROOT/'.runtime/custom-staged-release.txt').read_text(encoding='utf-8'))
    jar=(release/'target/stocksentinel-0.1.0-SNAPSHOT.jar').resolve()
    if ROOT/'.runtime' not in jar.parents or not jar.is_file():raise RuntimeError('INVALID_PREVIEW_JAR')
    schema='stocksentinel_managerpreview_'+uuid.uuid4().hex
    if not re.fullmatch(r'stocksentinel_managerpreview_[a-f0-9]{32}',schema):raise RuntimeError('INVALID_PREVIEW_SCHEMA')
    with socket.socket() as sock:sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
    run=ROOT/'.runtime'/('manager-preview-'+datetime.now().strftime('%Y%m%d-%H%M%S-%f'));run.mkdir(exist_ok=False)
    sql(f'CREATE SCHEMA "{schema}";')
    # Minimal environment: do not inherit live provider/model/bot feature switches.
    env={k:v for k,v in os.environ.items() if k.upper() in {'PATH','SYSTEMROOT','WINDIR','COMSPEC','TEMP','TMP','USERPROFILE','APPDATA','LOCALAPPDATA','PROGRAMFILES','PROGRAMFILES(X86)'}}
    env.update(DB_URL=f'jdbc:postgresql://127.0.0.1:5432/myrag?currentSchema={schema}',POSTGRES_USER='myrag_user',
        POSTGRES_PASSWORD=secret('POSTGRES_PASSWORD'),CUSTOM_STRATEGIES_ENABLED='true',CUSTOM_STRATEGY_NOTIFICATIONS_ENABLED='false')
    java=Path(r'C:\Program Files\Java\jdk-21.0.10\bin\java.exe')
    with (run/'backend.out.log').open('xb') as out,(run/'backend.err.log').open('xb') as err:
        child=subprocess.Popen([str(java),'-jar',str(jar),'--server.address=127.0.0.1',f'--server.port={port}',
            f'--spring.flyway.default-schema={schema}'],cwd=ROOT,env=env,stdout=out,stderr=err,stdin=subprocess.DEVNULL,creationflags=subprocess.CREATE_NO_WINDOW)
    state=dict(pid=child.pid,port=port,schema=schema,jar=str(jar),directory=str(run),url=f'http://127.0.0.1:{port}/manage/index.html?sandbox=1')
    (run/'state.json').write_text(json.dumps(state,indent=2),encoding='utf-8');POINTER.write_text(str(run/'state.json'),encoding='utf-8')
    for _ in range(90):
        if child.poll() is not None:raise RuntimeError('PREVIEW_BOOT_FAILED: '+str(run))
        try:
            with urlopen(f'http://127.0.0.1:{port}/actuator/health',timeout=2) as response:
                if response.status==200:break
        except Exception:time.sleep(.5)
    else:raise RuntimeError('PREVIEW_BOOT_TIMEOUT: '+str(run))
    sql(f'''SET search_path TO "{schema}";
      INSERT INTO market_calendar(trade_date,exchange,is_open,source) VALUES('2026-10-21','CN',true,'SIMULATED'),('2026-10-22','CN',true,'SIMULATED');
      INSERT INTO stock_info(symbol,name,exchange) VALUES('000731','四川美丰（模拟）','SZ'),('600487','亨通光电（模拟）','SH');
      INSERT INTO stock_watchlist(symbol) VALUES('000731'),('600487');
      INSERT INTO market_daily_bar(symbol,trade_date,open_price,high_price,low_price,close_price,volume,amount,adjustment,source,fetched_at)
        SELECT symbol,d,9,11,8,10,100000,200000000,'QFQ','SIMULATED',now()-interval '20 minutes'
        FROM stock_info CROSS JOIN (VALUES(DATE '2026-10-21'),(DATE '2026-10-22')) AS dates(d);
      INSERT INTO market_indicator_daily(symbol,trade_date,ma5,ma10,ma20,ma60,dif,dea,macd_hist,volume_ratio,calculation_version,adjustment,calculated_at)
        SELECT symbol,d,8.5,8.7,CASE WHEN d=DATE '2026-10-22' THEN 9 ELSE 8.8 END,8,.5,.3,.4,1.8,'ma-macd-vol-v1','QFQ',now()-interval '10 minutes'
        FROM stock_info CROSS JOIN (VALUES(DATE '2026-10-21'),(DATE '2026-10-22')) AS dates(d);''')
    with urlopen(f'http://127.0.0.1:{port}/manage/index.html',timeout=5) as response:
        if response.status!=200:raise RuntimeError('PAGE_NOT_READY')
    print(json.dumps(state,ensure_ascii=False))


def stop():
    state_file=Path(POINTER.read_text(encoding='utf-8'));state=json.loads(state_file.read_text(encoding='utf-8'))
    schema=state['schema'];pid=int(state['pid'])
    if not re.fullmatch(r'stocksentinel_managerpreview_[a-f0-9]{32}',schema):raise RuntimeError('INVALID_PREVIEW_SCHEMA')
    inspected=subprocess.run(['powershell.exe','-NoProfile','-Command',f'Get-CimInstance Win32_Process -Filter "ProcessId = {pid}" | Select-Object -ExpandProperty CommandLine'],capture_output=True,text=True,creationflags=subprocess.CREATE_NO_WINDOW)
    command=inspected.stdout.strip()
    if command and state['jar'].lower() not in command.lower():raise RuntimeError('PREVIEW_PID_REUSED')
    if command:subprocess.run(['taskkill','/PID',str(pid),'/T','/F'],capture_output=True,timeout=10,creationflags=subprocess.CREATE_NO_WINDOW)
    sql(f'DROP SCHEMA IF EXISTS "{schema}" CASCADE;')
    state['stopped']=True;state_file.write_text(json.dumps(state,indent=2),encoding='utf-8')
    print('Preview stopped; isolated schema removed. Live Java/Python untouched.')


if __name__=='__main__':
    if len(sys.argv)!=2 or sys.argv[1] not in {'start','stop'}:raise SystemExit('Usage: custom_manager_preview.py start|stop')
    {'start':start,'stop':stop}[sys.argv[1]]()
