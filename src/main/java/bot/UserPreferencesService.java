package bot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class UserPreferencesService {
    private static final Logger logger = LoggerFactory.getLogger(UserPreferencesService.class);
    private final DatabaseManager dbManager;
    
    public UserPreferencesService() {
        this.dbManager = DatabaseManager.getInstance();
    }
    
    public static class UserPreferences {
        private final Set<String> favoriteLines;
        private final Map<LocalTime, NotificationSettings> scheduledNotifications;
        private boolean disruptionAlertsEnabled;
        private boolean weekendNotifications;
        
        public UserPreferences(Set<String> favoriteLines, Map<LocalTime, NotificationSettings> scheduledNotifications,
                             boolean disruptionAlertsEnabled, boolean weekendNotifications) {
            this.favoriteLines = favoriteLines;
            this.scheduledNotifications = scheduledNotifications;
            this.disruptionAlertsEnabled = disruptionAlertsEnabled;
            this.weekendNotifications = weekendNotifications;
        }
        
        public Set<String> getFavoriteLines() { return new HashSet<>(favoriteLines); }
        public Map<LocalTime, NotificationSettings> getScheduledNotifications() { return new HashMap<>(scheduledNotifications); }
        public boolean isDisruptionAlertsEnabled() { return disruptionAlertsEnabled; }
        public boolean isWeekendNotifications() { return weekendNotifications; }
    }
    
    public static class NotificationSettings {
        private final Set<String> lines;
        private final boolean onlyDisruptions;
        
        public NotificationSettings(Set<String> lines, boolean onlyDisruptions) {
            this.lines = new HashSet<>(lines);
            this.onlyDisruptions = onlyDisruptions;
        }
        
        public Set<String> getLines() { return new HashSet<>(lines); }
        public boolean isOnlyDisruptions() { return onlyDisruptions; }
    }
    
    public UserPreferences getUserPreferences(Long userId) {
        try (Connection conn = dbManager.getConnection()) {
            // Ensure user exists
            ensureUserExists(conn, userId);
            
            // Get user settings
            boolean disruptionAlerts = true;
            boolean weekendNotifications = false;
            
            String userQuery = "SELECT disruption_alerts_enabled, weekend_notifications FROM users WHERE user_id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(userQuery)) {
                stmt.setLong(1, userId);
                try (ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        disruptionAlerts = rs.getBoolean("disruption_alerts_enabled");
                        weekendNotifications = rs.getBoolean("weekend_notifications");
                    }
                }
            }
            
            // Get favorite lines
            Set<String> favoriteLines = new HashSet<>();
            String favoritesQuery = "SELECT line_name FROM favorite_lines WHERE user_id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(favoritesQuery)) {
                stmt.setLong(1, userId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        favoriteLines.add(rs.getString("line_name"));
                    }
                }
            }
            
            // Get scheduled notifications
            Map<LocalTime, NotificationSettings> scheduledNotifications = new HashMap<>();
            String notificationsQuery = "SELECT sn.notification_time, sn.only_disruptions, sn.id, " +
                    "ARRAY_AGG(nl.line_name) as lines " +
                    "FROM scheduled_notifications sn " +
                    "LEFT JOIN notification_lines nl ON sn.id = nl.notification_id " +
                    "WHERE sn.user_id = ? " +
                    "GROUP BY sn.id, sn.notification_time, sn.only_disruptions";
            
            try (PreparedStatement stmt = conn.prepareStatement(notificationsQuery)) {
                stmt.setLong(1, userId);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        LocalTime time = rs.getTime("notification_time").toLocalTime();
                        boolean onlyDisruptions = rs.getBoolean("only_disruptions");
                        
                        Array linesArray = rs.getArray("lines");
                        Set<String> lines = new HashSet<>();
                        if (linesArray != null) {
                            String[] lineNames = (String[]) linesArray.getArray();
                            for (String lineName : lineNames) {
                                if (lineName != null) {
                                    lines.add(lineName);
                                }
                            }
                        }
                        
                        scheduledNotifications.put(time, new NotificationSettings(lines, onlyDisruptions));
                    }
                }
            }
            
            return new UserPreferences(favoriteLines, scheduledNotifications, disruptionAlerts, weekendNotifications);
            
        } catch (SQLException e) {
            logger.error("Failed to get user preferences for user {}", userId, e);
            throw new RuntimeException("Database error", e);
        }
    }
    
    private void ensureUserExists(Connection conn, Long userId) throws SQLException {
        String query = "INSERT INTO users (user_id) VALUES (?) ON CONFLICT (user_id) DO NOTHING";
        try (PreparedStatement stmt = conn.prepareStatement(query)) {
            stmt.setLong(1, userId);
            stmt.executeUpdate();
        }
    }
    
    public void addFavoriteLine(Long userId, String lineName) {
        try (Connection conn = dbManager.getConnection()) {
            ensureUserExists(conn, userId);
            
            String query = "INSERT INTO favorite_lines (user_id, line_name) VALUES (?, ?) ON CONFLICT (user_id, line_name) DO NOTHING";
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setLong(1, userId);
                stmt.setString(2, lineName);
                int rows = stmt.executeUpdate();
                
                if (rows > 0) {
                    logger.info("User {} added favorite line: {}", userId, lineName);
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to add favorite line for user {}: {}", userId, lineName, e);
            throw new RuntimeException("Database error", e);
        }
    }
    
    public void removeFavoriteLine(Long userId, String lineName) {
        try (Connection conn = dbManager.getConnection()) {
            String query = "DELETE FROM favorite_lines WHERE user_id = ? AND line_name = ?";
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setLong(1, userId);
                stmt.setString(2, lineName);
                int rows = stmt.executeUpdate();
                
                if (rows > 0) {
                    logger.info("User {} removed favorite line: {}", userId, lineName);
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to remove favorite line for user {}: {}", userId, lineName, e);
            throw new RuntimeException("Database error", e);
        }
    }
    
    public Set<String> getFavoriteLines(Long userId) {
        return getUserPreferences(userId).getFavoriteLines();
    }
    
    public void addScheduledNotification(Long userId, String time, Set<String> lines, boolean onlyDisruptions) {
        try {
            LocalTime notificationTime = LocalTime.parse(time, DateTimeFormatter.ofPattern("HH:mm"));
            
            try (Connection conn = dbManager.getConnection()) {
                conn.setAutoCommit(false);
                
                try {
                    ensureUserExists(conn, userId);
                    
                    // Insert notification
                    String notificationQuery = "INSERT INTO scheduled_notifications (user_id, notification_time, only_disruptions) VALUES (?, ?, ?) ON CONFLICT (user_id, notification_time) DO UPDATE SET only_disruptions = EXCLUDED.only_disruptions RETURNING id";
                    int notificationId;
                    
                    try (PreparedStatement stmt = conn.prepareStatement(notificationQuery)) {
                        stmt.setLong(1, userId);
                        stmt.setTime(2, Time.valueOf(notificationTime));
                        stmt.setBoolean(3, onlyDisruptions);
                        
                        try (ResultSet rs = stmt.executeQuery()) {
                            if (rs.next()) {
                                notificationId = rs.getInt("id");
                            } else {
                                throw new SQLException("Failed to insert notification");
                            }
                        }
                    }
                    
                    // Clear existing lines for this notification
                    String clearQuery = "DELETE FROM notification_lines WHERE notification_id = ?";
                    try (PreparedStatement stmt = conn.prepareStatement(clearQuery)) {
                        stmt.setInt(1, notificationId);
                        stmt.executeUpdate();
                    }
                    
                    // Insert new lines
                    if (!lines.isEmpty()) {
                        String lineQuery = "INSERT INTO notification_lines (notification_id, line_name) VALUES (?, ?)";
                        try (PreparedStatement stmt = conn.prepareStatement(lineQuery)) {
                            for (String line : lines) {
                                stmt.setInt(1, notificationId);
                                stmt.setString(2, line);
                                stmt.addBatch();
                            }
                            stmt.executeBatch();
                        }
                    }
                    
                    conn.commit();
                    logger.info("User {} added scheduled notification at {}", userId, time);
                    
                } catch (SQLException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to add scheduled notification for user {}: {}", userId, time, e);
            throw new RuntimeException("Database error", e);
        } catch (Exception e) {
            logger.error("Failed to parse time for user {}: {}", userId, time, e);
            throw new IllegalArgumentException("Invalid time format. Use HH:MM (24-hour format)");
        }
    }
    
    public void removeScheduledNotification(Long userId, String time) {
        try {
            LocalTime notificationTime = LocalTime.parse(time, DateTimeFormatter.ofPattern("HH:mm"));
            
            try (Connection conn = dbManager.getConnection()) {
                String query = "DELETE FROM scheduled_notifications WHERE user_id = ? AND notification_time = ?";
                try (PreparedStatement stmt = conn.prepareStatement(query)) {
                    stmt.setLong(1, userId);
                    stmt.setTime(2, Time.valueOf(notificationTime));
                    int rows = stmt.executeUpdate();
                    
                    if (rows > 0) {
                        logger.info("User {} removed scheduled notification at {}", userId, time);
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to remove scheduled notification for user {}: {}", userId, time, e);
            throw new RuntimeException("Database error", e);
        } catch (Exception e) {
            logger.error("Failed to parse time for user {}: {}", userId, time, e);
            throw new IllegalArgumentException("Invalid time format. Use HH:MM (24-hour format)");
        }
    }
    
    public void setDisruptionAlerts(Long userId, boolean enabled) {
        try (Connection conn = dbManager.getConnection()) {
            ensureUserExists(conn, userId);
            
            String query = "UPDATE users SET disruption_alerts_enabled = ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setBoolean(1, enabled);
                stmt.setLong(2, userId);
                stmt.executeUpdate();
                
                logger.info("User {} set disruption alerts to: {}", userId, enabled);
            }
        } catch (SQLException e) {
            logger.error("Failed to set disruption alerts for user {}: {}", userId, enabled, e);
            throw new RuntimeException("Database error", e);
        }
    }
    
    public void setWeekendNotifications(Long userId, boolean enabled) {
        try (Connection conn = dbManager.getConnection()) {
            ensureUserExists(conn, userId);
            
            String query = "UPDATE users SET weekend_notifications = ?, updated_at = CURRENT_TIMESTAMP WHERE user_id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setBoolean(1, enabled);
                stmt.setLong(2, userId);
                stmt.executeUpdate();
                
                logger.info("User {} set weekend notifications to: {}", userId, enabled);
            }
        } catch (SQLException e) {
            logger.error("Failed to set weekend notifications for user {}: {}", userId, enabled, e);
            throw new RuntimeException("Database error", e);
        }
    }
    
    public List<Long> getUsersWithScheduledNotifications(LocalTime currentTime) {
        List<Long> users = new ArrayList<>();
        
        try (Connection conn = dbManager.getConnection()) {
            String query = "SELECT DISTINCT user_id FROM scheduled_notifications WHERE notification_time = ?";
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setTime(1, Time.valueOf(currentTime));
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        users.add(rs.getLong("user_id"));
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get users with scheduled notifications for time {}", currentTime, e);
        }
        
        return users;
    }
    
    public List<Long> getUsersWithDisruptionAlerts() {
        List<Long> users = new ArrayList<>();
        
        try (Connection conn = dbManager.getConnection()) {
            String query = "SELECT user_id FROM users WHERE disruption_alerts_enabled = true";
            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        users.add(rs.getLong("user_id"));
                    }
                }
            }
        } catch (SQLException e) {
            logger.error("Failed to get users with disruption alerts", e);
        }
        
        return users;
    }
}