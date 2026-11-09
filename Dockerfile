FROM gradle:8.11.1-jdk17 AS build
WORKDIR /app
COPY . .
RUN gradle installDist --no-daemon -q

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/install/petpal-server .
CMD ["bin/petpal-server"]
