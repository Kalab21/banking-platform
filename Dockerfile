# Shared runtime image for all thirteen Spring Boot services.
#
# Each service builds with its own module as the context and its jar copied in,
# so one file keeps the runtime identical across the platform.

FROM eclipse-temurin:17-jre-alpine

# Run as an unprivileged user. A container process that starts as root keeps
# root's capabilities inside the container, so a remote-code-execution bug in
# the application becomes root-in-container rather than a constrained failure.
# Nothing here needs elevated privileges: the jar binds a high port and writes
# nothing outside its own working directory.
RUN addgroup --system --gid 1001 banking \
 && adduser  --system --uid 1001 --ingroup banking banking

WORKDIR /app
COPY --chown=banking:banking target/*.jar app.jar

USER banking

# The JVM sizes its heap from the *container* limit rather than the host's RAM.
# Without this, thirteen JVMs on one machine each size themselves against total
# host memory and collectively promise far more than exists — the usual cause
# of a Compose stack that dies under load with no single obvious culprit.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:InitialRAMPercentage=30 -XX:+ExitOnOutOfMemoryError"

# Shell form so $JAVA_OPTS is expanded; exec so the JVM is PID 1 and receives
# SIGTERM directly, giving Spring a clean shutdown instead of a 10s kill.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
