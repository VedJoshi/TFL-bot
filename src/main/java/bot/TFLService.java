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
        
        // Validate that this is a proper line ID, not a callback command
        if (!isValidLineId(lineId)) {
            throw new IllegalArgumentException("Invalid line ID: " + lineId);
        }
        
        String endpoint = API_BASE_URL + "/line/" + lineId.toLowerCase().replace(" ", "-") + "/status";
        return getCachedResponse(endpoint);
    }

    private boolean isValidLineId(String lineId) {
        // List of valid TFL line IDs
        Set<String> validLines = Set.of(
            "bakerloo", "central", "circle", "district", "hammersmith-city",
            "jubilee", "metropolitan", "northern", "piccadilly", "victoria",
            "waterloo-city", "elizabeth", "london-overground", "dlr"
        );
        return validLines.contains(lineId.toLowerCase());
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

        /**
         * Generate a unique identifier for this disruption based on line name, severity, and description
         */
        public String getId() {
            return lineName + "|" + statusSeverity + "|" + description.hashCode();
        }

        @Override
        public String toString() {
            return "🚨 *" + lineName + "*: " + description +
                    (reason.isEmpty() ? "" : "\n   " + reason);
        }
    }

    public List<JourneyOption> planJourney(String fromStation, String toStation) throws IOException {
        // Use the correct TfL journey planner endpoint
        String endpoint = API_BASE_URL + "/journey/journeyresults/" + 
                java.net.URLEncoder.encode(fromStation, "UTF-8") + "/to/" + 
                java.net.URLEncoder.encode(toStation, "UTF-8");
        String response = getCachedResponse(endpoint);
        return parseJourneyOptions(response);
    }

    public StationInfo getStationInfo(String stationName) throws IOException {
        // First, search for the station to get its ID
        String searchEndpoint = API_BASE_URL + "/StopPoint/Search/" + 
                java.net.URLEncoder.encode(stationName, "UTF-8") + "?modes=tube";
        String searchResponse = getCachedResponse(searchEndpoint);
        String stationId = parseStationId(searchResponse);
        
        if (stationId == null) {
            throw new IllegalArgumentException("Station not found: " + stationName);
        }

        // Get station details using the correct endpoint
        String stationEndpoint = API_BASE_URL + "/stoppoint/" + stationId;
        String stationResponse = getCachedResponse(stationEndpoint);

        // Get live arrivals using the correct endpoint
        String arrivalsEndpoint = API_BASE_URL + "/stoppoint/" + stationId + "/arrivals";
        String arrivalsResponse = getCachedResponse(arrivalsEndpoint);

        return parseStationInfo(stationResponse, arrivalsResponse, stationName);
    }

    /**
     * Get journey plan as a formatted string
     */
    public String getJourneyPlan(String fromStation, String toStation) throws IOException {
        List<JourneyOption> options = planJourney(fromStation, toStation);
        
        if (options.isEmpty()) {
            return "❌ No journey options found. Please check station names.";
        }
        
        StringBuilder result = new StringBuilder();
        result.append("🗺️ *Journey Options:*\n\n");
        
        for (int i = 0; i < Math.min(3, options.size()); i++) {
            result.append("**Option ").append(i + 1).append(":**\n");
            result.append(options.get(i).toString()).append("\n");
        }
        
        return result.toString();
    }

    public List<ServiceUpdate> getServiceUpdates() throws IOException {
        String endpoint = API_BASE_URL + "/line/mode/tube,overground,dlr/status";
        String response = getCachedResponse(endpoint);
        return parseServiceUpdates(response);
    }

    private List<ServiceUpdate> parseServiceUpdates(String jsonResponse) throws IOException {
        List<ServiceUpdate> updates = new ArrayList<>();
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

                        // Include planned works and other non-good service statuses
                        if (severity < 10 || !reason.isEmpty()) {
                            updates.add(new ServiceUpdate(lineName, description, reason));
                        }
                    }
                }
            }
        }

        return updates;
    }

    private String parseStationId(String jsonResponse) throws IOException {
        JsonNode rootNode = mapper.readTree(jsonResponse);
        JsonNode matchesNode = rootNode.get("matches");

        if (matchesNode != null && matchesNode.isArray() && matchesNode.size() > 0) {
            JsonNode firstMatch = matchesNode.get(0);
            return firstMatch.path("id").asText();
        }
        return null;
    }

    private String parseStationCode(String jsonResponse) throws IOException {
        return parseStationId(jsonResponse);
    }

    private List<JourneyOption> parseJourneyOptions(String jsonResponse) throws IOException {
        List<JourneyOption> options = new ArrayList<>();
        JsonNode rootNode = mapper.readTree(jsonResponse);
        JsonNode journeysNode = rootNode.get("journeys");

        if (journeysNode != null && journeysNode.isArray()) {
            for (int i = 0; i < Math.min(3, journeysNode.size()); i++) {
                JsonNode journey = journeysNode.get(i);
                int duration = journey.path("duration").asInt();

                List<String> steps = new ArrayList<>();
                JsonNode legsNode = journey.get("legs");
                if (legsNode != null && legsNode.isArray()) {
                    for (JsonNode leg : legsNode) {
                        JsonNode modeNode = leg.get("mode");
                        String mode = modeNode != null ? modeNode.path("name").asText("Walking") : "Walking";
                        
                        JsonNode instructionNode = leg.get("instruction");
                        String instruction = instructionNode != null ? 
                                instructionNode.path("summary").asText("Continue") : "Continue";
                        
                        String departurePoint = leg.path("departurePoint").path("commonName").asText("");
                        String arrivalPoint = leg.path("arrivalPoint").path("commonName").asText("");
                        
                        StringBuilder stepText = new StringBuilder();
                        stepText.append(mode);
                        if (!departurePoint.isEmpty() && !arrivalPoint.isEmpty()) {
                            stepText.append(" from ").append(departurePoint).append(" to ").append(arrivalPoint);
                        } else if (!instruction.isEmpty()) {
                            stepText.append(": ").append(instruction);
                        }
                        
                        steps.add(stepText.toString());
                    }
                }

                options.add(new JourneyOption(duration, steps));
            }
        }

        return options;
    }

    private StationInfo parseStationInfo(String stationResponse, String arrivalsResponse, String stationName) throws IOException {
        JsonNode stationNode = mapper.readTree(stationResponse);
        JsonNode arrivalsNode = mapper.readTree(arrivalsResponse);

        String name = stationNode.path("commonName").asText(stationName);
        List<String> facilities = new ArrayList<>();

        // Parse facilities and accessibility information
        JsonNode facilitiesNode = stationNode.get("additionalProperties");
        if (facilitiesNode != null && facilitiesNode.isArray()) {
            for (JsonNode facility : facilitiesNode) {
                String key = facility.path("key").asText();
                String value = facility.path("value").asText();
                if (key.toLowerCase().contains("accessibility") || 
                    key.toLowerCase().contains("facility") ||
                    key.toLowerCase().contains("toilet") ||
                    key.toLowerCase().contains("lift")) {
                    facilities.add(value);
                }
            }
        }

        // Parse live arrivals
        List<String> arrivals = new ArrayList<>();
        if (arrivalsNode.isArray()) {
            // Sort arrivals by time to station
            List<JsonNode> arrivalsList = new ArrayList<>();
            arrivalsNode.forEach(arrivalsList::add);
            arrivalsList.sort((a, b) -> 
                Integer.compare(a.path("timeToStation").asInt(), b.path("timeToStation").asInt()));
            
            for (int i = 0; i < Math.min(5, arrivalsList.size()); i++) {
                JsonNode arrival = arrivalsList.get(i);
                String lineName = arrival.path("lineName").asText();
                String towards = arrival.path("towards").asText();
                String destinationName = arrival.path("destinationName").asText();
                int timeToStation = arrival.path("timeToStation").asInt();

                String destination = !towards.isEmpty() ? towards : destinationName;
                String arrivalText = lineName + " to " + destination + " - ";
                
                if (timeToStation < 30) {
                    arrivalText += "Due";
                } else if (timeToStation < 60) {
                    arrivalText += "< 1 min";
                } else {
                    arrivalText += (timeToStation / 60) + " min";
                }
                arrivals.add(arrivalText);
            }
        }

        return new StationInfo(name, facilities, arrivals);
    }

    public static class JourneyOption {
        public final int durationMinutes;
        public final List<String> steps;

        public JourneyOption(int durationMinutes, List<String> steps) {
            this.durationMinutes = durationMinutes;
            this.steps = steps;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append("⏱️ Duration: ").append(durationMinutes).append(" minutes\n");
            for (int i = 0; i < steps.size(); i++) {
                sb.append("  ").append(i + 1).append(". ").append(steps.get(i)).append("\n");
            }
            return sb.toString();
        }
    }

    public static class StationInfo {
        public final String name;
        public final List<String> facilities;
        public final List<String> liveArrivals;

        public StationInfo(String name, List<String> facilities, List<String> liveArrivals) {
            this.name = name;
            this.facilities = facilities;
            this.liveArrivals = liveArrivals;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            
            if (!liveArrivals.isEmpty()) {
                sb.append("🚇 *Live Arrivals:*\n");
                for (String arrival : liveArrivals) {
                    sb.append("• ").append(arrival).append("\n");
                }
                sb.append("\n");
            } else {
                sb.append("🚇 *Live Arrivals:* No current arrivals\n\n");
            }

            if (!facilities.isEmpty()) {
                sb.append("🏢 *Facilities:*\n");
                for (String facility : facilities) {
                    sb.append("• ").append(facility).append("\n");
                }
            } else {
                sb.append("🏢 *Facilities:* Information not available");
            }

            return sb.toString();
        }
    }

    public static class ServiceUpdate {
        public final String lineName;
        public final String description;
        public final String details;

        public ServiceUpdate(String lineName, String description, String details) {
            this.lineName = lineName;
            this.description = description;
            this.details = details;
        }

        @Override
        public String toString() {
            return "🔧 *" + lineName + "*: " + description +
                    (details.isEmpty() ? "" : "\n   " + details);
        }
    }
}
