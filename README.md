# TFL Telegram Bot

A telegram bot for checking London tube status. Features include real-time status updates, favorite lines, disruption alerts, and comprehensive line information.

## Features
- 📊 Real-time TFL tube status overview
- 🚇 Individual line status checking
- ⭐ Favorite lines management
- 🔔 Disruption notifications
- 📱 Interactive inline keyboard interface

## AWS Deployment Guide

### Prerequisites
- AWS Account (free tier eligible)
- Telegram Bot Token from @BotFather
- Java 17+ and Maven installed locally
- SSH client (PuTTY on Windows or terminal on Mac/Linux)

### Step 1: Build the Application
```bash
# Clone and build the project
git clone <your-repo-url>
cd UpdatedTFLBot
mvn clean package -DskipTests
```
This creates `target/UpdatedTFLBot-1.0-SNAPSHOT.jar`

### Step 2: Create AWS RDS PostgreSQL Database

1. **Login to AWS Console** → Navigate to RDS service

2. **Create Database**
   - Click "Create database"
   - Choose "Standard Create"
   - Engine type: **PostgreSQL**
   - Version: **PostgreSQL 15.4** (or latest)
   - Templates: **Free tier** ⚠️ IMPORTANT

3. **Settings**
   - DB cluster identifier: `tfl-bot-db`
   - Master username: `postgres`
   - Master password: `TflBot2024!` (or your choice - remember this!)
   - Confirm password

4. **DB Instance Class**
   - Burstable classes: **db.t3.micro** ✅ (Free tier eligible)

5. **Storage**
   - Storage type: General Purpose SSD (gp2)
   - Allocated storage: **20 GiB** (free tier limit)
   - ❌ UNCHECK "Enable storage autoscaling" (to avoid charges)

6. **Connectivity**
   - Virtual Private Cloud (VPC): Default VPC
   - Public access: **Yes** (for development)
   - VPC security group: Create new
   - Security group name: `tfl-bot-db-sg`

7. **Database Authentication**
   - Database authentication: Password authentication

8. **Additional Configuration**
   - Initial database name: `tflbot`
   - ❌ UNCHECK "Enable automated backups" (to stay in free tier)
   - ❌ UNCHECK "Enable Enhanced monitoring"

9. **Click "Create database"** - Wait 5-10 minutes for creation

10. **Configure Security Group**
    - Go to EC2 → Security Groups → Find `tfl-bot-db-sg`
    - Edit inbound rules → Add rule:
      - Type: PostgreSQL
      - Port: 5432
      - Source: 0.0.0.0/0 (for development - restrict in production)
    - Save rules

### Step 3: Create AWS EC2 Instance

1. **Navigate to EC2 Console** → Click "Launch Instance"

2. **Name and Tags**
   - Name: `tfl-bot-server`

3. **Application and OS Images**
   - Quick Start: **Amazon Linux**
   - Amazon Machine Image: **Amazon Linux 2 AMI (HVM) - Kernel 5.10**
   - Architecture: **64-bit (x86)**

4. **Instance Type**
   - Instance type: **t2.micro** ✅ (Free tier eligible)

