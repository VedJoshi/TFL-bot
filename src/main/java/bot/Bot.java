package bot;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;
import io.github.cdimascio.dotenv.Dotenv;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class Bot extends TelegramLongPollingBot {

    private final TFLService tflApiService = new TFLService();

    private final Dotenv dotenv = Dotenv.load();

    private boolean awaitingLineName = false; // Flag to track when the bot is waiting for the line name input

    @Override
    public String getBotUsername() {
        return "AbiTFLBot";
    }

    @Override
    public String getBotToken() {
        String token = dotenv.get("TELEBOT_API_KEY");
        return token;
    }
    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();

            if (awaitingLineName) {
                // If the bot is waiting for the line name, handle the line-specific request
                handleLineSpecificRequest(chatId, messageText.toLowerCase());
                awaitingLineName = false; // Reset the flag after handling the request
            } else if (messageText.equals("/start")) {
                // Show the main menu when the user sends /start
                sendMenu(chatId, "Welcome to the AbiTFLBot! Choose an option:", getMainMenu());
            }
        } else if (update.hasCallbackQuery()) {
            String callbackData = update.getCallbackQuery().getData();
            long chatId = update.getCallbackQuery().getMessage().getChatId();

            if (callbackData.equals("overview")) {
                // Handle the overview request when the "Overview" button is clicked
                handleOverviewRequest(chatId);
            } else if (callbackData.equals("line")) {
                // Show line-specific options when the "Line Specific" button is clicked
                try {
                    sendLineOptions(chatId);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                // Assume any other callback data is a line-specific request
                handleLineSpecificRequest(chatId, callbackData);
            }
        }
    }

    private InlineKeyboardMarkup getMainMenu() {
        // Create buttons for "Overview" and "Line Specific"
        var overviewButton = InlineKeyboardButton.builder()
                .text("Overview")
                .callbackData("overview")
                .build();

        var lineButton = InlineKeyboardButton.builder()
                .text("Line Specific")
                .callbackData("line")
                .build();

        // Create an inline keyboard with the buttons
        return InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(overviewButton, lineButton))
                .build();
    }

    private void sendLineOptions(Long chatId) throws IOException {
        try {
            List<String> lineNames = tflApiService.getAllLineNames();
            List<List<InlineKeyboardButton>> buttons = new ArrayList<>();

            for (String line : lineNames) {
                InlineKeyboardButton button = InlineKeyboardButton.builder()
                        .text(line.substring(0, 1).toUpperCase() + line.substring(1))
                        .callbackData(line.toLowerCase())
                        .build();
                buttons.add(List.of(button));
            }
            InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                    .keyboard(buttons)
                    .build();
            sendMenu(chatId, "Please select a line:", keyboard);
        } catch (IOException e) {
            sendText(chatId, "Failed to retrieve data. Please try again.");
        }
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
            // Get and send the overview of all lines
            String overview = tflApiService.parseLineStatus(tflApiService.getAllLineStatuses());
            sendText(chatId, overview);
        } catch (IOException e) {
            sendText(chatId, "Failed to retrieve data. Please try again.");
        }
    }

    private void handleLineSpecificRequest(long chatId, String lineId) {
        try {
            // Get and send the status of the specified line
            String status = tflApiService.parseLineStatus(tflApiService.getLineStatus(lineId));
            sendText(chatId, status);
        } catch (IOException e) {
            sendText(chatId, "Failed to retrieve data. Please try again.");
        }
    }

    public static void main(String[] args) throws TelegramApiException {
        // Main method to start the bot
        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        Bot bot = new Bot();
        botsApi.registerBot(bot);
    }
}