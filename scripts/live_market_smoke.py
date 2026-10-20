"""Explicitly authorized live TASK-02 smoke test. Reads DB credentials only from env."""
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import urllib.request
import urllib.error

root=Path(__file__).resolve().parents[1]


def port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1",0))
        return sock.getsockname()[1]


def request(url,method="GET",timeout=110):
    req=urllib.request.Request(url,method=method)
    try:
        with urllib.request.urlopen(req,timeout=timeout) as response:
            return json.load(response)
    except urllib.error.HTTPError as failure:
        raise RuntimeError(f"Local endpoint returned HTTP {failure.code}") from None


def ready(url,process):
    for attempt in range(300):
        if process.poll() is not None:
            raise RuntimeError("Service exited before readiness; inspect local log")
        try:
            request(url,timeout=1)
            return
        except (OSError,RuntimeError):
            time.sleep(0.2)
    raise RuntimeError("Service readiness timed out")


java_port,python_port=port(),port()
progress={"status":"RUNNING","symbol":"600487","adjustment":"QFQ"}
processes=[]
logs=[]
try:
    quant_env=os.environ.copy()
    # The compute service never receives business database or model credentials.
    for key in list(quant_env):
        if key.startswith(("POSTGRES_","STOCKSENTINEL_TEST_","LLM_","WECOM_")) or key=="DB_URL":
            quant_env.pop(key,None)
    quant_env["MARKET_PROVIDER_ENABLED"]="true"
    java_env=os.environ.copy()
    java_env.update(MARKET_ENABLED="true",PYTHON_URL=f"http://127.0.0.1:{python_port}",LLM_ENABLED="false",WECOM_ENABLED="false")
    for filename,command,environment in [
        ("task02-live-python.log",[sys.executable,"-m","uvicorn","app.main:app","--app-dir","quant-service","--host","127.0.0.1","--port",str(python_port)],quant_env),
        ("task02-live-java.log",["java","-jar","backend/target/stocksentinel-0.1.0-SNAPSHOT.jar","--server.address=127.0.0.1",f"--server.port={java_port}"],java_env),
    ]:
        logfile=(root/filename).open("w",encoding="utf-8")
        logs.append(logfile)
        processes.append(subprocess.Popen(command,cwd=root,env=environment,stdout=logfile,stderr=subprocess.STDOUT,
            creationflags=getattr(subprocess,"CREATE_NO_WINDOW",0)))
    ready(f"http://127.0.0.1:{python_port}/health",processes[0])
    ready(f"http://127.0.0.1:{java_port}/actuator/health",processes[1])
    base=f"http://127.0.0.1:{java_port}/api/v1"
    print("Both services ready; synchronizing live stock catalog",flush=True)
    catalog=request(base+"/stocks/sync","POST")
    progress["catalog"]=catalog
    print("Catalog result:",json.dumps(catalog),flush=True)
    suffix="/market/600487/daily/import?start=2025-01-01&end=2026-10-19&adjustment=QFQ"
    first=request(base+suffix,"POST")
    progress["first_import"]=first
    print("First import:",json.dumps(first),flush=True)
    read_path="/market/600487/daily?start=2025-01-01&end=2026-10-19&adjustment=QFQ"
    before=request(base+read_path)
    if len(before)<120:
        raise RuntimeError("Fewer than 120 historical rows; cannot accept live daily data")
    second=request(base+suffix,"POST")
    after=request(base+read_path)
    if len(after)!=len(before):
        raise RuntimeError("Repeated import changed row count")
    summary={"catalog":catalog,"first_import":first,"second_import":second,"row_count":len(after),
        "earliest":after[0]["tradeDate"],"latest":after[-1]["tradeDate"],"symbol":"600487","adjustment":"QFQ"}
    (root/"docs/task02-live-result.json").write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding="utf-8")
    print("Live import and idempotency: PASS",json.dumps(summary,ensure_ascii=False),flush=True)
except Exception as failure:
    progress.update(status="FAILED",failure_type=type(failure).__name__)
    (root/"docs/task02-live-result.json").write_text(json.dumps(progress,ensure_ascii=False,indent=2),encoding="utf-8")
    raise
finally:
    for process in reversed(processes):
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
    for logfile in logs:
        logfile.close()
    print("Temporary services stopped",flush=True)