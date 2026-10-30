@echo off
cd /d "%~dp0"
if exist currency-parser.jar (
    java -jar currency-parser.jar %*
) else (
    if not exist target\currency-parser-1.0.0.jar (
        call mvn -B -ntp package
        if errorlevel 1 goto finished
    )
    java -jar target\currency-parser-1.0.0.jar %*
)
:finished
pause
