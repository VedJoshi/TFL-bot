package bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class Bot extends TelegramLongPollingBot {
    private static final Logger logger = LoggerFactory.getLogger(Bot.class);

    private final TFLService tflApiService = new TFLService();
    private final UserPreferencesService preferencesService = new UserPreferencesService();
    private final NotificationScheduler notificationScheduler;

    private boolean awaitingLineName = false;
    private boolean awaitingJourneyInput = false;
    private boolean awaitingStationInput = false;
    private boolean awaitingScheduleTime = false;

    public Bot() {
        super("BOT_TOKEN"); // Fix deprecated constructor
        this.notificationScheduler = new NotificationScheduler(tflApiService, preferencesService, this);
        this.notificationScheduler.start();
        logger.info("Bot initialized successfully");
    }

    @Override
    public String getBotUsername() {
        return "AbiTFLBot";
    }

    @Override
    public String getBotToken() {
        String token = System.getenv("BOT_TOKEN");
        if (token == null) {
            logger.error("BOT_TOKEN environment variable not found");
            throw new RuntimeException("BOT_TOKEN environment variable is required");
        }
        return token;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();
            Long userId = update.getMessage().getFrom().getId();

            if (awaitingJourneyInput) {
                handleJourneyPlannerInput(chatId, messageText);
                awaitingJourneyInput = false;
            } else if (awaitingStationInput) {
                handleStationInfoInput(chatId, messageText);
                awaitingStationInput = false;
            } else if (awaitingScheduleTime) {
                handleScheduleTimeInput(chatId, userId, messageText);
                awaitingScheduleTime = false;
            } else if (awaitingLineName) {
                handleLineSpecificRequest(chatId, messageText.toLowerCase());
                awaitingLineName = false;
            } else if (messageText.startsWith("from ") && messageText.contains(" to ")) {
                handleJourneyPlannerInput(chatId, messageText);
            } else if (messageText.equals("/start")) {
                sendMenu(chatId, "Welcome to the AbiTFLBot! 🚇\nChoose an option:", getMainMenu());
            } else if (messageText.equals("/favorites")) {
                showFavorites(chatId);
            } else if (messageText.equals("/settings")) {
                showSettings(chatId);
            } else if (messageText.equals("/help")) {
                showHelp(chatId);
            } else {
                // Check if it's a station name
                handleStationInfoInput(chatId, messageText);
            }
        } else if (update.hasCallbackQuery()) {
            String callbackData = update.getCallbackQuery().getData();
            long chatId = update.getCallbackQuery().getMessage().getChatId();
            Long userId = update.getCallbackQuery().getFrom().getId();

            handleCallbackQuery(chatId, userId, callbackData);
        }
    }

    private void handleCallbackQuery(long chatId, Long userId, String callbackData) {
        if (callbackData.equals("overview")) {
            handleOverviewRequest(chatId);
        } else if (callbackData.equals("line")) {
            sendLineOptions(chatId);
        } else if (callbackData.equals("favorites")) {
            showFavorites(chatId);
        } else if (callbackData.equals("settings")) {
            showSettings(chatId);
        } else if (callbackData.equals("schedule_notifications")) {
            showScheduleNotificationsMenu(chatId);
        } else if (callbackData.equals("add_schedule")) {
            handleAddSchedule(chatId);
        } else if (callbackData.equals("remove_schedule")) {
            handleRemoveSchedule(chatId, userId);
        } else if (callbackData.startsWith("remove_schedule_")) {
            String timeToRemove = callbackData.substring(16); // Remove "remove_schedule_" prefix
            preferencesService.removeScheduledNotification(userId, timeToRemove);
            sendText(chatId, "✅ Removed notification for " + timeToRemove);
            showScheduleNotificationsMenu(chatId); // Refresh the menu
        } else if (callbackData.equals("journey_planner")) {
            showJourneyPlannerMenu(chatId);
            awaitingJourneyInput = true;
        } else if (callbackData.equals("station_info")) {
            showStationInfoMenu(chatId);
            awaitingStationInput = true;
        } else if (callbackData.equals("service_updates")) {
            showServiceUpdates(chatId);
        } else if (callbackData.equals("check_disruptions")) {
            checkDisruptionsOnDemand(chatId);
        } else if (callbackData.startsWith("add_fav_")) {
            String lineName = callbackData.substring(8);
            preferencesService.addFavoriteLine(userId, lineName);
            sendText(chatId, "✅ Added " + lineName + " to your favorites!");
        } else if (callbackData.startsWith("remove_fav_")) {
            String lineName = callbackData.substring(11);
            preferencesService.removeFavoriteLine(userId, lineName);
            sendText(chatId, "❌ Removed " + lineName + " from your favorites!");
        } else if (callbackData.equals("toggle_disruption_alerts")) {
            UserPreferencesService.UserPreferences prefs = preferencesService.getUserPreferences(userId);
            boolean newState = !prefs.isDisruptionAlertsEnabled();
            preferencesService.setDisruptionAlerts(userId, newState);
            sendText(chatId, "🔔 Disruption alerts " + (newState ? "enabled" : "disabled"));
        } else if (isValidLineId(callbackData)) {
            handleLineSpecificRequest(chatId, callbackData);
        } else {
            sendText(chatId, "❌ Unknown command. Please use the menu options.");
        }
    }

    private InlineKeyboardMarkup getMainMenu() {
        var overviewButton = InlineKeyboardButton.builder()
                .text("📊 Overview")
                .callbackData("overview")
                .build();

        var lineButton = InlineKeyboardButton.builder()
                .text("🚇 Line Specific")
                .callbackData("line")
                .build();

        var journeyButton = InlineKeyboardButton.builder()
                .text("🗺️ Journey Planner")
                .callbackData("journey_planner")
                .build();

        var stationButton = InlineKeyboardButton.builder()
                .text("🚉 Station Info")
                .callbackData("station_info")
                .build();

        var favoritesButton = InlineKeyboardButton.builder()
                .text("⭐ Favorites")
                .callbackData("favorites")
                .build();

        var scheduleButton = InlineKeyboardButton.builder()
                .text("⏰ Schedule Alerts")
                .callbackData("schedule_notifications")
                .build();

        var updatesButton = InlineKeyboardButton.builder()
                .text("🔧 Service Updates")
                .callbackData("service_updates")
                .build();

        var disruptionsButton = InlineKeyboardButton.builder()
                .text("🚨 Check Disruptions")
                .callbackData("check_disruptions")
                .build();

        var settingsButton = InlineKeyboardButton.builder()
                .text("⚙️ Settings")
                .callbackData("settings")
                .build();

        return InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(overviewButton, lineButton))
                .keyboardRow(List.of(journeyButton, stationButton))
                .keyboardRow(List.of(favoritesButton, scheduleButton))
                .keyboardRow(List.of(updatesButton, disruptionsButton))
                .keyboardRow(List.of(settingsButton))
                .build();
    }

    // New feature handlers
    private void showScheduleNotificationsMenu(long chatId) {
        Long userId = chatId;
        UserPreferencesService.UserPreferences prefs = preferencesService.getUserPreferences(userId);
        
        StringBuilder message = new StringBuilder("⏰ *Scheduled Notifications*\n\n");
        
        if (prefs.getScheduledNotifications().isEmpty()) {
            message.append("You don't have any scheduled notifications yet.\n\n");
        } else {
            message.append("Your current notifications:\n");
            prefs.getScheduledNotifications().forEach((time, settings) -> {
                message.append("• ").append(time.toString()).append(" - ");
                if (settings.getLines().isEmpty()) {
                    message.append("All lines");
                } else {
                    message.append(String.join(", ", settings.getLines()));
                }
                message.append("\n");
            });
            message.append("\n");
        }
        
        message.append("Send a time in HH:MM format (e.g., 08:30) to add a notification, or use the buttons below:");

        var addButton = InlineKeyboardButton.builder()
                .text("➕ Add Notification")
                .callbackData("add_schedule")
                .build();

        var removeButton = InlineKeyboardButton.builder()
                .text("➖ Remove Notification")
                .callbackData("remove_schedule")
                .build();

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(addButton, removeButton))
                .build();

        sendMenu(chatId, message.toString(), keyboard);
    }

    private void showJourneyPlannerMenu(long chatId) {
        sendText(chatId, "🗺️ *Journey Planner*\n\n" +
                "Send your journey in this format:\n" +
                "`from [station] to [station]`\n\n" +
                "Example: `from King's Cross to Oxford Circus`\n\n" +
                "I'll find the best route for you!\n\n" +
                "💡 *Tip*: You can also just type station names without 'from' and 'to'");
    }

    private void showStationInfoMenu(long chatId) {
        sendText(chatId, "🚉 *Station Information*\n\n" +
                "Send a station name to get:\n" +
                "• Live arrival times\n" +
                "• Station facilities\n" +
                "• Accessibility information\n\n" +
                "Example: `King's Cross St. Pancras`\n\n" +
                "💡 *Tip*: Just type the station name!");
    }

    private void showServiceUpdates(long chatId) {
        try {
            List<TFLService.ServiceUpdate> updates = tflApiService.getServiceUpdates();
            
            StringBuilder message = new StringBuilder("🔧 *Service Updates*\n\n");
            
            if (updates.isEmpty()) {
                message.append("No major service updates or engineering works scheduled.");
            } else {
                for (TFLService.ServiceUpdate update : updates) {
                    message.append(update.toString()).append("\n\n");
                }
            }

            SendMessage sendMessage = new SendMessage();
            sendMessage.setChatId(chatId);
            sendMessage.setText(message.toString());
            sendMessage.setParseMode("Markdown");
            
            execute(sendMessage);
        } catch (Exception e) {
            logger.error("Failed to get service updates", e);
            sendText(chatId, "❌ Failed to retrieve service updates. Please try again.");
        }
    }

    private void checkDisruptionsOnDemand(long chatId) {
        List<TFLService.DisruptionInfo> disruptions = notificationScheduler.checkDisruptionsOnDemand();
        
        StringBuilder message = new StringBuilder("🚨 *Current Disruptions*\n\n");
        
        if (disruptions.isEmpty()) {
            message.append("✅ No severe disruptions currently reported!");
        } else {
            for (TFLService.DisruptionInfo disruption : disruptions) {
                message.append(disruption.toString()).append("\n\n");
            }
        }

        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(chatId);
        sendMessage.setText(message.toString());
        sendMessage.setParseMode("Markdown");
        
        try {
            execute(sendMessage);
        } catch (TelegramApiException e) {
            logger.error("Failed to send disruptions", e);
        }
    }

    private void showFavorites(long chatId) {
        Long userId = chatId; // In this context, chatId is the same as userId for private chats
        Set<String> favorites = preferencesService.getFavoriteLines(userId);

        if (favorites.isEmpty()) {
            sendText(chatId, "⭐ You don't have any favorite lines yet!\n\nUse the line-specific menu to add favorites.");
            return;
        }

        StringBuilder message = new StringBuilder("⭐ *Your Favorite Lines:*\n\n");
        
        try {
            for (String line : favorites) {
                String status = tflApiService.parseLineStatus(tflApiService.getLineStatus(line));
                message.append(status).append("\n");
            }
        } catch (IOException e) {
            message.append("❌ Failed to retrieve status for some lines.");
        }

        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(chatId);
        sendMessage.setText(message.toString());
        sendMessage.setParseMode("Markdown");

        try {
            execute(sendMessage);
        } catch (TelegramApiException e) {
            logger.error("Failed to send favorites", e);
        }
    }

    private void showSettings(long chatId) {
        Long userId = chatId;
        UserPreferencesService.UserPreferences prefs = preferencesService.getUserPreferences(userId);

        var disruptionButton = InlineKeyboardButton.builder()
                .text("🔔 Disruption Alerts: " + (prefs.isDisruptionAlertsEnabled() ? "ON" : "OFF"))
                .callbackData("toggle_disruption_alerts")
                .build();

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(disruptionButton))
                .build();

        sendMenu(chatId, "⚙️ *Settings*\n\nManage your notification preferences:", keyboard);
    }

    private void showHelp(long chatId) {
        String helpText = "🤖 *AbiTFLBot Help*\n\n" +
                "*Commands:*\n" +
                "/start - Show main menu\n" +
                "/favorites - Show your favorite lines\n" +
                "/settings - Manage preferences\n" +
                "/help - Show this help\n\n" +
                "*Features:*\n" +
                "📊 Overview - See all tube line statuses\n" +
                "🚇 Line Specific - Check individual lines\n" +
                "⭐ Favorites - Save frequently used lines\n" +
                "🔔 Alerts - Get notified of disruptions\n\n" +
                "*Tips:*\n" +
                "• Add lines to favorites for quick access\n" +
                "• Enable disruption alerts for important updates\n" +
                "• Use the inline keyboard for easy navigation";

        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(chatId);
        sendMessage.setText(helpText);
        sendMessage.setParseMode("Markdown");

        try {
            execute(sendMessage);
        } catch (TelegramApiException e) {
            logger.error("Failed to send help", e);
        }
    }

    private void sendLineOptions(Long chatId) {
        // Define the lines in a custom layout
        List<List<InlineKeyboardButton>> buttons = new ArrayList<>();

        buttons.add(List.of(
                InlineKeyboardButton.builder().text("Bakerloo").callbackData("bakerloo").build(),
                InlineKeyboardButton.builder().text("Central").callbackData("central").build(),
                InlineKeyboardButton.builder().text("Circle").callbackData("circle").build()
        ));

        buttons.add(List.of(
                InlineKeyboardButton.builder().text("District").callbackData("district").build(),
                InlineKeyboardButton.builder().text("Waterloo & City").callbackData("waterloo-city").build(),
                InlineKeyboardButton.builder().text("Jubilee").callbackData("jubilee").build()
        ));

        buttons.add(List.of(
                InlineKeyboardButton.builder().text("Metropolitan").callbackData("metropolitan").build(),
                InlineKeyboardButton.builder().text("Northern").callbackData("northern").build(),
                InlineKeyboardButton.builder().text("Piccadilly").callbackData("piccadilly").build()
        ));

        buttons.add(List.of(
                InlineKeyboardButton.builder().text("Victoria").callbackData("victoria").build(),
                InlineKeyboardButton.builder().text("Hammersmith & City").callbackData("hammersmith-city").build()
        ));

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboard(buttons)
                .build();

        sendMenu(chatId, "Please select a line:", keyboard);
    }

    private void sendMenu(Long chatId, String text, InlineKeyboardMarkup keyboard) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .replyMarkup(keyboard)
                .build();

        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void sendText(Long chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .build();

        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void handleOverviewRequest(long chatId) {
        try {
            String overview = tflApiService.parseLineStatus(tflApiService.getAllLineStatuses());
            
            SendMessage sendMessage = new SendMessage();
            sendMessage.setChatId(chatId);
            sendMessage.setText("📊 *TFL Tube Overview*\n\n" + overview);
            sendMessage.setParseMode("Markdown");
            
            execute(sendMessage);
        } catch (IOException e) {
            logger.error("Failed to get overview", e);
            sendText(chatId, "❌ Failed to retrieve data. Please try again.");
        } catch (TelegramApiException e) {
            logger.error("Failed to send overview", e);
        }
    }

    private void handleLineSpecificRequest(long chatId, String lineId) {
        try {
            String status = tflApiService.parseLineStatus(tflApiService.getLineStatus(lineId));
            
            // Add favorite button
            var addFavButton = InlineKeyboardButton.builder()
                    .text("⭐ Add to Favorites")
                    .callbackData("add_fav_" + lineId)
                    .build();

            InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                    .keyboardRow(List.of(addFavButton))
                    .build();

            SendMessage sendMessage = new SendMessage();
            sendMessage.setChatId(chatId);
            sendMessage.setText(status);
            sendMessage.setParseMode("Markdown");
            sendMessage.setReplyMarkup(keyboard);
            
            execute(sendMessage);
        } catch (IOException e) {
            logger.error("Failed to get line status for {}", lineId, e);
            sendText(chatId, "❌ Failed to retrieve data for " + lineId + ". Please try again.");
        } catch (TelegramApiException e) {
            logger.error("Failed to send line status", e);
        }
    }

    public static void main(String[] args) throws TelegramApiException {
        logger.info("Starting TFL Bot...");
        
        // Multi-platform port configuration
        String port = getPort();
        if (port != null) {
            logger.info("Deployment PORT detected: {}", port);
            // Start a simple health check server for platforms that require it
            startHealthCheckServer(Integer.parseInt(port));
        }
        
        // Database connection logging
        String dbUrl = System.getenv("DATABASE_URL");
        if (dbUrl != null) {
            logger.info("Database connection configured: {}", maskDbUrl(dbUrl));
        } else {
            logger.warn("DATABASE_URL not found - using default database settings");
        }
        
        // Log deployment platform
        String platform = detectPlatform();
        logger.info("Detected deployment platform: {}", platform);
        
        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        Bot bot = new Bot();
        botsApi.registerBot(bot);
        
        logger.info("TFL Bot started successfully on {}!", platform);
        
        // Graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down bot...");
            bot.notificationScheduler.stop();
            DatabaseManager.getInstance().close();
            logger.info("Bot shutdown complete");
        }));
    }
    
    private static String getPort() {
        // Check various port environment variables used by different platforms
        String port = System.getenv("PORT");           // Heroku, Render, Railway
        if (port == null) port = System.getenv("HTTP_PORT");  // Some platforms
        if (port == null) port = System.getenv("SERVER_PORT"); // Custom
        return port;
    }
    
    private static String detectPlatform() {
        if (System.getenv("AWS_EXECUTION_ENV") != null || System.getenv("AWS_REGION") != null) return "AWS";
        if (System.getenv("RENDER") != null) return "Render";
        if (System.getenv("HEROKU_APP_NAME") != null) return "Heroku";
        if (System.getenv("RAILWAY_ENVIRONMENT") != null) return "Railway";
        if (System.getenv("VERCEL") != null) return "Vercel";
        return "Local/Unknown";
    }
    
    private static void startHealthCheckServer(int port) {
        new Thread(() -> {
            try {
                com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                    new java.net.InetSocketAddress(port), 0);
                
                server.createContext("/health", exchange -> {
                    String response = "Bot is healthy";
                    exchange.sendResponseHeaders(200, response.length());
                    try (java.io.OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                });
                
                server.createContext("/", exchange -> {
                    String response = "TFL Bot is running on " + detectPlatform();
                    exchange.sendResponseHeaders(200, response.length());
                    try (java.io.OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                });
                
                server.createContext("/status", exchange -> {
                    String response = "{\"status\":\"running\",\"platform\":\"" + detectPlatform() + "\",\"timestamp\":\"" + java.time.Instant.now() + "\"}";
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, response.length());
                    try (java.io.OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                });
                
                server.setExecutor(null);
                server.start();
                logger.info("Health check server started on port {}", port);
            } catch (Exception e) {
                logger.error("Failed to start health check server", e);
            }
        }).start();
    }
    
    private static String maskDbUrl(String dbUrl) {
        // Mask password in database URL for logging
        if (dbUrl.contains("@")) {
            String[] parts = dbUrl.split("@");
            if (parts[0].contains(":")) {
                String[] userPass = parts[0].split(":");
                if (userPass.length >= 3) {
                    return userPass[0] + ":" + userPass[1] + ":****@" + parts[1];
                }
            }
        }
        return dbUrl.replaceAll("password=[^&\\s]+", "password=****");
    }

    private void handleJourneyPlannerInput(long chatId, String messageText) {
        try {
            String from, to;
            
            if (messageText.toLowerCase().startsWith("from ") && messageText.toLowerCase().contains(" to ")) {
                // Parse "from X to Y" format
                String[] parts = messageText.toLowerCase().split(" to ");
                from = parts[0].substring(5).trim(); // Remove "from "
                to = parts[1].trim();
            } else if (messageText.toLowerCase().contains(" to ")) {
                // Parse "X to Y" format
                String[] parts = messageText.toLowerCase().split(" to ");
                from = parts[0].trim();
                to = parts[1].trim();
            } else {
                sendText(chatId, "❌ Please use the format: 'from [station] to [station]'\nExample: from King's Cross to Oxford Circus\n\nOr simply: King's Cross to Oxford Circus");
                return;
            }

            // Capitalize station names for better display
            from = capitalizeWords(from);
            to = capitalizeWords(to);

            String journeyInfo = tflApiService.getJourneyPlan(from, to);
            
            SendMessage sendMessage = new SendMessage();
            sendMessage.setChatId(chatId);
            sendMessage.setText("🗺️ *Journey from " + from + " to " + to + "*\n\n" + journeyInfo);
            sendMessage.setParseMode("Markdown");
            
            execute(sendMessage);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid journey request: {}", messageText, e);
            sendText(chatId, "❌ " + e.getMessage() + "\n\nPlease check the station names and try again.");
        } catch (Exception e) {
            logger.error("Failed to get journey plan", e);
            sendText(chatId, "❌ Failed to plan your journey. Please check station names and try again.\n\nTip: Try using full station names like 'King's Cross St. Pancras' or 'Oxford Circus'");
        }
    }

    private void handleScheduleTimeInput(long chatId, Long userId, String timeText) {
        if (!preferencesService.isValidTime(timeText)) {
            sendText(chatId, "❌ Invalid time format. Please use HH:MM (24-hour format).\nExample: 08:30 or 17:45");
            return;
        }

        try {
            preferencesService.addScheduledNotification(userId, timeText, new UserPreferencesService.NotificationSettings());
            sendText(chatId, "✅ Added notification for " + timeText + " (all lines, all statuses)\n\nUse settings to customize specific lines.");
            showScheduleNotificationsMenu(chatId);
        } catch (Exception e) {
            logger.error("Failed to add scheduled notification", e);
            sendText(chatId, "❌ Failed to add notification. Please try again.");
        }
    }

    private void handleAddSchedule(long chatId) {
        awaitingScheduleTime = true;
        sendText(chatId, "⏰ *Add Scheduled Notification*\n\n" +
                "Please send the time when you want to receive notifications.\n\n" +
                "Format: HH:MM (24-hour format)\n" +
                "Examples: 08:30, 17:45, 23:00\n\n" +
                "This will notify you of all line statuses at the specified time.");
    }

    private void handleRemoveSchedule(long chatId, Long userId) {
        UserPreferencesService.UserPreferences prefs = preferencesService.getUserPreferences(userId);
        
        if (prefs.getScheduledNotifications().isEmpty()) {
            sendText(chatId, "❌ You don't have any scheduled notifications to remove.");
            return;
        }

        List<List<InlineKeyboardButton>> buttons = new ArrayList<>();
        
        prefs.getScheduledNotifications().keySet().forEach(time -> {
            var button = InlineKeyboardButton.builder()
                    .text("🗑️ " + time.toString())
                    .callbackData("remove_schedule_" + time.toString())
                    .build();
            buttons.add(List.of(button));
        });

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboard(buttons)
                .build();

        sendMenu(chatId, "Select a notification time to remove:", keyboard);
    }

    private boolean isValidLineId(String lineId) {
        Set<String> validLines = Set.of(
            "bakerloo", "central", "circle", "district", "hammersmith-city",
            "jubilee", "metropolitan", "northern", "piccadilly", "victoria",
            "waterloo-city", "elizabeth", "london-overground", "dlr"
        );
        return validLines.contains(lineId.toLowerCase());
    }

    private void handleStationInfoInput(long chatId, String stationName) {
        try {
            String capitalizedStationName = capitalizeWords(stationName.trim());
            TFLService.StationInfo stationInfo = tflApiService.getStationInfo(capitalizedStationName);
            
            SendMessage sendMessage = new SendMessage();
            sendMessage.setChatId(chatId);
            sendMessage.setText("🚉 *" + capitalizedStationName + " Station*\n\n" + stationInfo.toString());
            sendMessage.setParseMode("Markdown");
            
            execute(sendMessage);
        } catch (IllegalArgumentException e) {
            logger.warn("Station not found: {}", stationName, e);
            sendText(chatId, "❌ Station '" + stationName + "' not found.\n\nPlease check the spelling and try again.\n\nTip: Try full names like 'King's Cross St. Pancras' or 'Leicester Square'");
        } catch (Exception e) {
            logger.error("Failed to get station info for {}", stationName, e);
            sendText(chatId, "❌ Failed to get information for '" + stationName + "'.\n\nPlease try again or check the station name.");
        }
    }

    private String capitalizeWords(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        
        String[] words = text.toLowerCase().split("\\s+");
        StringBuilder result = new StringBuilder();
        
        for (int i = 0; i < words.length; i++) {
            if (i > 0) {
                result.append(" ");
            }
            
            String word = words[i];
            if (word.length() > 0) {
                result.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) {
                    result.append(word.substring(1));
                }
            }
        }
        
        return result.toString();
    }

    // ...existing code...
}