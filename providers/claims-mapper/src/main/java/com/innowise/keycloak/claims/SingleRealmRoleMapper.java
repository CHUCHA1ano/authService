package com.innowise.keycloak.claims;

import org.keycloak.models.ClientSessionContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.ProtocolMapperModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserSessionModel;
import org.keycloak.protocol.oidc.mappers.AbstractOIDCProtocolMapper;
import org.keycloak.protocol.oidc.mappers.OIDCAccessTokenMapper;
import org.keycloak.protocol.oidc.mappers.OIDCIDTokenMapper;
import org.keycloak.protocol.oidc.mappers.TokenIntrospectionTokenMapper;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.representations.IDToken;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Emits a single {@code role} string claim ({@code ADMIN} or {@code USER}) so downstream services
 * do not have to parse the Keycloak-specific {@code realm_access.roles} structure. The value is
 * deterministic, unlike the built-in realm role mapper: the first role from
 * {@link #CLAIMED_ROLES} found on the user wins.
 */
public class SingleRealmRoleMapper extends AbstractOIDCProtocolMapper
        implements OIDCAccessTokenMapper, OIDCIDTokenMapper, TokenIntrospectionTokenMapper {

    public static final String PROVIDER_ID = "jacapp-single-realm-role";
    public static final String CLAIM_NAME = "role";

    /** Application roles in priority order: the first one found on the user is used. */
    private static final List<String> CLAIMED_ROLES = List.of("ADMIN", "USER");

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayType() {
        return "jACApp Single Role";
    }

    @Override
    public String getDisplayCategory() {
        return TOKEN_MAPPER_CATEGORY;
    }

    @Override
    public String getHelpText() {
        return "Adds a single role claim (ADMIN or USER) resolved from the user's realm roles.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return List.of();
    }

    @Override
    protected void setClaim(IDToken token,
                            ProtocolMapperModel mappingModel,
                            UserSessionModel userSession,
                            KeycloakSession session,
                            ClientSessionContext clientSessionCtx) {
        Set<String> realmRoles = clientSessionCtx.getRolesStream()
                .filter(role -> role.getContainer() instanceof RealmModel)
                .map(RoleModel::getName)
                .collect(Collectors.toSet());

        String role = CLAIMED_ROLES.stream()
                .filter(realmRoles::contains)
                .findFirst()
                .orElse(null);

        if (role != null) {
            token.getOtherClaims().put(CLAIM_NAME, role);
        }
    }
}
