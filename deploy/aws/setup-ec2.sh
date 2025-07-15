#!/bin/bash

# EC2 Setup Script for TFL Bot
# Run this on a fresh Amazon Linux 2 EC2 instance

echo "Setting up TFL Bot on EC2..."

# Update system
sudo yum update -y

# Install Java 17
sudo yum install -y java-17-amazon-corretto-devel

# Install Git
sudo yum install -y git

# Create application directory
mkdir -p /home/ec2-user/tfl-bot/logs

# Set environment variables (replace with your actual values)
echo "export BOT_TOKEN='YOUR_BOT_TOKEN_HERE'" >> ~/.bashrc
echo "export DATABASE_URL='jdbc:postgresql://YOUR_RDS_ENDPOINT:5432/tflbot?user=YOUR_USERNAME&password=YOUR_PASSWORD'" >> ~/.bashrc
echo "export PORT=8080" >> ~/.bashrc

# Reload bashrc
source ~/.bashrc

# Create systemd service file
sudo tee /etc/systemd/system/tfl-bot.service > /dev/null <<EOF
[Unit]
Description=TFL Telegram Bot
After=network.target

[Service]
Type=simple
User=ec2-user
WorkingDirectory=/home/ec2-user/tfl-bot
ExecStart=/usr/bin/java -jar /home/ec2-user/tfl-bot/UpdatedTFLBot-1.0-SNAPSHOT.jar
Restart=always
RestartSec=10
Environment=BOT_TOKEN=$BOT_TOKEN
Environment=DATABASE_URL=$DATABASE_URL
Environment=PORT=8080

[Install]
WantedBy=multi-user.target
EOF

# Enable and start the service
sudo systemctl daemon-reload
sudo systemctl enable tfl-bot

echo "Setup complete!"
echo "Next steps:"
echo "1. Upload your JAR file to /home/ec2-user/tfl-bot/"
echo "2. Edit ~/.bashrc to set your actual BOT_TOKEN and DATABASE_URL"
echo "3. Run: source ~/.bashrc"
echo "4. Start the service: sudo systemctl start tfl-bot"
echo "5. Check status: sudo systemctl status tfl-bot"
