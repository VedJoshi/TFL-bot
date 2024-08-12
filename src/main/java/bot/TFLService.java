package bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Scanner;

public class TFLService {

    private static final String API_BASE_URL = "https://api.tfl.gov.uk";

    // Get all line statuses
    public String getAllLineStatuses() throws IOException {
        String endpoint = API_BASE_URL + "/line/mode/tube/status";
        return getResponse(endpoint);
    }

    // Get status of a specific line
    public String getLineStatus(String lineId) throws IOException {
        String endpoint = API_BASE_URL + "/line/" + lineId + "/status";
        return getResponse(endpoint);
    }

    private String getResponse(String endpoint) throws IOException {
        URL url = new URL(endpoint);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");

        Scanner scanner = new Scanner(url.openStream());
        StringBuilder response = new StringBuilder();

        while (scanner.hasNext()) {
            response.append(scanner.nextLine());
        }

        scanner.close();
        return response.toString();
    }

    // Method to parse JSON and extract relevant information
    public String parseLineStatus(String jsonResponse) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode rootNode = mapper.readTree(jsonResponse);
        StringBuilder status = new StringBuilder();

        for (JsonNode line : rootNode) {
            String name = line.get("name").asText();
            String statusDescription = line.get("lineStatuses").get(0).get("statusSeverityDescription").asText();
            String reason = line.get("lineStatuses").get(0).path("reason").asText("");
            status.append(name).append(": ").append(statusDescription).append("\n").append(reason).append("\n\n");
        }

        return status.toString();
    }
}
