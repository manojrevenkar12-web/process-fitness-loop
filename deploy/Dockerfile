# The pfl command line in a small JRE image. Build the jars first: mvn -q package -DskipTests
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S pfl && adduser -S pfl -G pfl
WORKDIR /app
COPY pfl-service/target/pfl-service-*.jar /app/lib/
COPY pfl-service/target/lib/*.jar /app/lib/
COPY config /app/config
COPY processes /app/processes
COPY proposals /app/proposals
COPY data /app/data
USER pfl
ENTRYPOINT ["sh", "-c", "exec java -cp '/app/lib/*' io.github.mkp.pfl.service.Main \"$@\"", "--"]
