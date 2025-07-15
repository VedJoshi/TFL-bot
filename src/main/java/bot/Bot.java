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

    public Bot() {
        super();
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

            if (awaitingLineName) {
                handleLineSpecificRequest(chatId, messageText.toLowerCase());
                awaitingLineName = false;
            } else if (messageText.equals("/start")) {
                sendMenu(chatId, "Welcome to the AbiTFLBot! 🚇\nChoose an option:", getMainMenu());
            } else if (messageText.equals("/favorites")) {
                showFavorites(chatId);
            } else if (messageText.equals("/settings")) {
                showSettings(chatId);
            } else if (messageText.equals("/help")) {
                showHelp(chatId);
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
        } else {
            handleLineSpecificRequest(chatId, callbackData);
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

        var favoritesButton = InlineKeyboardButton.builder()
                .text("⭐ Favorites")
                .callbackData("favorites")
                .build();

        var settingsButton = InlineKeyboardButton.builder()
                .text("⚙️ Settings")
                .callbackData("settings")
                .build();

        return InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(overviewButton, lineButton))
                .keyboardRow(List.of(favoritesButton, settingsButton))
                .build();
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
        
        // Railway port configuration
        String port = System.getenv("PORT");
        if (port != null) {
            logger.info("Railway PORT detected: {}", port);
        }
        
        // Log database connection for Railway debugging
        String dbUrl = System.getenv("DATABASE_URL");
        if (dbUrl != null) {
            logger.info("Database connection configured for Railway");
        } else {
            logger.warn("DATABASE_URL not found - using default database settings");
        }
        
        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        Bot bot = new Bot();
        botsApi.registerBot(bot);
        
        logger.info("TFL Bot started successfully!");
        
        // Graceful shutdown
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down bot...");
            bot.notificationScheduler.stop();
            DatabaseManager.getInstance().close();
            logger.info("Bot shutdown complete");
        }));
    }
}