@echo off
REM Windows용 서버 시작 스크립트

echo Starting Server 1 on port 8081...
start "Server1" cmd /k "gradlew.bat bootRun --args='--spring.profiles.active=server1 --SERVER_PORT=8081'"

timeout /t 5 /nobreak >nul

echo Starting Server 2 on port 8082...
start "Server2" cmd /k "gradlew.bat bootRun --args='--spring.profiles.active=server2 --SERVER_PORT=8082'"

timeout /t 5 /nobreak >nul

echo Starting Server 3 on port 8083...
start "Server3" cmd /k "gradlew.bat bootRun --args='--spring.profiles.active=server3 --SERVER_PORT=8083'"

timeout /t 5 /nobreak >nul

echo Starting Load Balancer on port 8080...
start "LoadBalancer" cmd /k "gradlew.bat bootRun --args='--spring.profiles.active=lb --SERVER_PORT=8080'"

echo All servers started in separate windows!
pause

