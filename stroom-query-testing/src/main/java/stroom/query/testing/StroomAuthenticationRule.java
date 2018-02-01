package stroom.query.testing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.github.tomakehurst.wiremock.core.Options;
import com.github.tomakehurst.wiremock.junit.WireMockClassRule;
import org.eclipse.jetty.http.HttpStatus;
import org.jose4j.jwk.JsonWebKey;
import org.jose4j.jwk.RsaJsonWebKey;
import org.jose4j.jwk.RsaJwkGenerator;
import org.jose4j.jws.AlgorithmIdentifiers;
import org.jose4j.jws.JsonWebSignature;
import org.jose4j.jwt.JwtClaims;
import org.jose4j.lang.JoseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import stroom.query.api.v2.DocRef;
import stroom.query.audit.authorisation.DocumentPermission;
import stroom.query.audit.security.ServiceUser;

import javax.ws.rs.core.MediaType;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.Assert.fail;

/**
 * This rule effectively builds an Authentication and Authorisation service for use in integration tests.
 *
 * It provides methods for getting authenticated and unauthenticated users, it also gives tests the ability
 * to grant permissions to specific users.
 */
public class StroomAuthenticationRule extends WireMockClassRule {
    private static final Logger LOGGER = LoggerFactory.getLogger(StroomAuthenticationRule.class);

    private final String ADMIN_USER = UUID.randomUUID().toString();

    private RsaJsonWebKey jwk;
    private final ConcurrentHashMap<String, ServiceUser> authenticatedUsers = new ConcurrentHashMap<>();

    private RsaJsonWebKey invalidJwk;
    private final ConcurrentHashMap<String, ServiceUser> unauthenticatedUsers = new ConcurrentHashMap<>();

    private final String docRefType;

    private static final com.fasterxml.jackson.databind.ObjectMapper jacksonObjectMapper
            = new com.fasterxml.jackson.databind.ObjectMapper();

    public StroomAuthenticationRule(final Options options,
                                    final String docRefType) {
        super(options);

        this.docRefType = docRefType;
    }

    @Override
    protected void before() {
        super.before();

        LOGGER.info("Setting Up Stroom Auth with Wiremock");

        //
        // Setup Authentication Service
        //
        try {
            String jwkId = UUID.randomUUID().toString();
            jwk = RsaJwkGenerator.generateJwk(2048);
            jwk.setKeyId(jwkId);
        } catch (Exception e) {
            fail(e.getLocalizedMessage());
            return;
        }

        //
        // Setup another public key, which can be used to generate credentials which are the right format, but won't authenticate with our service
        //
        try {
            String jwkId = UUID.randomUUID().toString();
            invalidJwk = RsaJwkGenerator.generateJwk(2048);
            invalidJwk.setKeyId(jwkId);
        } catch (Exception e) {
            fail(e.getLocalizedMessage());
            return;
        }

        stubFor(get(urlEqualTo("/testAuthService/publicKey"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON)
                        .withBody(jwk.toJson(JsonWebKey.OutputControlLevel.PUBLIC_ONLY))
                        .withStatus(200)));

        //
        // Setup Authorisation Service for Doc Refs, by default allow nothing
        // Specific sub classes will have to setup specific authorisations.
        //
        stubFor(post(urlEqualTo("/api/authorisation/v1/isAuthorised"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", MediaType.TEXT_PLAIN)
                        .withBody("Mock disapproval for authorisation")
                        .withStatus(HttpStatus.UNAUTHORIZED_401)));
    }

    @Override
    protected void after() {
        super.after();

        authenticatedUsers.clear();
        unauthenticatedUsers.clear();
    }

    /**
     * Function that gets the default admin user, this user will generally have permission to do everything
     * @return The default admin ServiceUser, with an API key
     */
    public ServiceUser adminUser() {
        return serviceUser(ADMIN_USER, authenticatedUsers, jwk);
    }

    /**
     * Utility function to generate an authenticated user.
     * @param username The usernane of the required user.
     * @return the user, with a JWK tied to the valid public key credentials
     */
    public ServiceUser authenticatedUser(final String username) {
        return serviceUser(username, authenticatedUsers, jwk);
    }

    /**
     * Utility function to generate an unauthenticated user.
     * @param username The usernane of the required user.
     * @return the user, with a JWK tied to the wrong public key credentials
     */
    public ServiceUser unauthenticatedUser(final String username) {
        return serviceUser(username, unauthenticatedUsers, invalidJwk);
    }

    /**
     * Request a user with a given name, if the user does not exist, a new API key is created for that user.
     * Allows for the creation of multiple users, which may have different levels of access to doc refs for testing
     * that authorisation works.
     * @param username The usernane of the required user.
     * @param usersMap map of users to use, there are authenticated and unauthenticated users
     * @param publicKey The public key that is tied to the users map
     * @return A created ServiceUser, with an API Key
     */
    private ServiceUser serviceUser(final String username,
                                    final ConcurrentHashMap<String, ServiceUser> usersMap,
                                    final RsaJsonWebKey publicKey) {
        return usersMap.computeIfAbsent(username, u -> {
            ServiceUser serviceUser = null;

            JwtClaims claims = new JwtClaims();
            claims.setIssuer("stroom");  // who creates the token and signs it
            claims.setSubject(u); // the subject/principal is whom the token is about

            final JsonWebSignature jws = new JsonWebSignature();
            jws.setPayload(claims.toJson());
            jws.setAlgorithmHeaderValue(AlgorithmIdentifiers.RSA_USING_SHA256);
            jws.setKey(publicKey.getPrivateKey());
            jws.setDoKeyValidation(false);

            try {
                serviceUser = new ServiceUser.Builder()
                        .jwt(jws.getCompactSerialization())
                        .name(u)
                        .build();
            } catch (JoseException e) {
                fail(e.getLocalizedMessage());
            }

            return serviceUser;
        });
    }

    public void giveFolderCreatePermission(final ServiceUser serviceUser,
                                           final String folderUuid) {

        givePermission(serviceUser, new DocRef.Builder()
                        .type(DocumentPermission.FOLDER)
                        .uuid(folderUuid)
                        .build(),
                DocumentPermission.CREATE.getTypedPermission(this.docRefType));
    }

    public void giveDocumentPermission(final ServiceUser serviceUser,
                                       final String uuid,
                                       final DocumentPermission permission) {
        givePermission(serviceUser, new DocRef.Builder()
                        .type(this.docRefType)
                        .uuid(uuid)
                        .build(),
                permission.getName());
    }

    public void givePermission(final ServiceUser serviceUser,
                               final DocRef docRef,
                               final String permissionName) {
        /**
         * Setup Authorisation Service for Doc Refs, by default allow everything
         * Specific sub classes may have more specific cases to test for unauthorised actions.
         */

        final Map<String, Object> request = new HashMap<>();
        request.put("docRef", docRef);
        request.put("permission", permissionName);

        String requestJson;
        try {
            requestJson = jacksonObjectMapper.writeValueAsString(request);
        } catch (JsonProcessingException e) {
            fail(e.getLocalizedMessage());
            return;
        }

        stubFor(post(urlEqualTo("/api/authorisation/v1/isAuthorised"))
                .withRequestBody(equalToJson(requestJson))
                .withHeader("Authorization", containing(serviceUser.getJwt()))
                .willReturn(ok("Mock approval for authorisation")));
    }
}