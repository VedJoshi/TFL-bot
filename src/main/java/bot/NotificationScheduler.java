package bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class NotificationScheduler {
    private static final Logger logger = LoggerFactory.getLogger(NotificationScheduler.class);
    
    private final TFLService tflService;
    private final UserPreferencesService preferencesService;
    private final Bot bot;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    
    public NotificationScheduler(TFLService tflService, UserPreferencesService preferencesService, Bot bot) {
        this.tflService = tflService;
        this.preferencesService = preferencesService;
        this.bot = bot;
    }
    
    public void start() {
        // Check for scheduled notifications every minute
        scheduler.scheduleAtFixedRate(this::checkScheduledNotifications, 0, 1, TimeUnit.MINUTES);
        
        // Check for disruptions every 5 minutes
        scheduler.scheduleAtFixedRate(this::checkDisruptions, 0, 5, TimeUnit.MINUTES);
        
        logger.info("Notification scheduler started");
    }
    
    public void stop() {
        scheduler.shutdown();
        logger.info("Notification scheduler stopped");
    }
    
    private void checkScheduledNotifications() {
        try {
            LocalTime currentTime = LocalTime.now().withSecond(0).withNano(0);
            List<Long> users = preferencesService.getUsersWithScheduledNotifications(currentTime);
            
            if (!users.isEmpty() && shouldSendNotifications()) {
                logger.info("Sending scheduled notifications to {} users at {}", users.size(), currentTime);
                
                for (Long userId : users) {
                    try {
                        sendScheduledNotification(userId, currentTime);
                    } catch (Exception e) {
                        logger.error("Failed to send scheduled notification to user {}", userId, e);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error checking scheduled notifications", e);
        }
    }
    
    private void checkDisruptions() {
        try {
            List<TFLService.DisruptionInfo> severeDisruptions = tflService.getSevereDisruptions();
            
            if (!severeDisruptions.isEmpty()) {
                List<Long> users = preferencesService.getUsersWithDisruptionAlerts();
                logger.info("Found {} severe disruptions, notifying {} users", severeDisruptions.size(), users.size());
                
                for (Long userId : users) {
                    try {
                        sendDisruptionAlert(userId, severeDisruptions);
                    } catch (Exception e) {
                        logger.error("Failed to send disruption alert to user {}", userId, e);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error checking disruptions", e);
        }
    }
    
    private boolean shouldSendNotifications() {
        DayOfWeek today = java.time.LocalDate.now().getDayOfWeek();
        return today != DayOfWeek.SATURDAY && today != DayOfWeek.SUNDAY;
    }
    
    private void sendScheduledNotification(Long userId, LocalTime time) {
        UserPreferencesService.UserPreferences prefs = preferencesService.getUserPreferences(userId);
        UserPreferencesService.NotificationSettings settings = prefs.getScheduledNotifications().get(time);
        
        if (settings == null) return;
        
        try {
            StringBuilder message = new StringBuilder("🕐 *Scheduled Update* - ").append(time).append("\n\n");
            
            Set<String> lines = settings.getLines();
            if (lines.isEmpty()) {
                // Show all lines if no specific lines selected
                String allStatuses = tflService.getAllLineStatuses();
                String parsed = tflService.parseLineStatus(allStatuses);
                message.append(parsed);
            } else {
                // Show only selected lines
                for (String line : lines) {
                    try {
                        String lineStatus = tflService.getLineStatus(line);
                        String parsed = tflService.parseLineStatus(lineStatus);
                        message.append(parsed);
                    } catch (Exception e) {
                        message.append("❌ Could not get status for ").append(line).append("\n\n");
                    }
                }
            }
            
            SendMessage sendMessage = new SendMessage();
            sendMessage.setChatId(userId.toString());
            sendMessage.setText(message.toString());
            sendMessage.setParseMode("Markdown");
            
            bot.execute(sendMessage);
            
        } catch (Exception e) {
            logger.error("Failed to send scheduled notification to user {}", userId, e);
        }
    }
    
    private void sendDisruptionAlert(Long userId, List<TFLService.DisruptionInfo> disruptions) {
        try {
            StringBuilder message = new StringBuilder("🚨 *Disruption Alert*\n\n");
            
            for (TFLService.DisruptionInfo disruption : disruptions) {
                message.append(disruption.toString()).append("\n\n");
            }
            
            SendMessage sendMessage = new SendMessage();
            sendMessage.setChatId(userId.toString());
            sendMessage.setText(message.toString());
            sendMessage.setParseMode("Markdown");
            
            bot.execute(sendMessage);
            
        } catch (Exception e) {
            logger.error("Failed to send disruption alert to user {}", userId, e);
        }
    }
}
