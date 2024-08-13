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
                sendLineOptions(chatId);
            } else {
                // Assume any other callback data is a line-specific request
                handleLineSpecificRequest(chatId, callbackData);
            }
        }
    }

    private InlineKeyboardMarkup getMainMenu() {
        var overviewButton = InlineKeyboardButton.builder()
                .text("Overview")
                .callbackData("overview")
                .build();

        var lineButton = InlineKeyboardButton.builder()
                .text("Line Specific")
                .callbackData("line")
                .build();

        return InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(overviewButton, lineButton))
                .build();
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