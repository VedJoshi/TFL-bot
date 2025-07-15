# 🚇 TFLBot - London Transport Information Bot

A sophisticated Telegram bot that provides real-time London Transport (TFL) information with intelligent notifications, user preferences, and robust error handling. Built with enterprise-grade architecture patterns and designed for scalability.

## Project Overview

TFLBot transforms the way London commuters access transport information by providing instant, personalized updates through Telegram. The bot demonstrates advanced software engineering principles including clean architecture, concurrent processing, intelligent caching, and reliable deployment strategies.

## Core Features

### Real-Time Transport Information
- **Live Status Updates**: Instant access to all London Underground line statuses
- **Line-Specific Queries**: Detailed information for individual tube lines
- **Service Disruption Alerts**: Proactive notifications about delays and closures
- **Journey Planning**: Route optimization and travel time estimates with natural language input
- **Station Information**: Live arrivals, facilities, and accessibility details

### Intelligent User Experience
- **Personalized Favorites**: Save frequently used lines for quick access
- **Scheduled Notifications**: Customizable alerts for commute times with time validation
- **Smart Input Parsing**: Natural language journey planning (e.g., "from King's Cross to Oxford Circus")
- **Context-Aware Interface**: State management for multi-step interactions
- **Robust Error Handling**: Graceful fallbacks and user-friendly error messages

### Enterprise-Grade Reliability
- **Input Validation**: Comprehensive validation preventing invalid API calls
- **API Error Prevention**: Smart filtering of callback data before API requests
- **State Management**: Proper handling of user interaction states
- **Graceful Degradation**: Fallback mechanisms for service failures

## Technical Architecture

### Design Patterns & Principles

**Clean Architecture Implementation**
- Separation of concerns across distinct service layers
- Dependency injection for loose coupling
- Single responsibility principle throughout codebase
- Interface-driven design for testability

**Concurrent Processing System**
- Background notification scheduler using ScheduledExecutorService
- Thread-safe user preference management
- Asynchronous message handling for optimal performance
- Resource pooling for efficient connection management

**Intelligent Caching Strategy**
- Time-based cache invalidation with configurable TTL
- Memory-efficient data structures
- Cache hit rate optimization
- Automatic fallback to live data when cache expires

### Data Management

**Database Architecture**
- HikariCP connection pooling for high concurrency
- Prepared statements for SQL injection prevention
- Transaction management for data consistency
- Automatic connection health checks

**User Preference System**
- Relational data modeling for scalable user preferences
- Efficient querying with indexed lookups
- Real-time preference synchronization
- Privacy-compliant data handling

### External API Integration

**TFL API Integration**
- RESTful API consumption with robust error handling
- Exponential backoff retry mechanism
- Rate limiting compliance
- JSON parsing with Jackson for type safety

**Telegram Bot API**
- Long polling for real-time message processing
- Inline keyboard management
- Callback query handling
- Message formatting with Markdown support

## Technology Stack

### Core Technologies
- **Java 11+**: Modern language features and performance optimizations
- **Maven**: Dependency management and build automation
- **PostgreSQL**: Production-ready relational database
- **Jackson**: High-performance JSON processing
- **SLF4J + Logback**: Structured logging with configurable levels

### Frameworks & Libraries
- **Telegram Bots API**: Official Java library for Telegram integration
- **HikariCP**: High-performance JDBC connection pooling
- **Docker**: Containerization for consistent deployments

## Software Engineering Highlights

### Scalability Features
- **Connection Pooling**: Handles 50+ concurrent users efficiently
- **Asynchronous Processing**: Non-blocking operations for optimal throughput
- **Stateless Design**: Horizontal scaling capability
- **Resource Management**: Automatic cleanup and memory optimization

### Security & Reliability
- **Input Validation**: Comprehensive sanitization of user inputs
- **SQL Injection Prevention**: Parameterized queries throughout
- **Error Boundary Implementation**: Graceful degradation on failures
- **Secrets Management**: Environment-based configuration

