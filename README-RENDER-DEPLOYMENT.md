# Deploying TFL Bot to Render

## Prerequisites
1. GitHub account with your code pushed
2. Render account (free)
3. Telegram Bot Token

## Step 1: Create PostgreSQL Database
1. Go to [Render Dashboard](https://dashboard.render.com/)
2. Click "New +" → "PostgreSQL"
3. Configure:
   - Name: `tfl-bot-db`
   - Database: `tflbot`
   - User: `tflbot`
   - Plan: Free
4. Click "Create Database"
5. Note down the connection details (available in database info)

## Step 2: Deploy the Bot
1. In Render Dashboard, click "New +" → "Web Service"
2. Connect your GitHub repository
3. Configure:
   - Name: `tfl-telegram-bot`
   - Environment: Docker
   - Plan: Free
   - Health Check Path: `/health`

## Step 3: Set Environment Variables
In your web service settings, add:
- `BOT_TOKEN`: Your Telegram bot token
- `DATABASE_URL`: Copy from your PostgreSQL database (Internal Database URL)

## Step 4: Deploy
1. Click "Create Web Service"
2. Render will automatically build and deploy
3. Monitor logs for any issues

## Free Tier Limits
- **Web Service**: 750 hours/month, sleeps after 15 minutes of inactivity
- **PostgreSQL**: 1GB storage, 1 month retention
- **Bandwidth**: 100GB/month

## Keeping Bot Awake (Optional)
For production, consider:
1. Upgrading to paid plan ($7/month)
2. Using external ping service (not recommended for free tier)
3. Implementing smart sleep/wake patterns

## Troubleshooting
- Check logs in Render dashboard
- Verify environment variables are set
- Ensure DATABASE_URL uses internal connection string
- Check health endpoint: `https://your-app.onrender.com/health`
