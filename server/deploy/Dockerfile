# Runtime-only image: the app is built on the dev machine (./gradlew :server:installDist),
# because configuring the Gradle build needs the Android SDK that the NAS does not have.
FROM eclipse-temurin:17-jre

WORKDIR /app
COPY bff/ /app/
# Built on Windows the start script loses its exec bit inside the tarball.
RUN chmod 755 /app/bin/bff

ENV PORT=8080
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=3 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/${PORT} && printf "GET /healthz HTTP/1.0\r\n\r\n" >&3 && grep -q " 200 " <&3' || exit 1

ENTRYPOINT ["/app/bin/bff"]