### Monitoring & Observability
- **Structured Logging**: JSON-formatted logs for analysis
- **Health Endpoints**: Real-time system status reporting
- **Error Tracking**: Comprehensive exception logging
- **Performance Metrics**: Response time and cache hit rate monitoring

### Technical Decision Making
- **Caching Strategy**: Balanced data freshness with API rate limits
- **Database Design**: Optimized for read-heavy workloads with proper indexing
- **Error Handling**: Implemented circuit breaker pattern for external API resilience
- **User Experience**: Designed conversational interface based on user behavior analysis

#### Advanced Error Handling
- **Input Sanitization**: Prevents invalid TFL API calls through pre-validation
- **State-Based Interaction**: Context-aware message handling for complex workflows
- **API Call Optimization**: Smart caching and validation reduces unnecessary requests
- **User Experience Continuity**: Graceful error recovery maintains conversation flow

#### Robust Callback Management
- **Command Validation**: Distinguishes between line IDs and system commands
- **State Machine Pattern**: Manages multi-step user interactions reliably
- **Fallback Handling**: Comprehensive error messages guide user corrections
- **Input Format Validation**: Time format, station name, and journey format checking

## Operations

### Monitoring & Maintenance
- **Health Check Endpoints**: `/health`, `/status` for monitoring integration
- **Graceful Shutdown**: Proper resource cleanup on application termination
- **Rolling Updates**: Zero-downtime deployment capability
- **Log Aggregation**: Structured logging for centralized monitoring

### Configuration Management
- **Environment Variables**: Secure configuration without hardcoded values
- **Platform Detection**: Automatic adaptation to deployment environment
- **Database Connection**: Flexible connection string management
- **Feature Toggles**: Runtime feature enabling/disabling capability

## 💡 Key Learning Outcomes

### Technical Skills Demonstrated
- **Concurrent Programming**: Thread-safe operations and resource management
- **API Design**: RESTful principles and error handling best practices
- **Database Optimization**: Query optimization and connection management
- **Cloud Deployment**: Multi-platform deployment strategies

### Software Engineering Practices
- **Clean Code**: Readable, maintainable, and well-documented codebase
- **Testing**: Comprehensive test coverage with multiple testing strategies
- **Version Control**: Git workflow with meaningful commit messages
- **Documentation**: Clear technical documentation and user guides

## 🚀 Getting Started

### Prerequisites
- Java 11 or higher
- Maven 3.6+
- PostgreSQL 12+ (or Docker for local development)
- Telegram Bot Token (obtain from @BotFather)

### Quick Setup

1. **Clone the repository**
   ```bash
   git clone <repository-url>
   cd UpdatedTFLBot
   ```

2. **Set up environment variables**
   ```bash
   export BOT_TOKEN=your_telegram_bot_token
   export DATABASE_URL=your_database_connection_string
   ```

3. **Build the application**
   ```bash
   mvn clean compile
   mvn package -DskipTests
   ```

4. **Run the bot**
   ```bash
   java -jar target/tfl-bot-1.0-SNAPSHOT.jar
   ```

### Docker Deployment

1. **Build Docker image**
   ```bash
   docker build -t tfl-bot .
   ```

2. **Run with environment variables**
   ```bash
   docker run -e BOT_TOKEN=your_token -e DATABASE_URL=your_db_url tfl-bot
   ```

### Testing Features

Once running, test these key features:

1. **Journey Planning**: Send "from King's Cross to Oxford Circus"
2. **Station Info**: Send any station name like "Liverpool Street"
3. **Schedule Alerts**: Use the Schedule Alerts button and follow prompts
4. **Line Status**: Use the menu to check individual lines

### Database Setup

1. **Create PostgreSQL database**
2. **Run migration scripts** (located in `src/main/resources/db/`)
3. **Verify connection** through application health endpoint

The bot will automatically start polling for messages and initialize all background services. Monitor the logs for successful startup confirmation and any configuration issues.
