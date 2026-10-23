param(
    [ValidateSet('A','03')][string]$Stage='A',
    [switch]$UseLocalDatabase
)
$ErrorActionPreference='Stop'
$taskRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$taskStamp=Get-Date -Format 'yyyyMMdd-HHmmss-fff'
$taskLogs=Join-Path $taskRoot ".runtime/verify-opt-$Stage-$taskStamp"
New-Item -ItemType Directory -Path $taskLogs | Out-Null
$taskNames=@('TREND_ENABLED','TREND_SCHEDULE_ENABLED','WECOM_DOC_ENABLED','WECOM_DOC_SCHEDULE_ENABLED','WECOM_DOC_MCP_URL','WECOM_ENABLED','WECOM_COMMANDS_ENABLED','WECOM_CHAT_ASSISTANT_ENABLED','WECOM_BOT_ID','WECOM_BOT_SECRET','WECOM_CHAT_ID','WECOM_CHAT_IDS','DEEPSEEK_API_KEY','LLM_API_KEY','LLM_ENABLED','MARKET_PROVIDER_ENABLED','MARKET_ENABLED','INTRADAY_ENABLED','INTRADAY_SCHEDULE_ENABLED','JOBS_ENABLED','DAILY_SCAN_SCHEDULE_ENABLED','SCREENING_ENABLED','PULLBACK_ENABLED','DAILY_REPORT_ENABLED','T_OBSERVATION_ENABLED','SCAN_ALERTS_ENABLED','PERSONAL_PRICE_ENABLED')
$taskBefore=@{}
try {
    foreach($taskName in $taskNames){$taskBefore[$taskName]=[Environment]::GetEnvironmentVariable($taskName,'Process');[Environment]::SetEnvironmentVariable($taskName,$null,'Process')}
    foreach($taskName in @('TREND_ENABLED','TREND_SCHEDULE_ENABLED','WECOM_DOC_ENABLED','WECOM_DOC_SCHEDULE_ENABLED','WECOM_ENABLED','WECOM_COMMANDS_ENABLED','WECOM_CHAT_ASSISTANT_ENABLED','LLM_ENABLED','MARKET_PROVIDER_ENABLED','MARKET_ENABLED','INTRADAY_ENABLED','INTRADAY_SCHEDULE_ENABLED','JOBS_ENABLED','DAILY_SCAN_SCHEDULE_ENABLED','SCREENING_ENABLED','PULLBACK_ENABLED','DAILY_REPORT_ENABLED','T_OBSERVATION_ENABLED','SCAN_ALERTS_ENABLED','PERSONAL_PRICE_ENABLED')){[Environment]::SetEnvironmentVariable($taskName,'false','Process')}
    Push-Location $taskRoot
    try {
        & mvn -B -s maven-settings.xml '-Dmaven.repo.local=.maven-repository' -f backend/pom.xml test *> (Join-Path $taskLogs 'java.log')
        if($LASTEXITCODE -ne 0){throw 'Java verification failed; inspect java.log'}
        & .venv/Scripts/python.exe -m pytest quant-service/tests -q -p no:cacheprovider *> (Join-Path $taskLogs 'python.log')
        if($LASTEXITCODE -ne 0){throw 'Python verification failed; inspect python.log'}
        if($UseLocalDatabase){
            if($env:STOCKSENTINEL_TEST_DB_URL -notmatch '^jdbc:postgresql://(127\.0\.0\.1|localhost):[0-9]+/[A-Za-z0-9_]+$' -or [string]::IsNullOrEmpty($env:STOCKSENTINEL_TEST_DB_USER) -or [string]::IsNullOrEmpty($env:STOCKSENTINEL_TEST_DB_PASSWORD)){throw 'Explicit loopback test database environment is required; credentials are never read from production settings'}
            $taskTests=if($Stage -eq 'A'){'TObservationLocalDatabaseIT,IntradayLocalDatabaseIT,GroupAssistantLocalDatabaseIT,ScanFaultLocalDatabaseIT,TTradeMigrationLocalDatabaseIT'}else{'TObservationLocalDatabaseIT,GroupAssistantLocalDatabaseIT,TTradeMigrationLocalDatabaseIT'}
            & mvn -B -s maven-settings.xml '-Dmaven.repo.local=.maven-repository' -f backend/pom.xml failsafe:integration-test failsafe:verify "-Dit.test=$taskTests" *> (Join-Path $taskLogs 'database.log')
            if($LASTEXITCODE -ne 0){throw 'Isolated-schema verification failed; inspect database.log'}
        }
        Write-Output "PASS stage=$Stage localDatabase=$UseLocalDatabase logs=$taskLogs"
    } finally {Pop-Location}
} finally {
    foreach($taskName in $taskNames){[Environment]::SetEnvironmentVariable($taskName,$taskBefore[$taskName],'Process')}
}
