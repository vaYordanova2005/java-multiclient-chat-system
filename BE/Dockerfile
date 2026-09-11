# Multi-stage build so the final image only carries a JRE + the fat jar, not
# the Maven wrapper/build cache. Render builds this with dockerContext: BE
# (see ../render.yaml), so all paths below are relative to BE/.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

COPY src ./src
RUN ./mvnw clean package -DskipTests -B

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Render injects PORT at runtime; application.yml already reads it
# (server.port: ${PORT:5000}) so nothing else is needed here.
ENTRYPOINT ["java", "-jar", "app.jar"]
