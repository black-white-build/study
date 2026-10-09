$ErrorActionPreference = 'Continue'
Set-Location 'S:\heart-pilot\heart-pilot-backend'
& .\mvnw.cmd -q '-Dtest=LegacyBaselineReplayTest,DecisionAssistantEvaluationTest' '-DfailIfNoTests=false' test 2>&1 | Select-String -Pattern 'baseline:|evaluation:|BUILD|ERROR|Tests run|FAIL' | Select-Object -First 40
Write-Output ('MVN_EXIT=' + $LASTEXITCODE)