5. **Key Pair**
   - Create new key pair (if you don't have one):
     - Key pair name: `tfl-bot-key`
     - Key pair type: RSA
     - Private key format: .pem
     - **Download and save the .pem file securely!**

6. **Network Settings**
   - Create security group:
     - Security group name: `tfl-bot-sg`
     - Description: `TFL Bot Security Group`
     - Allow SSH traffic from: Your IP
     - Allow HTTP traffic from: Internet ✅
     - Add custom rule:
       - Type: Custom TCP
       - Port: 8080
       - Source: 0.0.0.0/0

7. **Configure Storage**
   - Storage: 8 GiB gp2 (free tier includes up to 30 GiB)

8. **Click "Launch Instance"**

9. **Note the Public IP** after instance starts (will be shown in EC2 console)

### Step 4: Connect to EC2 and Setup Environment

1. **Connect via SSH**
   ```bash
   # Make key file secure (Linux/Mac)
   chmod 400 tfl-bot-key.pem
   
   # Connect to instance
   ssh -i tfl-bot-key.pem ec2-user@YOUR_EC2_PUBLIC_IP
   ```

2. **Update System and Install Java**
   ```bash
   # Update system
   sudo yum update -y
   
   # Install Java 17
   sudo yum install -y java-17-amazon-corretto-devel
   
   # Verify installation
   java -version
   ```

3. **Create Application Directory**
   ```bash
   mkdir -p /home/ec2-user/tfl-bot/logs
   cd /home/ec2-user/tfl-bot
   ```

4. **Upload JAR File**
   ```bash
   # From your local machine (new terminal window)
   scp -i tfl-bot-key.pem target/UpdatedTFLBot-1.0-SNAPSHOT.jar ec2-user@YOUR_EC2_PUBLIC_IP:/home/ec2-user/tfl-bot/
   ```

### Step 5: Configure Environment Variables

1. **Get RDS Connection Details**
   - Go to RDS Console → Databases → tfl-bot-db
   - Copy the **Endpoint** (looks like: `tfl-bot-db.xxxxx.region.rds.amazonaws.com`)

2. **Set Environment Variables on EC2**
   ```bash
   # Edit bashrc
   nano ~/.bashrc
   
   # Add these lines at the end (replace with your actual values):
   export BOT_TOKEN='YOUR_TELEGRAM_BOT_TOKEN'
   export DATABASE_URL='jdbc:postgresql://YOUR_RDS_ENDPOINT:5432/tflbot?user=postgres&password=TflBot2024!'
   export PORT=8080
   
   # Save file (Ctrl+X, then Y, then Enter)
   
   # Reload environment
   source ~/.bashrc
   
   # Verify variables are set
   echo $BOT_TOKEN
   echo $DATABASE_URL
   ```

### Step 6: Create Startup Script

1. **Create startup script**
   ```bash
   nano /home/ec2-user/tfl-bot/start-bot.sh
   ```

2. **Add script content:**
   ```bash
   #!/bin/bash
   
   APP_NAME="tfl-bot"
   JAR_FILE="/home/ec2-user/tfl-bot/UpdatedTFLBot-1.0-SNAPSHOT.jar"
   LOG_FILE="/home/ec2-user/tfl-bot/logs/bot.log"
   PID_FILE="/home/ec2-user/tfl-bot/bot.pid"
   
   start_bot() {
       if [ -f $PID_FILE ]; then
           PID=$(cat $PID_FILE)
           if ps -p $PID > /dev/null; then
               echo "Bot is already running with PID $PID"
               exit 1
           fi
       fi
       
       echo "Starting TFL Bot..."
       source ~/.bashrc
       nohup java -jar $JAR_FILE > $LOG_FILE 2>&1 &
       echo $! > $PID_FILE
       echo "Bot started with PID $(cat $PID_FILE)"
   }
   
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
   
   case "$1" in
       start)
           start_bot
           ;;
       stop)
           stop_bot
           ;;
       restart)
           stop_bot
           sleep 2
           start_bot
           ;;
       status)
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
           ;;
       *)
           echo "Usage: $0 {start|stop|restart|status}"
           exit 1
           ;;
   esac
   ```

3. **Make script executable**
   ```bash
   chmod +x /home/ec2-user/tfl-bot/start-bot.sh
   ```

### Step 7: Start the Bot

1. **Start the bot**
   ```bash
   cd /home/ec2-user/tfl-bot
   ./start-bot.sh start
   ```

2. **Check status**
   ```bash
   ./start-bot.sh status
   ```

3. **View logs**
   ```bash
   tail -f logs/bot.log
   ```

4. **Test health endpoint**
   ```bash
   curl http://localhost:8080/health
   # Or from external:
   curl http://YOUR_EC2_PUBLIC_IP:8080/health
   ```

### Step 8: Test Your Bot

1. Open Telegram and find your bot
2. Send `/start` command
3. Try the different menu options
4. Test adding favorites and checking line status

### Management Commands

```bash
# Start bot
./start-bot.sh start

# Stop bot
./start-bot.sh stop

# Restart bot
./start-bot.sh restart

# Check status
./start-bot.sh status

# View logs
tail -f logs/bot.log

# View recent logs
tail -50 logs/bot.log
```

### Troubleshooting

**Bot won't start:**
```bash
# Check Java installation
java -version

# Check environment variables
echo $BOT_TOKEN
echo $DATABASE_URL

# Check if port is in use
sudo netstat -tlnp | grep 8080

# View detailed logs
cat logs/bot.log
```

**Database connection issues:**
```bash
# Test database connection
sudo yum install -y postgresql
psql -h YOUR_RDS_ENDPOINT -U postgres -d tflbot

# Check security group allows port 5432
# Verify DATABASE_URL format
```

**Memory issues (t2.micro has 1GB RAM):**
```bash
# Add swap space
sudo dd if=/dev/zero of=/swapfile bs=1024 count=1048576
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

### Auto-start on Boot (Optional)

```bash
# Create systemd service
sudo tee /etc/systemd/system/tfl-bot.service > /dev/null <<EOF
[Unit]
Description=TFL Telegram Bot
After=network.target

[Service]
Type=forking
User=ec2-user
WorkingDirectory=/home/ec2-user/tfl-bot
ExecStart=/home/ec2-user/tfl-bot/start-bot.sh start
ExecStop=/home/ec2-user/tfl-bot/start-bot.sh stop
PIDFile=/home/ec2-user/tfl-bot/bot.pid
Restart=always
RestartSec=10

[Install]
WantedBy=multi-user.target
EOF

# Enable and start service
sudo systemctl daemon-reload
sudo systemctl enable tfl-bot
sudo systemctl start tfl-bot

# Check service status
sudo systemctl status tfl-bot
```

### Cost Management

**Free Tier Limits:**
- EC2: 750 hours/month for 12 months
- RDS: 750 hours/month for 12 months
- Storage: 30GB EBS, 20GB RDS

**To minimize costs:**
1. Stop EC2 instance when not needed
2. Set up billing alerts in AWS Console
3. Monitor usage in Free Tier dashboard
4. Don't exceed storage limits

### Security Notes
- In production, restrict database access to EC2 security group only
- Use IAM roles instead of hardcoded credentials
- Keep your system updated: `sudo yum update -y`
- Consider using AWS Secrets Manager for sensitive data

## Local Development

```bash
# Set environment variables
export BOT_TOKEN='your_bot_token'
export DATABASE_URL='jdbc:postgresql://localhost:5432/tflbot?user=postgres&password=password'

# Run locally
mvn spring-boot:run
# or
java -jar target/UpdatedTFLBot-1.0-SNAPSHOT.jar
```

## Support

If you encounter issues:
1. Check the logs: `tail -f logs/bot.log`
2. Verify environment variables are set
3. Test database connectivity
4. Check AWS security group rules
5. Monitor AWS Free Tier usage

---

Made with ❤️ for checking London tube status
