"""Explicit operator start: real market, smart bot and group intent model; no Docker.

Reads credentials from process/User/Machine environment, never writes them to disk.
Keeps children alive after this launcher exits; close with the recorded process IDs.
"""
import json
import os
from pathlib import Path
import socket
import subprocess
import time
from datetime import datetime
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]


def environment_value(name):
    value = os.environ.get(name)
    if value:
        return value
    if os.name == "nt":
        import winreg
        for hive, path in [(winreg.HKEY_CURRENT_USER, "Environment"),
                           (winreg.HKEY_LOCAL_MACHINE, r"SYSTEM\CurrentControlSet\Control\Session Manager\Environment")]:
            try:
                with winreg.OpenKey(hive, path) as key:
                    value = winreg.QueryValueEx(key, name)[0]
                    if value:
                        return value
            except OSError:
                pass
    return None


def start():
    for port in (8000, 8080):
        with socket.socket() as probe:
            if probe.connect_ex(("127.0.0.1", port)) == 0:
                raise SystemExit(f"Port {port} already in use; inspect existing service before starting.")
    java = Path(environment_value("STOCKSENTINEL_JAVA_HOME") or r"C:\Program Files\Java\jdk-21.0.10") / "bin/java.exe"
    python = ROOT / ".venv/Scripts/python.exe"
    jar = ROOT / "backend/target/stocksentinel-0.1.0-SNAPSHOT.jar"
    if not all(p.is_file() for p in (java, python, jar)):
        raise SystemExit("Build the JAR and prepare JDK21/Python virtual environment first.")
    version = subprocess.run([str(java), "-version"], capture_output=True, text=True,
                             creationflags=subprocess.CREATE_NO_WINDOW, timeout=5)
    if version.returncode or 'version "21.' not in version.stderr:
        raise SystemExit("STOCKSENTINEL_JAVA_HOME must point to JDK21.")
    env = os.environ.copy()
    for name in ("POSTGRES_PASSWORD", "WECOM_BOT_ID", "WECOM_BOT_SECRET", "DEEPSEEK_API_KEY"):
        value = environment_value(name)
        if not value:
            raise SystemExit(f"Missing environment variable: {name}")
        env[name] = value
    for name in ("WECOM_CHAT_ID", "WECOM_CHAT_IDS"):
        value = environment_value(name)
        if value:
            env[name] = value
    if not (env.get("WECOM_CHAT_ID", "").strip() or env.get("WECOM_CHAT_IDS", "").strip()):
        raise SystemExit("Configure WECOM_CHAT_ID or WECOM_CHAT_IDS")
    env["LLM_API_KEY"] = env["DEEPSEEK_API_KEY"]
    for name in ("WECOM_DOC_ENABLED", "WECOM_DOC_SCHEDULE_ENABLED", "WECOM_DOC_MCP_URL",
                 "TREND_ENABLED", "TREND_SCHEDULE_ENABLED", "TREND_MIN_AMOUNT", "TREND_RISK_RATE",
                 "TREND_STOCK_CAP", "TREND_PORTFOLIO_CAP", "TREND_INITIAL_ATR", "TREND_TRAILING_ATR"):
        value = environment_value(name)
        if value:
            env[name] = value
    env.update(DB_URL="jdbc:postgresql://localhost:5432/myrag", POSTGRES_USER="myrag_user",
               MARKET_ENABLED="true", INTRADAY_ENABLED="true", INTRADAY_SCHEDULE_ENABLED="true", INTRADAY_MA60_ENABLED="true", SCAN_ALERTS_ENABLED="true",
               WECOM_ENABLED="true", WECOM_COMMANDS_ENABLED="true", WECOM_CHAT_ASSISTANT_ENABLED="true", WECOM_MODE="SMART_BOT",
               WECOM_COMMANDS_BASE_URL="https://api.deepseek.com", WECOM_COMMANDS_MODEL="deepseek-flash",
               LLM_ENABLED="true", LLM_BASE_URL="https://api.deepseek.com", LLM_MODEL="deepseek-flash", JOBS_ENABLED="true", DAILY_SCAN_SCHEDULE_ENABLED="true", PERSONAL_PRICE_ENABLED="true", T_OBSERVATION_ENABLED="true",
               PYTHON_URL="http://127.0.0.1:8000", MINUTE_HTTP_TIMEOUT_SECONDS="45", INTRADAY_SCAN_BUDGET_SECONDS="55", WECOM_RECONNECT_COOLDOWN_SECONDS="300",
               DAILY_REPORT_ENABLED="true", DAILY_REPORT_SCHEDULE_ENABLED="true", SCREENING_ENABLED="true", PULLBACK_ENABLED="true", PULLBACK_DIGEST_ENABLED="true", SCREENING_LLM_ENABLED="true")
    # Python receives no business database/model/bot credentials.
    quant_env = {k: v for k, v in os.environ.items() if not any(word in k.upper() for word in
                 ("PASSWORD", "SECRET", "TOKEN", "API_KEY", "WECOM", "POSTGRES", "DB_URL", "LLM", "DEEPSEEK"))}
    quant_env["MARKET_PROVIDER_ENABLED"] = "true"
    quant_env["MARKET_MINUTE_MIRROR_ENABLED"] = "true"
    quant_env["MARKET_TENCENT_BACKUP_ENABLED"] = "true"
    quant_env["MARKET_TENCENT_DAILY_BACKUP_ENABLED"] = "true"
    quant_env["MARKET_RSI_SINA_BACKUP_ENABLED"] = "true"
    quant_env["MARKET_RSI_BUDGET_SECONDS"] = "30"
    run = ROOT / ".runtime" / datetime.now().strftime("%Y%m%d-%H%M%S-%f")
    run.mkdir(parents=True, exist_ok=False)
    processes = []
    for name, args, child_env in [
        ("quant", [str(python), "-m", "uvicorn", "app.main:app", "--app-dir", str(ROOT / "quant-service"), "--host", "127.0.0.1", "--port", "8000"], quant_env),
        ("backend", [str(java), "-jar", str(jar), "--server.address=127.0.0.1", "--server.port=8080"], env),
    ]:
        with (run / f"{name}.out.log").open("xb") as out, (run / f"{name}.err.log").open("xb") as err:
            child = subprocess.Popen(args, cwd=ROOT, env=child_env, stdin=subprocess.DEVNULL,
                                     stdout=out, stderr=err, creationflags=subprocess.CREATE_NO_WINDOW)
        processes.append({"name": name, "pid": child.pid})
    state = {"startedAt": datetime.now().astimezone().isoformat(), "processes": processes}
    (run / "processes.json").write_text(json.dumps(state, indent=2), encoding="utf-8")
    for url in ("http://127.0.0.1:8000/health", "http://127.0.0.1:8080/actuator/health"):
        deadline = time.monotonic() + 45
        while True:
            try:
                with urlopen(url, timeout=2) as response:
                    if response.status == 200:
                        break
            except Exception:
                pass
            if time.monotonic() >= deadline:
                raise SystemExit(f"Service not ready; inspect logs in {run}. Process IDs saved.")
            time.sleep(0.5)
    print(json.dumps({"status": "RUNNING", "stateDirectory": str(run), **state}, ensure_ascii=False))


if __name__ == "__main__":
    start()
