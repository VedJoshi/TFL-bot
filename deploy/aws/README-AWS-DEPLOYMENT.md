# Deploying TFL Bot to AWS (Free Tier)

## Prerequisites
- AWS Account with free tier access
- Telegram Bot Token
- Local Maven installation for building

## Step 1: Build Your Application
```bash
# In your project root
mvn clean package -DskipTests
```
This creates `target/UpdatedTFLBot-1.0-SNAPSHOT.jar`

## Step 2: Create RDS PostgreSQL Database

1. **Navigate to RDS Console**
   - Go to AWS Console → RDS → Create database

2. **Configure Database**
   - Engine: PostgreSQL
   - Template: Free tier
   - DB instance identifier: `tfl-bot-db`
   - Master username: `postgres`
   - Master password: `[create a strong password]`
   - DB instance class: `db.t3.micro` (free tier)
   - Storage: 20 GB (free tier limit)
   - Public access: Yes (for development)

3. **Security Group**
   - Create/edit security group to allow PostgreSQL (port 5432)
   - Add inbound rule: Type=PostgreSQL, Source=Your IP or 0.0.0.0/0 (less secure)

4. **Note Connection Details**
   - Endpoint: `your-db-instance.region.rds.amazonaws.com`
   - Port: `5432`
   - Database name: `postgres` (default)

## Step 3: Launch EC2 Instance

1. **Create EC2 Instance**
   - AMI: Amazon Linux 2
   - Instance type: `t2.micro` (free tier)
   - Key pair: Create new or use existing
   - Security group: Allow SSH (22) and HTTP (8080)

2. **Security Group Rules**
   ```
   SSH (22) - Your IP
   Custom TCP (8080) - 0.0.0.0/0 (for health checks)
   ```

3. **Connect to Instance**
   ```bash
   ssh -i your-key.pem ec2-user@your-ec2-public-ip
   ```

## Step 4: Setup EC2 Instance

1. **Upload setup script**
   ```bash
   scp -i your-key.pem deploy/aws/setup-ec2.sh ec2-user@your-ec2-ip:~/
   ```

2. **Run setup**
   ```bash
   chmod +x setup-ec2.sh
   ./setup-ec2.sh
   ```

3. **Upload your JAR file**
   ```bash
   scp -i your-key.pem target/UpdatedTFLBot-1.0-SNAPSHOT.jar ec2-user@your-ec2-ip:~/tfl-bot/
   ```

4. **Upload startup script**
   ```bash
   scp -i your-key.pem deploy/aws/start-bot.sh ec2-user@your-ec2-ip:~/tfl-bot/
   chmod +x ~/tfl-bot/start-bot.sh
   ```

## Step 5: Configure Environment Variables

1. **Edit environment variables**
   ```bash
   nano ~/.bashrc
   ```

2. **Add your actual values**
   ```bash
   export BOT_TOKEN='your_actual_bot_token'
   # Database connection URL with RDS endpoint and credentials
   export DATABASE_URL='jdbc:postgresql://your-rds-endpoint:5432/postgres?user=postgres&password=your_password'
   export PORT=8080
   ```

3. **Reload environment**
   ```bash
   source ~/.bashrc
   ```

## Step 6: Start the Bot

### Option A: Using systemd service (recommended)
```bash
sudo systemctl start tfl-bot
sudo systemctl status tfl-bot
sudo systemctl enable tfl-bot  # Auto-start on boot
```

### Option B: Using start script
```bash
cd ~/tfl-bot
./start-bot.sh start
./start-bot.sh status
```

## Step 7: Verify Deployment

1. **Check bot logs**
   ```bash
   sudo journalctl -u tfl-bot -f
   # or
   tail -f ~/tfl-bot/logs/bot.log
   ```

2. **Test health endpoint**
   ```bash
   curl http://your-ec2-public-ip:8080/health
   ```

3. **Test your Telegram bot**
   - Send `/start` to your bot

## Monitoring and Maintenance

### View Logs
```bash
# Service logs
sudo journalctl -u tfl-bot -f

# Application logs
tail -f ~/tfl-bot/logs/bot.log
```

### Control Service
```bash
sudo systemctl start tfl-bot
sudo systemctl stop tfl-bot
sudo systemctl restart tfl-bot
sudo systemctl status tfl-bot
```

### Update Application
```bash
# Stop service
sudo systemctl stop tfl-bot

# Upload new JAR
scp -i your-key.pem target/UpdatedTFLBot-1.0-SNAPSHOT.jar ec2-user@your-ec2-ip:~/tfl-bot/

# Start service
sudo systemctl start tfl-bot
```

## Free Tier Limits
- **EC2**: 750 hours/month for 12 months
- **RDS**: 750 hours/month, 20GB storage for 12 months
- **Data Transfer**: 15GB/month

## Troubleshooting

### Bot not starting
1. Check Java installation: `java -version`
2. Check environment variables: `echo $BOT_TOKEN`
3. Check database connectivity: `telnet your-rds-endpoint 5432`

### Database connection issues
1. Verify RDS security group allows port 5432
2. Check DATABASE_URL format
3. Test connection: `psql -h your-rds-endpoint -U postgres -d postgres`

### Memory issues (t2.micro has limited RAM)
1. Add swap space:
   ```bash
   sudo dd if=/dev/zero of=/swapfile bs=1024 count=1048576
   sudo chmod 600 /swapfile
   sudo mkswap /swapfile
   sudo swapon /swapfile
   ```

## Cost Optimization
- Stop EC2 instance when not needed (saves hours)
- Monitor AWS Free Tier usage in billing dashboard
- Set up billing alerts
- Consider scheduled start/stop for development

## Security Best Practices
- Use IAM roles instead of access keys when possible
- Restrict security group rules to minimum required
- Keep your AMI and packages updated
- Use secrets manager for sensitive data in production
