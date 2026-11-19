FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY . .
RUN ./gradlew --no-daemon :driveBroker:installDist

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 drivebroker
WORKDIR /app
COPY --from=build /workspace/driveBroker/build/install/driveBroker/ /app/
USER 10001
EXPOSE 8080
ENTRYPOINT ["/app/bin/driveBroker"]
