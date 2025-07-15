package bot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

    // Get all line statuses - FIXED: Correct case sensitivity
    public String getAllLineStatuses() throws IOException {
        String endpoint = API_BASE_URL + "/Line/Mode/tube/Status";
        return getCachedResponse(endpoint);
    }

    // Get status of a specific line - FIXED: Correct case sensitivity
    public String getLineStatus(String lineId) throws IOException {
        if (lineId == null || lineId.trim().isEmpty()) {
            throw new IllegalArgumentException("Line ID cannot be null or empty");
        }
        
        if (!isValidLineId(lineId)) {
            throw new IllegalArgumentException("Invalid line ID: " + lineId);
        }
        
        String endpoint = API_BASE_URL + "/Line/" + lineId.toLowerCase().replace(" ", "-") + "/Status";
        return getCachedResponse(endpoint);
    }

    private boolean isValidLineId(String lineId) {
        Set<String> validLines = Set.of(
            "bakerloo", "central", "circle", "district", "hammersmith-city",
            "jubilee", "metropolitan", "northern", "piccadilly", "victoria",
            "waterloo-city", "elizabeth", "london-overground", "dlr"
        );
        return validLines.contains(lineId.toLowerCase());
    }

    public List<String> getAllLineNames() throws IOException {
        String endpoint = API_BASE_URL + "/Line/Mode/tube";
        String response = getCachedResponse(endpoint);
        return parseLineNames(response);
    }

    public List<DisruptionInfo> getDisruptions() throws IOException {
        String endpoint = API_BASE_URL + "/Line/Mode/tube/Status";
        String response = getCachedResponse(endpoint);
        return parseDisruptions(response);
    }

    public List<DisruptionInfo> getSevereDisruptions() throws IOException {
        return getDisruptions().stream()
                .filter(disruption -> isSevereDisruption(disruption.statusSeverity))
                .toList();
    }

    public List<ServiceUpdate> getServiceUpdates() throws IOException {
        String endpoint = API_BASE_URL + "/Line/Mode/tube/Status";
        String response = getCachedResponse(endpoint);
        return parseServiceUpdates(response);
    }

    // FIXED: Station info method with correct endpoint format
    public StationInfo getStationInfo(String stationName) throws IOException {
        String encodedStationName = URLEncoder.encode(stationName.trim(), StandardCharsets.UTF_8);
        
        // FIXED: Correct endpoint format with proper case and query parameter
        String searchEndpoint = API_BASE_URL + "/StopPoint/Search?query=" + encodedStationName + "&modes=tube";
        
        logger.debug("Searching for station with endpoint: {}", searchEndpoint);
        
        String searchResponse = getCachedResponse(searchEndpoint);
        String stationId = parseStationId(searchResponse);
        
        if (stationId == null || stationId.isEmpty()) {
            throw new IllegalArgumentException("Station not found: " + stationName);
        }

        logger.debug("Found station ID: {} for station: {}", stationId, stationName);

        // Get station details using the station ID
        String stationEndpoint = API_BASE_URL + "/StopPoint/" + stationId;
        String stationResponse = getCachedResponse(stationEndpoint);

        // Get live arrivals using the station ID
        String arrivalsEndpoint = API_BASE_URL + "/StopPoint/" + stationId + "/Arrivals";
        String arrivalsResponse = getCachedResponse(arrivalsEndpoint);

        return parseStationInfo(stationResponse, arrivalsResponse, stationName);
    }

    // FIXED: Journey planning method with correct endpoint format
    public List<JourneyOption> planJourney(String fromStation, String toStation) throws IOException {
        String encodedFrom = URLEncoder.encode(fromStation.trim(), StandardCharsets.UTF_8);
        String encodedTo = URLEncoder.encode(toStation.trim(), StandardCharsets.UTF_8);
        
        // FIXED: Correct endpoint format with proper case
        String endpoint = API_BASE_URL + "/Journey/JourneyResults/" + encodedFrom + "/to/" + encodedTo;
        String response = getCachedResponse(endpoint);
        return parseJourneyOptions(response);
    }

    public String getJourneyPlan(String fromStation, String toStation) throws IOException {
        try {
            List<JourneyOption> options = planJourney(fromStation, toStation);
            
            if (options.isEmpty()) {
                return "❌ No journey options found. Please check station names.\n\n" +
                       "💡 *Tips:*\n" +
                       "• Try full station names (e.g., 'King's Cross St. Pancras')\n" +
                       "• Check spelling of station names\n" +
                       "• Some stations have multiple names";
            }
            
            StringBuilder result = new StringBuilder();
            result.append("🗺️ *Journey Options:*\n\n");
            
            for (int i = 0; i < Math.min(3, options.size()); i++) {
                result.append("**Option ").append(i + 1).append(":**\n");
                result.append(options.get(i).toString()).append("\n");
            }
            
            return result.toString();
            
        } catch (IOException e) {
            logger.error("Journey planning failed for {} to {}: {}", fromStation, toStation, e.getMessage());
            
            // Provide helpful error message based on the type of error
            if (e.getMessage().contains("404")) {
                throw new IllegalArgumentException("One or both stations not found. Please check station names and try again.");
            } else if (e.getMessage().contains("429")) {
                throw new IOException("Service temporarily busy. Please try again in a moment.");
            } else {
                throw new IOException("Journey planning service unavailable. Please try again later.");
            }
        }
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
                logger.error("Error on attempt {} for endpoint: {} - Status: {}", attempt, endpoint, e.getMessage());
                if (attempt == MAX_RETRIES) break;
            }
        }

        throw new IOException("Failed after " + MAX_RETRIES + " attempts for endpoint: " + endpoint, lastException);
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
            
            // Enhanced error handling with specific status codes
            if (responseCode == 404) {
                throw new IOException("TFL API endpoint not found (404). Check endpoint format: " + endpoint);
            } else if (responseCode == 429) {
                throw new IOException("TFL API rate limit exceeded (429). Please try again later.");
            } else if (responseCode == 403) {
                throw new IOException("TFL API access forbidden (403). Rate limiting or authentication issue.");
            } else if (responseCode != 200) {
                throw new IOException("TFL API returned status code: " + responseCode + " for endpoint: " + endpoint);
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
                        String description = status.path("statusSeverityDescription").asText();
                        String reason = status.path("reason").asText("");
                        String disruption = status.path("disruption").path("description").asText("");

                        // Only include updates that have meaningful information
                        if (!reason.isEmpty() || !disruption.isEmpty() || !"Good Service".equals(description)) {
                            String details = !reason.isEmpty() ? reason : disruption;
                            updates.add(new ServiceUpdate(lineName, description, details));
                        }
                    }
                }
            }
        }

        return updates;
    }

    private boolean isSevereDisruption(int severity) {
        return severity <= 6; // Severe delays, part closure, etc.
    }

    // FIXED: Proper station ID parsing for the corrected search endpoint
    private String parseStationId(String jsonResponse) throws IOException {
        JsonNode rootNode = mapper.readTree(jsonResponse);
        
        // Handle search results from StopPoint/Search endpoint
        if (rootNode.has("matches")) {
            JsonNode matchesNode = rootNode.get("matches");
            if (matchesNode.isArray() && matchesNode.size() > 0) {
                List<JsonNode> matches = new ArrayList<>();
                matchesNode.forEach(matches::add);
                
                // Prefer underground stations (IDs starting with 940GZZLU)
                for (JsonNode match : matches) {
                    String id = match.path("id").asText();
                    String name = match.path("name").asText();
                    JsonNode modesNode = match.get("modes");
                    
                    // Check if this is a tube station
                    if (modesNode != null && modesNode.isArray()) {
                        for (JsonNode mode : modesNode) {
                            if ("tube".equals(mode.asText())) {
                                logger.debug("Found tube station: {} with ID: {}", name, id);
                                return id;
                            }
                        }
                    }
                }
                
                // Fallback to first match if no tube station found
                JsonNode firstMatch = matches.get(0);
                String id = firstMatch.path("id").asText();
                String name = firstMatch.path("name").asText();
                logger.debug("Using first match: {} with ID: {}", name, id);
                return id;
            }
        }
        
        // Handle direct station data (when searching by ID)
        if (rootNode.has("id")) {
            return rootNode.path("id").asText();
        }
        
        logger.warn("No station ID found in response: {}", jsonResponse.substring(0, Math.min(200, jsonResponse.length())));
        return null;
    }

    private List<JourneyOption> parseJourneyOptions(String jsonResponse) throws IOException {
        List<JourneyOption> options = new ArrayList<>();
        JsonNode rootNode = mapper.readTree(jsonResponse);
        
        // Handle error responses
        if (rootNode.has("httpStatusCode")) {
            int statusCode = rootNode.path("httpStatusCode").asInt();
            String message = rootNode.path("message").asText();
            logger.warn("TFL API error response: {} - {}", statusCode, message);
            return options; // Return empty list for error responses
        }
        
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
                        
                        if ("walking".equalsIgnoreCase(mode)) {
                            stepText.append("🚶 Walk");
                        } else if ("tube".equalsIgnoreCase(mode)) {
                            stepText.append("🚇 Tube");
                        } else if ("bus".equalsIgnoreCase(mode)) {
                            stepText.append("🚌 Bus");
                        } else {
                            stepText.append("🚊 ").append(mode);
                        }
                        
                        if (!departurePoint.isEmpty() && !arrivalPoint.isEmpty()) {
                            stepText.append(" from ").append(departurePoint).append(" to ").append(arrivalPoint);
                        } else if (!instruction.isEmpty() && !instruction.equals("Continue")) {
                            stepText.append(": ").append(instruction);
                        }
                        
                        // Add line information for tube journeys
                        JsonNode routeOptionsNode = leg.get("routeOptions");
                        if (routeOptionsNode != null && routeOptionsNode.isArray() && routeOptionsNode.size() > 0) {
                            JsonNode routeOption = routeOptionsNode.get(0);
                            String lineName = routeOption.path("name").asText("");
                            if (!lineName.isEmpty() && "tube".equalsIgnoreCase(mode)) {
                                stepText.append(" (").append(lineName).append(" line)");
                            }
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
                    key.toLowerCase().contains("lift") ||
                    key.toLowerCase().contains("step")) {
                    facilities.add(key + ": " + value);
                }
            }
        }

        // Parse live arrivals
        List<String> arrivals = new ArrayList<>();
        if (arrivalsNode.isArray() && arrivalsNode.size() > 0) {
            // Sort arrivals by time to station
            List<JsonNode> arrivalsList = new ArrayList<>();
            arrivalsNode.forEach(arrivalsList::add);
            arrivalsList.sort((a, b) -> 
                Integer.compare(a.path("timeToStation").asInt(), b.path("timeToStation").asInt()));
            
            for (int i = 0; i < Math.min(6, arrivalsList.size()); i++) {
                JsonNode arrival = arrivalsList.get(i);
                String lineName = arrival.path("lineName").asText();
                String towards = arrival.path("towards").asText();
                String destinationName = arrival.path("destinationName").asText();
                String platformName = arrival.path("platformName").asText();
                int timeToStation = arrival.path("timeToStation").asInt();

                String destination = !towards.isEmpty() ? towards : destinationName;
                StringBuilder arrivalText = new StringBuilder();
                
                // Add line name with emoji
                if (lineName.toLowerCase().contains("central")) {
                    arrivalText.append("🔴 ");
                } else if (lineName.toLowerCase().contains("northern")) {
                    arrivalText.append("⚫ ");
                } else if (lineName.toLowerCase().contains("piccadilly")) {
                    arrivalText.append("🔵 ");
                } else {
                    arrivalText.append("🚇 ");
                }
                
                arrivalText.append(lineName);
                if (!destination.isEmpty()) {
                    arrivalText.append(" to ").append(destination);
                }
                
                // Add platform information if available
                if (!platformName.isEmpty() && !platformName.equals("null")) {
                    arrivalText.append(" (").append(platformName).append(")");
                }
                
                arrivalText.append(" - ");
                
                if (timeToStation < 30) {
                    arrivalText.append("Due");
                } else if (timeToStation < 60) {
                    arrivalText.append("< 1 min");
                } else {
                    arrivalText.append((timeToStation / 60)).append(" min");
                }
                
                arrivals.add(arrivalText.toString());
            }
        }

        return new StationInfo(name, facilities, arrivals);
    }

    // Data classes
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

        public String getId() {
            return lineName + "|" + statusSeverity + "|" + description.hashCode();
        }

        @Override
        public String toString() {
            return "🚨 *" + lineName + "*: " + description +
                    (reason.isEmpty() ? "" : "\n   " + reason);
        }
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