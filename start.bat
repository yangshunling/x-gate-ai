@echo off
chcp 65001 >nul
cd /d "%~dp0"

set "JAR=target\x-gate-ai-1.0.0.jar"
set "PORT=8090"

rem 已在运行则先结束旧进程，双击即自动重启
for /f "tokens=5" %%p in ('netstat -ano ^| findstr /r /c:":%PORT% .*LISTENING"') do taskkill /F /PID %%p >nul 2>nul
timeout /t 1 /nobreak >nul

rem 无 jar 或带 -r 参数时重新打包
if not "%~1"=="-r" if exist "%JAR%" goto :go
call mvn clean package -DskipTests || exit /b 1

:go
echo 启动中，访问 http://localhost:%PORT%/ （Ctrl+C 停止）
java -jar "%JAR%"
