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
    private static final String APP_KEY = "***REDACTED***";
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

    // FIXED: Correct endpoint with lowercase and app_key
    public String getAllLineStatuses() throws IOException {
        String endpoint = API_BASE_URL + "/line/mode/tube/status?app_key=" + APP_KEY;
        return getCachedResponse(endpoint);
    }

    // FIXED: Correct endpoint with lowercase and app_key
    public String getLineStatus(String lineId) throws IOException {
        if (lineId == null || lineId.trim().isEmpty()) {
            throw new IllegalArgumentException("Line ID cannot be null or empty");
        }
        
        if (!isValidLineId(lineId)) {
            throw new IllegalArgumentException("Invalid line ID: " + lineId);
        }
        
        String endpoint = API_BASE_URL + "/line/" + lineId.toLowerCase().replace(" ", "-") + "/status?app_key=" + APP_KEY;
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
        String endpoint = API_BASE_URL + "/line/mode/tube?app_key=" + APP_KEY;
        String response = getCachedResponse(endpoint);
        return parseLineNames(response);
    }

    public List<DisruptionInfo> getDisruptions() throws IOException {
        String endpoint = API_BASE_URL + "/line/mode/tube/status?app_key=" + APP_KEY;
        String response = getCachedResponse(endpoint);
        return parseDisruptions(response);
    }

    public List<DisruptionInfo> getSevereDisruptions() throws IOException {
        return getDisruptions().stream()
                .filter(disruption -> isSevereDisruption(disruption.statusSeverity))
                .toList();
    }

    public List<ServiceUpdate> getServiceUpdates() throws IOException {
        String endpoint = API_BASE_URL + "/line/mode/tube/status?app_key=" + APP_KEY;
        String response = getCachedResponse(endpoint);
        return parseServiceUpdates(response);
    }

    // FIXED: Correct endpoint format with proper case and app_key
    public StationInfo getStationInfo(String stationName) throws IOException {
        String encodedStationName = URLEncoder.encode(stationName.trim(), StandardCharsets.UTF_8);
        
        // FIXED: Correct endpoint format - note the capital 'S' in StopPoint
        String searchEndpoint = API_BASE_URL + "/StopPoint/Search?query=" + encodedStationName + "&modes=tube&app_key=" + APP_KEY;
        
        logger.debug("Searching for station with endpoint: {}", searchEndpoint);
        
        String searchResponse = getCachedResponse(searchEndpoint);
        String stationId = parseStationId(searchResponse);
        
        if (stationId == null || stationId.isEmpty()) {
            throw new IllegalArgumentException("Station not found: " + stationName);
        }

        logger.debug("Found station ID: {} for station: {}", stationId, stationName);

        // Get station details using the station ID
        String stationEndpoint = API_BASE_URL + "/StopPoint/" + stationId + "?app_key=" + APP_KEY;
        String stationResponse = getCachedResponse(stationEndpoint);

        // Get live arrivals using the station ID
        String arrivalsEndpoint = API_BASE_URL + "/StopPoint/" + stationId + "/arrivals?app_key=" + APP_KEY;
        String arrivalsResponse = getCachedResponse(arrivalsEndpoint);

        return parseStationInfo(stationResponse, arrivalsResponse, stationName);
    }

    // FIXED: Complete Journey planning methods without app_key and comprehensive station mappings

    public List<JourneyOption> planJourney(String fromStation, String toStation) throws IOException {
        // Clean and normalize station names
        String cleanFrom = normalizeStationName(fromStation.trim());
        String cleanTo = normalizeStationName(toStation.trim());
        
        // Properly encode the station names for URL
        String encodedFrom = URLEncoder.encode(cleanFrom, StandardCharsets.UTF_8)
                .replace("+", "%20")  // Replace spaces with %20 instead of +
                .replace(".", ""); // Remove periods
                
        String encodedTo = URLEncoder.encode(cleanTo, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace(".", "");
        
        // Add specific journey planner parameters to reduce ambiguity
        String endpoint = String.format("%s/Journey/JourneyResults/%s/to/%s?mode=tube&alternativeWalking=false&app_key=%s",
                API_BASE_URL,
                encodedFrom,
                encodedTo,
                APP_KEY);
        
        logger.debug("Journey planning endpoint: {}", endpoint);
        
        try {
            String response = getCachedResponse(endpoint);
            return parseJourneyOptions(response);
        } catch (IOException e) {
            // If we get a 300 status, try with ICS codes
            if (e.getMessage().contains("300")) {
                logger.info("Attempting to resolve station codes for {} to {}", fromStation, toStation);
                return tryJourneyWithStationCodes(fromStation, toStation);
            }
            throw e;
        }
    }

    private List<JourneyOption> tryJourneyWithStationCodes(String fromStation, String toStation) throws IOException {
        // Try to get station IDs first
        String fromId = getStationId(fromStation);
        String toId = getStationId(toStation);
        
        if (fromId == null || toId == null) {
            return new ArrayList<>(); // Return empty list if we can't find station IDs
        }
        
        // Use station IDs in the journey planning endpoint
        String endpoint = String.format("%s/Journey/JourneyResults/%s/to/%s?mode=tube&alternativeWalking=false&app_key=%s",
                API_BASE_URL,
                fromId,
                toId,
                APP_KEY);
                
        String response = getCachedResponse(endpoint);
        return parseJourneyOptions(response);
    }

    private String getStationId(String stationName) throws IOException {
        String searchEndpoint = API_BASE_URL + "/StopPoint/Search?query=" + 
            URLEncoder.encode(stationName, StandardCharsets.UTF_8) + 
            "&modes=tube&app_key=" + APP_KEY;
            
        String response = getCachedResponse(searchEndpoint);
        return parseStationId(response);
    }

    public String getJourneyPlan(String fromStation, String toStation) throws IOException {
        try {
            List<JourneyOption> options = planJourney(fromStation, toStation);
            
            if (options.isEmpty()) {
                return "❌ No journey options found. Please check station names.\n\n" +
                    "💡 *Tips:*\n" +
                    "• Try simplified station names (e.g., 'Kings Cross' instead of 'King's Cross St. Pancras')\n" +
                    "• Check spelling of station names\n" +
                    "• Some stations have multiple names - try alternatives";
            }
            
            StringBuilder result = new StringBuilder();
            result.append("🗺️ *Journey Options:*\n\n");
            
            for (int i = 0; i < Math.min(3, options.size()); i++) {
                result.append("**Option ").append(i + 1).append(":**\n");
                result.append(options.get(i).toString()).append("\n");
            }
            
            return result.toString();
            
        } catch (IllegalArgumentException e) {
            // Handle disambiguation by trying simplified names
            if (e.getMessage().contains("ambiguous") || e.getMessage().contains("404")) {
                logger.info("Attempting to resolve with simplified names for {} to {}", fromStation, toStation);
                return handleSimplifiedJourney(fromStation, toStation);
            }
            throw e;
        } catch (IOException e) {
            logger.error("Journey planning failed for {} to {}: {}", fromStation, toStation, e.getMessage());
            
            // Provide helpful error message based on the type of error
            if (e.getMessage().contains("404")) {
                return handleSimplifiedJourney(fromStation, toStation);
            } else if (e.getMessage().contains("429")) {
                throw new IOException("Service temporarily busy. Please try again in a moment.");
            } else {
                throw new IOException("Journey planning service unavailable. Please try again later.");
            }
        }
    }

    private String normalizeStationName(String stationName) {
        if (stationName == null || stationName.isEmpty()) {
            return stationName;
        }
        
        // Remove extra spaces and normalize
        String normalized = stationName.trim().replaceAll("\\s+", " ");
        
        // Comprehensive station name mappings - covers all major London stations
        Map<String, String> stationMappings = new HashMap<>();
        // Major interchange stations
        stationMappings.put("King's Cross St. Pancras", "Kings Cross");
        stationMappings.put("King's Cross", "Kings Cross");
        stationMappings.put("St. Pancras", "Kings Cross");
        stationMappings.put("Liverpool Street", "Liverpool St");
        stationMappings.put("London Bridge", "London Bridge");
        stationMappings.put("Waterloo", "Waterloo");
        stationMappings.put("Victoria", "Victoria");
        stationMappings.put("Paddington", "Paddington");
        stationMappings.put("Euston", "Euston");
        stationMappings.put("Marylebone", "Marylebone");
        stationMappings.put("Charing Cross", "Charing Cross");
        // Central London stations
        stationMappings.put("Oxford Circus", "Oxford Circus");
        stationMappings.put("Bond Street", "Bond St");
        stationMappings.put("Tottenham Court Road", "Tottenham Court Rd");
        stationMappings.put("Leicester Square", "Leicester Sq");
        stationMappings.put("Covent Garden", "Covent Garden");
        stationMappings.put("Holborn", "Holborn");
        stationMappings.put("Russell Square", "Russell Sq");
        stationMappings.put("Goodge Street", "Goodge St");
        stationMappings.put("Warren Street", "Warren St");
        stationMappings.put("Great Portland Street", "Great Portland St");
        stationMappings.put("Baker Street", "Baker St");
        stationMappings.put("Regent's Park", "Regents Park");
        stationMappings.put("Camden Town", "Camden Town");
        stationMappings.put("Mornington Crescent", "Mornington Crescent");
        // City and East
        stationMappings.put("Bank", "Bank");
        stationMappings.put("Monument", "Monument");
        stationMappings.put("Bank-Monument", "Bank");
        stationMappings.put("Moorgate", "Moorgate");
        stationMappings.put("Old Street", "Old St");
        stationMappings.put("Angel", "Angel");
        stationMappings.put("Barbican", "Barbican");
        stationMappings.put("Farringdon", "Farringdon");
        stationMappings.put("Chancery Lane", "Chancery Lane");
        stationMappings.put("St. Paul's", "St Pauls");
        stationMappings.put("Mansion House", "Mansion House");
        stationMappings.put("Cannon Street", "Cannon St");
        stationMappings.put("Tower Hill", "Tower Hill");
        stationMappings.put("Tower Gateway", "Tower Gateway");
        stationMappings.put("Aldgate", "Aldgate");
        stationMappings.put("Aldgate East", "Aldgate East");
        stationMappings.put("Whitechapel", "Whitechapel");
        stationMappings.put("Bethnal Green", "Bethnal Green");
        stationMappings.put("Mile End", "Mile End");
        stationMappings.put("Stratford", "Stratford");
        stationMappings.put("Canary Wharf", "Canary Wharf");
        // West London
        stationMappings.put("Notting Hill Gate", "Notting Hill Gate");
        stationMappings.put("Bayswater", "Bayswater");
        stationMappings.put("Marble Arch", "Marble Arch");
        stationMappings.put("Hyde Park Corner", "Hyde Park Corner");
        stationMappings.put("Green Park", "Green Park");
        stationMappings.put("Piccadilly Circus", "Piccadilly Circus");
        stationMappings.put("Knightsbridge", "Knightsbridge");
        stationMappings.put("South Kensington", "South Kensington");
        stationMappings.put("Gloucester Road", "Gloucester Rd");
        stationMappings.put("Earl's Court", "Earls Court");
        stationMappings.put("Hammersmith", "Hammersmith");
        stationMappings.put("Shepherd's Bush", "Shepherds Bush");
        stationMappings.put("White City", "White City");
        stationMappings.put("Wood Lane", "Wood Lane");
        stationMappings.put("Westfield", "Shepherds Bush");
        // North London
        stationMappings.put("Finsbury Park", "Finsbury Park");
        stationMappings.put("Arsenal", "Arsenal");
        stationMappings.put("Holloway Road", "Holloway Rd");
        stationMappings.put("Caledonian Road", "Caledonian Rd");
        stationMappings.put("Kentish Town", "Kentish Town");
        stationMappings.put("Chalk Farm", "Chalk Farm");
        stationMappings.put("Belsize Park", "Belsize Park");
        stationMappings.put("Hampstead", "Hampstead");
        stationMappings.put("Golders Green", "Golders Green");
        stationMappings.put("Brent Cross", "Brent Cross");
        stationMappings.put("Hendon Central", "Hendon Central");
        stationMappings.put("Edgware", "Edgware");
        stationMappings.put("High Barnet", "High Barnet");
        stationMappings.put("Cockfosters", "Cockfosters");
        stationMappings.put("Arnos Grove", "Arnos Grove");
        stationMappings.put("Bounds Green", "Bounds Green");
        stationMappings.put("Wood Green", "Wood Green");
        stationMappings.put("Turnpike Lane", "Turnpike Lane");
        stationMappings.put("Manor House", "Manor House");
        // South London
        stationMappings.put("Elephant & Castle", "Elephant Castle");
        stationMappings.put("Elephant and Castle", "Elephant Castle");
        stationMappings.put("Borough", "Borough");
        stationMappings.put("London Bridge", "London Bridge");
        stationMappings.put("Bermondsey", "Bermondsey");
        stationMappings.put("Canada Water", "Canada Water");
        stationMappings.put("Surrey Quays", "Surrey Quays");
        stationMappings.put("New Cross", "New Cross");
        stationMappings.put("New Cross Gate", "New Cross Gate");
        stationMappings.put("Deptford Bridge", "Deptford Bridge");
        stationMappings.put("Greenwich", "Greenwich");
        stationMappings.put("Cutty Sark", "Cutty Sark");
        stationMappings.put("Island Gardens", "Island Gardens");
        stationMappings.put("Mudchute", "Mudchute");
        stationMappings.put("South Quay", "South Quay");
        stationMappings.put("Crossharbour", "Crossharbour");
        stationMappings.put("Heron Quays", "Heron Quays");
        stationMappings.put("West India Quay", "West India Quay");
        // Outer London - North East
        stationMappings.put("Buckhurst Hill", "Buckhursthill");
        stationMappings.put("Loughton", "Loughton");
        stationMappings.put("Debden", "Debden");
        stationMappings.put("Theydon Bois", "Theydon Bois");
        stationMappings.put("Epping", "Epping");
        stationMappings.put("Woodford", "Woodford");
        stationMappings.put("South Woodford", "South Woodford");
        stationMappings.put("Snaresbrook", "Snaresbrook");
        stationMappings.put("Leytonstone", "Leytonstone");
        stationMappings.put("Leyton", "Leyton");
        stationMappings.put("Walthamstow Central", "Walthamstow Central");
        stationMappings.put("Blackhorse Road", "Blackhorse Rd");
        stationMappings.put("Tottenham Hale", "Tottenham Hale");
        stationMappings.put("Seven Sisters", "Seven Sisters");
        // Outer London - West
        stationMappings.put("Heathrow Terminal 1", "Heathrow Terminal 1");
        stationMappings.put("Heathrow Terminal 2", "Heathrow Terminal 2");
        stationMappings.put("Heathrow Terminal 3", "Heathrow Terminal 3");
        stationMappings.put("Heathrow Terminal 4", "Heathrow Terminal 4");
        stationMappings.put("Heathrow Terminal 5", "Heathrow Terminal 5");
        stationMappings.put("Hatton Cross", "Hatton Cross");
        stationMappings.put("Hounslow East", "Hounslow East");
        stationMappings.put("Hounslow Central", "Hounslow Central");
        stationMappings.put("Hounslow West", "Hounslow West");
        stationMappings.put("Osterley", "Osterley");
        stationMappings.put("Boston Manor", "Boston Manor");
        stationMappings.put("Northfields", "Northfields");
        stationMappings.put("South Ealing", "South Ealing");
        stationMappings.put("Ealing Common", "Ealing Common");
        stationMappings.put("Ealing Broadway", "Ealing Broadway");
        stationMappings.put("West Ealing", "West Ealing");
        stationMappings.put("Hanwell", "Hanwell");
        stationMappings.put("Southall", "Southall");
        stationMappings.put("Hayes & Harlington", "Hayes Harlington");
        // Outer London - South
        stationMappings.put("Morden", "Morden");
        stationMappings.put("South Wimbledon", "South Wimbledon");
        stationMappings.put("Colliers Wood", "Colliers Wood");
        stationMappings.put("Tooting Broadway", "Tooting Broadway");
        stationMappings.put("Tooting Bec", "Tooting Bec");
        stationMappings.put("Balham", "Balham");
        stationMappings.put("Clapham South", "Clapham South");
        stationMappings.put("Clapham Common", "Clapham Common");
        stationMappings.put("Clapham North", "Clapham North");
        stationMappings.put("Stockwell", "Stockwell");
        stationMappings.put("Oval", "Oval");
        stationMappings.put("Kennington", "Kennington");
        stationMappings.put("Lambeth North", "Lambeth North");
        stationMappings.put("Southwark", "Southwark");
        stationMappings.put("Waterloo East", "Waterloo East");
        // Metropolitan Line extensions
        stationMappings.put("Amersham", "Amersham");
        stationMappings.put("Chalfont & Latimer", "Chalfont Latimer");
        stationMappings.put("Chorleywood", "Chorleywood");
        stationMappings.put("Rickmansworth", "Rickmansworth");
        stationMappings.put("Moor Park", "Moor Park");
        stationMappings.put("Northwood", "Northwood");
        stationMappings.put("Northwood Hills", "Northwood Hills");
        stationMappings.put("Pinner", "Pinner");
        stationMappings.put("North Harrow", "North Harrow");
        stationMappings.put("Harrow-on-the-Hill", "Harrow-on-the-Hill");
        stationMappings.put("West Harrow", "West Harrow");
        stationMappings.put("Rayners Lane", "Rayners Lane");
        stationMappings.put("Eastcote", "Eastcote");
        stationMappings.put("Ruislip Manor", "Ruislip Manor");
        stationMappings.put("Ruislip", "Ruislip");
        stationMappings.put("Ickenham", "Ickenham");
        stationMappings.put("Hillingdon", "Hillingdon");
        stationMappings.put("Uxbridge", "Uxbridge");
        // District Line extensions
        stationMappings.put("Upminster", "Upminster");
        stationMappings.put("Upminster Bridge", "Upminster Bridge");
        stationMappings.put("Hornchurch", "Hornchurch");
        stationMappings.put("Elm Park", "Elm Park");
        stationMappings.put("Dagenham Heathway", "Dagenham Heathway");
        stationMappings.put("Dagenham East", "Dagenham East");
        stationMappings.put("Becontree", "Becontree");
        stationMappings.put("Upney", "Upney");
        stationMappings.put("Barking", "Barking");
        stationMappings.put("East Ham", "East Ham");
        stationMappings.put("Upton Park", "Upton Park");
        stationMappings.put("Plaistow", "Plaistow");
        stationMappings.put("West Ham", "West Ham");
        stationMappings.put("Bromley-by-Bow", "Bromley-by-Bow");
        stationMappings.put("Bow Road", "Bow Rd");
        stationMappings.put("Bow Church", "Bow Church");
        // Elizabeth Line (Crossrail)
        stationMappings.put("Reading", "Reading");
        stationMappings.put("Slough", "Slough");
        stationMappings.put("Langley", "Langley");
        stationMappings.put("Iver", "Iver");
        stationMappings.put("West Drayton", "West Drayton");
        stationMappings.put("Hayes & Harlington", "Hayes Harlington");
        stationMappings.put("Southall", "Southall");
        stationMappings.put("Hanwell", "Hanwell");
        stationMappings.put("West Ealing", "West Ealing");
        stationMappings.put("Ealing Broadway", "Ealing Broadway");
        stationMappings.put("Acton Main Line", "Acton Main Line");
        stationMappings.put("Bond Street", "Bond St");
        stationMappings.put("Tottenham Court Road", "Tottenham Court Rd");
        stationMappings.put("Farringdon", "Farringdon");
        stationMappings.put("Liverpool Street", "Liverpool St");
        stationMappings.put("Whitechapel", "Whitechapel");
        stationMappings.put("Canary Wharf", "Canary Wharf");
        stationMappings.put("Woolwich", "Woolwich");
        stationMappings.put("Abbey Wood", "Abbey Wood");
        stationMappings.put("Shenfield", "Shenfield");
        stationMappings.put("Brentwood", "Brentwood");
        stationMappings.put("Harold Wood", "Harold Wood");
        stationMappings.put("Gidea Park", "Gidea Park");
        stationMappings.put("Romford", "Romford");
        stationMappings.put("Chadwell Heath", "Chadwell Heath");
        stationMappings.put("Goodmayes", "Goodmayes");
        stationMappings.put("Seven Kings", "Seven Kings");
        stationMappings.put("Ilford", "Ilford");
        stationMappings.put("Manor Park", "Manor Park");
        stationMappings.put("Forest Gate", "Forest Gate");
        stationMappings.put("Maryland", "Maryland");
        stationMappings.put("Stratford", "Stratford");
        // Common variations and abbreviations
        stationMappings.put("St.", "St");
        stationMappings.put("Rd.", "Rd");
        stationMappings.put("Ave.", "Ave");
        stationMappings.put("&", "");
        stationMappings.put(" and ", " ");
        
        // Check for exact matches first
        String mapped = stationMappings.get(normalized);
        if (mapped != null) {
            return mapped;
        }
        
        // Remove common suffixes that might cause issues
        normalized = normalized
            .replace(" Station", "")
            .replace(" Underground", "")
            .replace(" Tube", "")
            .replace("'", "")  // Remove apostrophes
            .replace(".", "")  // Remove periods
            .replace("-", "")  // Remove hyphens for URL compatibility
            .replaceAll("\\s+", ""); // Remove all spaces for API compatibility
        
        return normalized;
    }

    private String handleSimplifiedJourney(String fromStation, String toStation) throws IOException {
        // Try with heavily simplified station names
        String simplifiedFrom = simplifyStationName(fromStation);
        String simplifiedTo = simplifyStationName(toStation);
        
        logger.info("Trying simplified journey: {} to {}", simplifiedFrom, simplifiedTo);
        
        try {
            List<JourneyOption> options = planJourney(simplifiedFrom, simplifiedTo);
            if (!options.isEmpty()) {
                StringBuilder result = new StringBuilder();
                result.append("🗺️ *Journey Options:*\n");
                result.append("*(Using simplified station names)*\n\n");
                
                for (int i = 0; i < Math.min(3, options.size()); i++) {
                    result.append("**Option ").append(i + 1).append(":**\n");
                    result.append(options.get(i).toString()).append("\n");
                }
                
                return result.toString();
            }
        } catch (Exception e) {
            logger.debug("Simplified journey also failed: {} to {} - {}", simplifiedFrom, simplifiedTo, e.getMessage());
        }
        
        return "❌ Could not find a journey between these locations.\n\n" +
            "💡 *Try these alternatives:*\n" +
            "• Use simpler station names (e.g., 'Kings Cross', 'Liverpool St')\n" +
            "• Check spelling carefully\n" +
            "• Try nearby stations\n" +
            "• Some areas have multiple stations - try different ones\n\n" +
            "*Example formats that work well:*\n" +
            "• 'Kings Cross to Oxford Circus'\n" +
            "• 'Liverpool St to Canary Wharf'\n" +
            "• 'Buckhursthill to Bank'";
    }

    private String simplifyStationName(String stationName) {
        return stationName.toLowerCase()
                .replace("'s", "s")
                .replace("'", "")
                .replace(".", "")
                .replace("-", "")
                .replace(" st pancras", "")
                .replace(" st ", " ")
                .replace(" street", " st")
                .replace(" road", " rd")
                .replace(" square", " sq")
                .replace(" circus", " circus")
                .replace(" hill", "hill")
                .replace(" & ", " ")
                .replace(" and ", " ")
                .replaceAll("\\s+", "")
                .trim();
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

    // FIXED: Added proper User-Agent header
    private String getResponse(String endpoint) throws IOException {
        URL url = new URL(endpoint);
        HttpURLConnection conn = null;
        Scanner scanner = null;

        try {
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(CONNECTION_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            
            // CRITICAL: Add User-Agent header to prevent 403 errors
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; TFL-Bot/1.0)");
            conn.setRequestProperty("Accept", "application/json");

            int responseCode = conn.getResponseCode();
            
            // Enhanced error handling with specific status codes
            if (responseCode == 404) {
                throw new IOException("TFL API endpoint not found (404). Check endpoint format: " + endpoint);
            } else if (responseCode == 429) {
                throw new IOException("TFL API rate limit exceeded (429). Please try again later.");
            } else if (responseCode == 403) {
                throw new IOException("TFL API access forbidden (403). Check User-Agent header and API credentials.");
            } else if (responseCode != 200) {
                throw new IOException("TFL API returned status code: " + responseCode + " for endpoint: " + endpoint);
            }

            scanner = new Scanner(conn.getInputStream());
            StringBuilder response = new StringBuilder();

            while (scanner.hasNextLine()) {
                response.append(scanner.nextLine());
            }

            logger.debug("Successfully received response from: {}", endpoint);
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
        
        // Handle disambiguation responses (multiple location matches)
        if (rootNode.has("toLocationDisambiguation") || rootNode.has("fromLocationDisambiguation")) {
            logger.warn("Journey planning returned disambiguation response - locations need to be more specific");
            throw new IllegalArgumentException("Location names are ambiguous. Please use more specific station names or try alternative names.");
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

    // Data classes remain the same
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