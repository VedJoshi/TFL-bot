package bot;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class DatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    private static DatabaseManager instance;
    private final HikariDataSource dataSource;
    
    private DatabaseManager() {
        HikariConfig config = new HikariConfig();
        
        // Railway provides DATABASE_URL environment variable
        String databaseUrl = System.getenv("DATABASE_URL");
        if (databaseUrl == null) {
            // Fallback for local development
            databaseUrl = "jdbc:postgresql://localhost:5432/tflbot?user=postgres&password=password";
            logger.warn("DATABASE_URL not found, using local development database");
        }
        
        config.setJdbcUrl(databaseUrl);
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);
        
        this.dataSource = new HikariDataSource(config);
        
        // Initialize database schema
        initializeSchema();
        
        logger.info("Database connection pool initialized");
    }
    
    public static synchronized DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager();
        }
        return instance;
    }
    
    public DataSource getDataSource() {
        return dataSource;
    }
    
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }
    
    private void initializeSchema() {
        try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
            
            // Users table
            stmt.execute("CREATE TABLE IF NOT EXISTS users (" +
                    "user_id BIGINT PRIMARY KEY," +
                    "disruption_alerts_enabled BOOLEAN DEFAULT true," +
                    "weekend_notifications BOOLEAN DEFAULT false," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP" +
                    ")");
            
            // Favorite lines table
            stmt.execute("CREATE TABLE IF NOT EXISTS favorite_lines (" +
                    "id SERIAL PRIMARY KEY," +
                    "user_id BIGINT REFERENCES users(user_id) ON DELETE CASCADE," +
                    "line_name VARCHAR(100) NOT NULL," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "UNIQUE(user_id, line_name)" +
                    ")");
            
            // Scheduled notifications table
            stmt.execute("CREATE TABLE IF NOT EXISTS scheduled_notifications (" +
                    "id SERIAL PRIMARY KEY," +
                    "user_id BIGINT REFERENCES users(user_id) ON DELETE CASCADE," +
                    "notification_time TIME NOT NULL," +
                    "only_disruptions BOOLEAN DEFAULT false," +
                    "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "UNIQUE(user_id, notification_time)" +
                    ")");
            
            // Notification lines table (many-to-many relationship)
            stmt.execute("CREATE TABLE IF NOT EXISTS notification_lines (" +
                    "id SERIAL PRIMARY KEY," +
                    "notification_id INTEGER REFERENCES scheduled_notifications(id) ON DELETE CASCADE," +
                    "line_name VARCHAR(100) NOT NULL," +
                    "UNIQUE(notification_id, line_name)" +
                    ")");
            
            logger.info("Database schema initialized successfully");
            
        } catch (SQLException e) {
            logger.error("Failed to initialize database schema", e);
            throw new RuntimeException("Database initialization failed", e);
        }
    }
    
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("Database connection pool closed");
        }
    }
}
