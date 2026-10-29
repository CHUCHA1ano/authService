# --- Build the custom BCrypt password hash provider -------------------------
FROM maven:3.9.9-eclipse-temurin-21 AS provider-build

WORKDIR /build

COPY providers/bcrypt-provider/pom.xml ./pom.xml
RUN mvn -B -q dependency:go-offline

COPY providers/bcrypt-provider/src ./src
RUN mvn -B -q clean package -DskipTests

# --- Keycloak with the provider and the realm import ------------------------
FROM quay.io/keycloak/keycloak:26.8.1

COPY --from=provider-build /build/target/bcrypt-password-hash-provider.jar /opt/keycloak/providers/
COPY realm/jacapp-realm.json /opt/keycloak/data/import/jacapp-realm.json

# Re-augment Keycloak so the custom provider is available at runtime.
RUN /opt/keycloak/bin/kc.sh build

ENTRYPOINT ["/opt/keycloak/bin/kc.sh", "start", "--import-realm"]
