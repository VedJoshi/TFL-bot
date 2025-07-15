FROM openjdk:17-jdk-slim

WORKDIR /app

# Copy Maven files for dependency caching
COPY pom.xml .
COPY src ./src

# Install Maven
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

# Build the application
RUN mvn clean package -DskipTests

# Run the application
CMD ["java", "-jar", "target/TFL-bot-1.0-SNAPSHOT.jar"]

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:8080/health || exit 1
