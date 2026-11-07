# --- Build the custom SPis (BCrypt password hashing + claims mapper) ---------
FROM maven:3.9.9-eclipse-temurin-21 AS provider-build

WORKDIR /build

COPY pom.xml ./pom.xml
COPY providers ./providers
RUN mvn -B -q dependency:go-offline

RUN mvn -B -q -DskipTests package

# --- Keycloak with the providers and the realm import ------------------------
FROM quay.io/keycloak/keycloak:26.8.1

COPY --from=provider-build /build/providers/bcrypt-provider/target/bcrypt-password-hash-provider.jar /opt/keycloak/providers/
COPY --from=provider-build /build/providers/claims-mapper/target/keycloak-claims-mapper.jar /opt/keycloak/providers/
COPY realm/jacapp-realm.json /opt/keycloak/data/import/jacapp-realm.json

# Re-augment Keycloak so the custom providers are available at runtime.
RUN /opt/keycloak/bin/kc.sh build

ENTRYPOINT ["/opt/keycloak/bin/kc.sh", "start", "--import-realm"]
