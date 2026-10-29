package innowise.keycloak.bcrypt;

import at.favre.lib.crypto.bcrypt.BCrypt;
import org.keycloak.models.PasswordPolicy;
import org.keycloak.models.credential.PasswordCredentialModel;
import org.keycloak.credential.hash.PasswordHashProvider;

import java.security.SecureRandom;

/**
 * Keycloak {@link PasswordHashProvider} backed by BCrypt.
 * <p>
 * BCrypt generates a random salt for every hash and embeds it into the resulting
 * {@code $2a$<cost>$<salt><hash>} value, so each password gets its own unique salt.
 * The salt is additionally kept in Keycloak's credential secret data for informational
 * purposes and to stay compatible with the standard credential model.
 */
public class BcryptPasswordHashProvider implements PasswordHashProvider {

    public static final String PROVIDER_ID = "bcrypt";

    private static final int MIN_COST = 4;
    private static final int MAX_COST = 31;
    private static final int DEFAULT_COST = 12;
    private static final int SALT_LENGTH = 16;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final String providerId;

    public BcryptPasswordHashProvider(String providerId) {
        this.providerId = providerId;
    }

    @Override
    public boolean policyCheck(PasswordPolicy policy, PasswordCredentialModel credential) {
        int expectedCost = resolveCost(policy != null ? policy.getHashIterations() : -1);
        return providerId.equals(credential.getPasswordCredentialData().getAlgorithm())
                && credential.getPasswordCredentialData().getHashIterations() == expectedCost;
    }

    @Override
    public PasswordCredentialModel encodedCredential(String rawPassword, int iterations) {
        int cost = resolveCost(iterations);
        String hash = BCrypt.withDefaults().hashToString(cost, rawPassword.toCharArray());
        return PasswordCredentialModel.createFromValues(providerId, randomSalt(), cost, hash);
    }

    @Override
    public boolean verify(String rawPassword, PasswordCredentialModel credential) {
        String hash = credential.getPasswordSecretData().getValue();
        if (hash == null || hash.isBlank()) {
            return false;
        }
        BCrypt.Result result = BCrypt.verifyer().verify(rawPassword.toCharArray(), hash.toCharArray());
        return result.verified;
    }

    @Override
    public void close() {
        // nothing to release
    }

    /**
     * Keycloak passes the {@code hashIterations} password policy value as "iterations".
     * For BCrypt this value is the cost factor and must stay within 4..31. Anything
     * outside of that range (including the PBKDF2 default of -1) falls back to cost 12.
     */
    private int resolveCost(int iterations) {
        if (iterations < MIN_COST || iterations > MAX_COST) {
            return DEFAULT_COST;
        }
        return iterations;
    }

    private byte[] randomSalt() {
        byte[] salt = new byte[SALT_LENGTH];
        SECURE_RANDOM.nextBytes(salt);
        return salt;
    }
}
