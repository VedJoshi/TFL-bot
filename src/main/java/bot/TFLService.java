package bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TFLService {
    private static final Logger logger = LoggerFactory.getLogger(TFLService.class);
    private static final String API_BASE_URL = "https://api.tfl.gov.uk";
    private static final int CONNECTION_TIMEOUT = 10000; // 10 seconds
    private static final int READ_TIMEOUT = 15000; // 15 seconds
    private static final int MAX_RETRIES = 3;
    private static final int CACHE_DURATION_MINUTES = 2;

    // Simple cache implementation
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    private static class CacheEntry {
        final String data;
        final LocalDateTime timestamp;

        CacheEntry(String data) {
            this.data = data;
            this.timestamp = LocalDateTime.now();
        }

        boolean isExpired() {
            return ChronoUnit.MINUTES.between(timestamp, LocalDateTime.now()) > CACHE_DURATION_MINUTES;
        }
    }

    // Get all line statuses
    public String getAllLineStatuses() throws IOException {
        String endpoint = API_BASE_URL + "/line/mode/tube/status";
        return getCachedResponse(endpoint);
    }

    // Get status of a specific line
    public String getLineStatus(String lineId) throws IOException {
        if (lineId == null || lineId.trim().isEmpty()) {
            throw new IllegalArgumentException("Line ID cannot be null or empty");
        }
        String endpoint = API_BASE_URL + "/line/" + lineId.toLowerCase().replace(" ", "-") + "/status";
        return getCachedResponse(endpoint);
    }

    public List<String> getAllLineNames() throws IOException {
        String endpoint = API_BASE_URL + "/line/mode/tube";
        String response = getCachedResponse(endpoint);
        return parseLineNames(response);
    }

    public List<DisruptionInfo> getDisruptions() throws IOException {
        String endpoint = API_BASE_URL + "/line/mode/tube/status";
        String response = getCachedResponse(endpoint);
        return parseDisruptions(response);
    }

    public List<DisruptionInfo> getSevereDisruptions() throws IOException {
        return getDisruptions().stream()
                .filter(disruption -> isSevereDisruption(disruption.statusSeverity))
                .toList();
    }

    private String getCachedResponse(String endpoint) throws IOException {
        CacheEntry cached = cache.get(endpoint);
        if (cached != null && !cached.isExpired()) {
            logger.debug("Cache hit for endpoint: {}", endpoint);
            return cached.data;
        }

        String response = getResponseWithRetry(endpoint);
        cache.put(endpoint, new CacheEntry(response));
        return response;
    }

    private String getResponseWithRetry(String endpoint) throws IOException {
        IOException lastException = null;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return getResponse(endpoint);
            } catch (SocketTimeoutException e) {
                lastException = e;
                logger.warn("Timeout on attempt {} for endpoint: {}", attempt, endpoint);
                if (attempt < MAX_RETRIES) {
                    try {
                        Thread.sleep(1000 * attempt); // Exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Request interrupted", ie);
                    }
                }
            } catch (IOException e) {
                lastException = e;
                logger.error("Error on attempt {} for endpoint: {}", attempt, endpoint, e);
                if (attempt == MAX_RETRIES) break;
            }
        }

        throw new IOException("Failed after " + MAX_RETRIES + " attempts", lastException);
    }

    private String getResponse(String endpoint) throws IOException {
        URL url = new URL(endpoint);
        HttpURLConnection conn = null;
        Scanner scanner = null;

        try {
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECTION_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            conn.setRequestProperty("User-Agent", "TFL-Bot/1.0");

            int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                throw new IOException("TFL API returned status code: " + responseCode);
            }

            scanner = new Scanner(conn.getInputStream());
            StringBuilder response = new StringBuilder();

            while (scanner.hasNextLine()) {
                response.append(scanner.nextLine());
            }

            return response.toString();

        } finally {
            if (scanner != null) {
                scanner.close();
            }
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // Method to parse JSON and extract relevant information
    public String parseLineStatus(String jsonResponse) throws IOException {
        JsonNode rootNode = mapper.readTree(jsonResponse);
        StringBuilder status = new StringBuilder();

        if (rootNode.isArray()) {
            for (JsonNode line : rootNode) {
                JsonNode nameNode = line.get("name");
                JsonNode lineStatusesNode = line.get("lineStatuses");

                if (nameNode != null && lineStatusesNode != null && lineStatusesNode.isArray() && lineStatusesNode.size() > 0) {
                    String name = nameNode.asText();
                    JsonNode firstStatus = lineStatusesNode.get(0);
                    String statusDescription = firstStatus.path("statusSeverityDescription").asText("Unknown");
                    String reason = firstStatus.path("reason").asText("");

                    status.append("🚇 *").append(name).append("*: ").append(statusDescription);
                    if (!reason.isEmpty()) {
                        status.append("\n   ").append(reason);
                    }
                    status.append("\n\n");
                }
            }
        }

        return status.toString();
    }

    private List<String> parseLineNames(String jsonResponse) throws IOException {
        JsonNode rootNode = mapper.readTree(jsonResponse);
        List<String> lineNames = new ArrayList<>();

        if (rootNode.isArray()) {
            for (JsonNode line : rootNode) {
                JsonNode nameNode = line.get("name");
                if (nameNode != null) {
                    lineNames.add(nameNode.asText());
                }
            }
        }

        return lineNames;
    }

    private List<DisruptionInfo> parseDisruptions(String jsonResponse) throws IOException {
        List<DisruptionInfo> disruptions = new ArrayList<>();
        JsonNode rootNode = mapper.readTree(jsonResponse);

        if (rootNode.isArray()) {
            for (JsonNode line : rootNode) {
                JsonNode nameNode = line.get("name");
                JsonNode lineStatusesNode = line.get("lineStatuses");

                if (nameNode != null && lineStatusesNode != null && lineStatusesNode.isArray()) {
                    String lineName = nameNode.asText();

                    for (JsonNode status : lineStatusesNode) {
                        int severity = status.path("statusSeverity").asInt(10);
                        String description = status.path("statusSeverityDescription").asText();
                        String reason = status.path("reason").asText("");

                        if (severity < 10) { // 10 is "Good Service"
                            disruptions.add(new DisruptionInfo(lineName, severity, description, reason));
                        }
                    }
                }
            }
        }

        return disruptions;
    }

    private boolean isSevereDisruption(int severity) {
        return severity <= 6; // Severe delays, part closure, etc.
    }

    public static class DisruptionInfo {
        public final String lineName;
        public final int statusSeverity;
        public final String description;
        public final String reason;

        public DisruptionInfo(String lineName, int statusSeverity, String description, String reason) {
            this.lineName = lineName;
            this.statusSeverity = statusSeverity;
            this.description = description;
            this.reason = reason;
        }

        @Override
        public String toString() {
            return "🚨 *" + lineName + "*: " + description +
                    (reason.isEmpty() ? "" : "\n   " + reason);
        }
    }
}
