#!/bin/bash

# Define the function to start the bot
start_bot() {
    echo "Starting TFL Bot..."
    
    # Build the project first to ensure dependencies are available
    mvn clean compile dependency:copy-dependencies -q
    
    # Set classpath to include compiled classes and all dependencies
    CLASSPATH="target/classes:target/dependency/*"
    
    # Start the bot with proper classpath
    nohup java -cp "$CLASSPATH" bot.Bot > logs/bot.log 2>&1 &
    
    echo "TFL Bot started."
}

# Call the function to start the bot
start_bot