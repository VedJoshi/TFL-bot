package bot;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.CopyMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageReplyMarkup;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.updatesreceivers.DefaultBotSession;

import java.io.IOException;
import java.util.List;

public class Bot extends TelegramLongPollingBot {

    private boolean screaming = false;
    private InlineKeyboardMarkup keyboardM1;
    private InlineKeyboardMarkup keyboardM2;


    @Override
    public String getBotUsername() {
        return "AbiTFLBot";
    }
    @Override
    public String getBotToken() {
        return "7452383443:AAFF78bDNVsn82kZTP8tQ6CGSc2EL7t19tU";
    }

    public Bot() {
        var next = InlineKeyboardButton.builder()
                .text("Next").callbackData("next")
                .build();

        var back = InlineKeyboardButton.builder()
                .text("Back").callbackData("back")
                .build();

        var url = InlineKeyboardButton.builder()
                .text("Tutorial")
                .url("https://core.telegram.org/bots/api")
                .build();

        // Create keyboards
        keyboardM1 = InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(next))
                .build();

        keyboardM2 = InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(back))
                .keyboardRow(List.of(url))
                .build();
    }

    private final TFLService tflApiService = new TFLService();

    private void handleOverviewRequest(long chatId) {
        try {
            String overview = tflApiService.parseLineStatus(tflApiService.getAllLineStatuses());
            sendText(chatId, overview);
        } catch (IOException e) {
            sendText(chatId, "Failed to retrieve data. Please try again.");
        }
    }

    private void handleLineSpecificRequest(long chatId, String lineId) {
        try {
            String status = tflApiService.parseLineStatus(tflApiService.getLineStatus(lineId));
            sendText(chatId, status);
        } catch (IOException e) {
            sendText(chatId, "Failed to retrieve data. Please try again.");
        }
    }



    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            String messageText = update.getMessage().getText();
            long chatId = update.getMessage().getChatId();

            if (messageText.equals("/start")) {
                sendMenu(chatId, "Welcome to the AbiTFLBot! Choose an option:", getMainMenu());
            }
        } else if (update.hasCallbackQuery()) {
        String callbackData = update.getCallbackQuery().getData();
        long chatId = update.getCallbackQuery().getMessage().getChatId();

        if (callbackData.equals("overview")) {
            sendText(chatId, "Fetching overview...");
            // fetching and receiving here
        } else if (callbackData.equals("line")) {
            sendText(chatId, "Please enter the line name (e.g., central, piccadilly):");
        }
    }
    }

    private InlineKeyboardMarkup getMainMenu() {
        var overviewButton = InlineKeyboardButton.builder()
                .text("Overview").callbackData("overview")
                .build();

        var lineButton = InlineKeyboardButton.builder()
                .text("Line Specific").callbackData("line")
                .build();

        return InlineKeyboardMarkup.builder()
                .keyboardRow(List.of(overviewButton, lineButton))
                .build();
    }


    private void sendMenu(Long chatId, String text, InlineKeyboardMarkup keyboard) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .parseMode("HTML")
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

    public void copyMessage(Long who, Integer msgId){
        CopyMessage cm = CopyMessage.builder()
                .fromChatId(who.toString())  //We copy from the user
                .chatId(who.toString())      //And send it back to him
                .messageId(msgId)            //Specifying what message
                .build();
        try {
            execute(cm);
        } catch (TelegramApiException e) {
            throw new RuntimeException(e);
        }
    }

    private void buttonTap(Long id, String queryId, String data, int msgId) throws TelegramApiException {

        EditMessageText newTxt = EditMessageText.builder()
                .chatId(id.toString())
                .messageId(msgId).text("").build();

        EditMessageReplyMarkup newKb = EditMessageReplyMarkup.builder()
                .chatId(id.toString()).messageId(msgId).build();

        if(data.equals("next")) {
            newTxt.setText("MENU 2");
            newKb.setReplyMarkup(keyboardM2);
        } else if(data.equals("back")) {
            newTxt.setText("MENU 1");
            newKb.setReplyMarkup(keyboardM1);
        }

        AnswerCallbackQuery close = AnswerCallbackQuery.builder()
                .callbackQueryId(queryId).build();

        execute(close);
        execute(newTxt);
        execute(newKb);
    }

    private void scream(Long id, Message msg) {
        if(msg.hasText())
            sendText(id, msg.getText().toUpperCase());
        else
            copyMessage(id, msg.getMessageId()); //If it's not text, we just copy the message
    }

    public static void main(String[] args) throws TelegramApiException {
        TelegramBotsApi botsApi = new TelegramBotsApi(DefaultBotSession.class);
        Bot bot = new Bot();
        botsApi.registerBot(bot);
    }
}