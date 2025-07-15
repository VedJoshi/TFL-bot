#!/bin/bash

# TFL Bot Startup Script for AWS EC2
# Make executable with: chmod +x start-bot.sh

# Set variables
APP_NAME="tfl-bot"
JAR_FILE="/home/ec2-user/tfl-bot/UpdatedTFLBot-1.0-SNAPSHOT.jar"
LOG_FILE="/home/ec2-user/tfl-bot/logs/bot.log"
PID_FILE="/home/ec2-user/tfl-bot/bot.pid"

# Create directories
mkdir -p /home/ec2-user/tfl-bot/logs

# Function to start the bot
start_bot() {
    if [ -f $PID_FILE ]; then
        PID=$(cat $PID_FILE)
        if ps -p $PID > /dev/null; then
            echo "Bot is already running with PID $PID"
            exit 1
        fi
    fi
    
    echo "Starting TFL Bot..."
    nohup java -jar $JAR_FILE > $LOG_FILE 2>&1 &
    echo $! > $PID_FILE
    echo "Bot started with PID $(cat $PID_FILE)"
}

# Function to stop the bot
stop_bot() {
    if [ -f $PID_FILE ]; then
        PID=$(cat $PID_FILE)
        if ps -p $PID > /dev/null; then
            echo "Stopping TFL Bot (PID: $PID)..."
            kill $PID
            rm $PID_FILE
            echo "Bot stopped"
        else
            echo "Bot is not running"
            rm $PID_FILE
        fi
    else
        echo "PID file not found. Bot may not be running"
    fi
}

# Function to restart the bot
restart_bot() {
    stop_bot
    sleep 2
    start_bot
}

# Function to check status
status_bot() {
    if [ -f $PID_FILE ]; then
        PID=$(cat $PID_FILE)
        if ps -p $PID > /dev/null; then
            echo "Bot is running with PID $PID"
        else
            echo "Bot is not running (stale PID file)"
        fi
    else
        echo "Bot is not running"
    fi
}

# Handle command line arguments
case "$1" in
    start)
        start_bot
        ;;
    stop)
        stop_bot
        ;;
    restart)
        restart_bot
        ;;
    status)
        status_bot
        ;;
    *)
        echo "Usage: $0 {start|stop|restart|status}"
        exit 1
        ;;
esac
