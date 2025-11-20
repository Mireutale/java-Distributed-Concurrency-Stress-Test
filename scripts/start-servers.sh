#!/bin/bash

# 서버 인스턴스들을 백그라운드로 실행하는 스크립트

echo "Starting Server 1 on port 8081..."
./gradlew bootRun --args='--spring.profiles.active=server1 --SERVER_PORT=8081' > server1.log 2>&1 &
SERVER1_PID=$!

echo "Starting Server 2 on port 8082..."
./gradlew bootRun --args='--spring.profiles.active=server2 --SERVER_PORT=8082' > server2.log 2>&1 &
SERVER2_PID=$!

echo "Starting Server 3 on port 8083..."
./gradlew bootRun --args='--spring.profiles.active=server3 --SERVER_PORT=8083' > server3.log 2>&1 &
SERVER3_PID=$!

echo "Waiting for servers to start..."
sleep 10

echo "Starting Load Balancer on port 8080..."
./gradlew bootRun --args='--spring.profiles.active=lb --SERVER_PORT=8080' > lb.log 2>&1 &
LB_PID=$!

echo "All servers started!"
echo "Server 1 PID: $SERVER1_PID"
echo "Server 2 PID: $SERVER2_PID"
echo "Server 3 PID: $SERVER3_PID"
echo "Load Balancer PID: $LB_PID"
echo ""
echo "To stop all servers, run: kill $SERVER1_PID $SERVER2_PID $SERVER3_PID $LB_PID"
echo "Or use: pkill -f 'bootRun'"

