# ---------- build ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---------- runtime ----------
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
ENV TZ=Asia/Taipei
ENV SPRING_PROFILES_ACTIVE=prod
COPY --from=build /workspace/target/baseball-score.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=75","-jar","/app/app.jar"]
