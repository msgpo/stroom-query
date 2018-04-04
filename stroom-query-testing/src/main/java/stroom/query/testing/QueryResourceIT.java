package stroom.query.testing;

import io.dropwizard.Configuration;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.Rule;
import org.junit.Test;
import stroom.datasource.api.v2.DataSource;
import stroom.query.api.v2.DocRef;
import stroom.query.api.v2.ExpressionOperator;
import stroom.query.api.v2.OffsetRange;
import stroom.query.api.v2.SearchRequest;
import stroom.query.audit.authorisation.DocumentPermission;
import stroom.query.audit.client.DocRefResourceHttpClient;
import stroom.query.audit.client.QueryResourceHttpClient;
import stroom.query.audit.model.DocRefEntity;
import stroom.query.audit.rest.AuditedDocRefResourceImpl;
import stroom.query.audit.rest.AuditedQueryResourceImpl;

import javax.ws.rs.core.Response;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static stroom.query.testing.FifoLogbackRule.containsAllOf;

public abstract class QueryResourceIT<
        DOC_REF_ENTITY extends DocRefEntity,
        CONFIG_CLASS extends Configuration> {

    private final String docRefType;
    protected DocRefResourceHttpClient<DOC_REF_ENTITY> docRefClient;
    protected QueryResourceHttpClient queryClient;
    private final StroomAuthenticationRule authRule;

    @Rule
    public FifoLogbackRule auditLogRule = new FifoLogbackRule();

    protected QueryResourceIT(final String docRefType,
                              final DropwizardAppWithClientsRule<CONFIG_CLASS> appRule,
                              final StroomAuthenticationRule authRule) {
        this.docRefType = docRefType;
        this.authRule = authRule;
        this.queryClient = appRule.getClient(QueryResourceHttpClient::new);
        this.docRefClient = appRule.getClient(DocRefResourceHttpClient::new);
    }

    protected abstract SearchRequest getValidSearchRequest(final DocRef docRef,
                                                           final ExpressionOperator expressionOperator,
                                                           final OffsetRange offsetRange);

    protected abstract void assertValidDataSource(final DataSource dataSource);

    protected abstract DOC_REF_ENTITY getValidEntity(final DocRef docRef);

    @Test
    public void testGetDataSource() {
        final DocRef docRef = createDocument();

        final Response response = queryClient.getDataSource(authRule.adminUser(), docRef);
        assertEquals(HttpStatus.OK_200, response.getStatus());
        final DataSource result = response.readEntity(DataSource.class);

        assertValidDataSource(result);

        // Create doc ref, update, get data source
        auditLogRule.check()
                .thereAreAtLeast(3)
                .containsOrdered(containsAllOf(AuditedDocRefResourceImpl.CREATE_DOC_REF, docRef.getUuid()))
                .containsOrdered(containsAllOf(AuditedDocRefResourceImpl.UPDATE_DOC_REF, docRef.getUuid()))
                .containsOrdered(containsAllOf(AuditedQueryResourceImpl.GET_DATA_SOURCE, docRef.getUuid()));
    }

    @Test
    public void testGetDataSourcePermissions() {
        final DocRef docRef = createDocument();

        final String authorisedUsername = UUID.randomUUID().toString();
        final String unauthorisedUsername = UUID.randomUUID().toString();
        final String unauthenticatedUsername = UUID.randomUUID().toString();

        authRule.permitAuthenticatedUser(authorisedUsername)
                .docRef(docRef.getUuid(), docRefType)
                .permission(DocumentPermission.READ)
                .done();

        final Response authorisedResponse = queryClient.getDataSource(authRule.authenticatedUser(authorisedUsername), docRef);
        assertEquals(HttpStatus.OK_200, authorisedResponse.getStatus());
        authorisedResponse.close();

        final Response unauthorisedResponse = queryClient.getDataSource(authRule.authenticatedUser(unauthorisedUsername), docRef);
        assertEquals(HttpStatus.FORBIDDEN_403, unauthorisedResponse.getStatus());
        unauthorisedResponse.close();

        final Response unauthenticatedResponse = queryClient.getDataSource(authRule.unauthenticatedUser(unauthenticatedUsername), docRef);
        assertEquals(HttpStatus.UNAUTHORIZED_401, unauthenticatedResponse.getStatus());
        unauthenticatedResponse.close();

        // Create index, update, authorised get, unauthorised get
        auditLogRule.check()
                .thereAreAtLeast(4)
                .containsOrdered(containsAllOf(AuditedDocRefResourceImpl.CREATE_DOC_REF, docRef.getUuid()))
                .containsOrdered(containsAllOf(AuditedDocRefResourceImpl.UPDATE_DOC_REF, docRef.getUuid()))
                .containsOrdered(containsAllOf(AuditedQueryResourceImpl.GET_DATA_SOURCE, docRef.getUuid()))
                .containsOrdered(containsAllOf(AuditedQueryResourceImpl.GET_DATA_SOURCE, docRef.getUuid()));
    }

    @Test
    public void testSearchPermissions() {
        final DocRef docRef = createDocument();

        final String authorisedUsername = UUID.randomUUID().toString();
        final String unauthorisedUsername = UUID.randomUUID().toString();
        final String unauthenticatedUsername = UUID.randomUUID().toString();

        authRule.permitAuthenticatedUser(authorisedUsername)
                .docRef(docRef.getUuid(), docRefType)
                .permission(DocumentPermission.READ)
                .done();

        final SearchRequest searchRequest = getValidSearchRequest(docRef,
                new ExpressionOperator.Builder(ExpressionOperator.Op.OR).build(),
                new OffsetRange.Builder()
                        .length(0L)
                        .offset(0L)
                        .build());

        final Response authorisedResponse = queryClient.search(authRule.authenticatedUser(authorisedUsername), searchRequest);
        assertEquals(HttpStatus.OK_200, authorisedResponse.getStatus());
        authorisedResponse.close();

        final Response unauthorisedResponse = queryClient.getDataSource(authRule.authenticatedUser(unauthorisedUsername), docRef);
        assertEquals(HttpStatus.FORBIDDEN_403, unauthorisedResponse.getStatus());
        unauthorisedResponse.close();

        final Response unauthenticatedResponse = queryClient.getDataSource(authRule.unauthenticatedUser(unauthenticatedUsername), docRef);
        assertEquals(HttpStatus.UNAUTHORIZED_401, unauthenticatedResponse.getStatus());
        unauthenticatedResponse.close();

    }

    /**
     * Utility function to randomly generate a new annotations index doc ref.
     * It assumes that the creation of documents works, the detail of that is tested in another suite of tests.
     * Once the document is created, the passed in doc ref entity is then used to flesh out the implementation
     * specific details.
     *
     * @param docRefEntity The implementation specific entity, used to update the doc ref so it can be used.
     * @return The DocRef of the newly created annotations index.
     */
    private DocRef createDocument(final DOC_REF_ENTITY docRefEntity) {
        final DocRef docRef = new DocRef.Builder()
                .uuid(UUID.randomUUID().toString())
                .type(docRefType)
                .name(UUID.randomUUID().toString())
                .build();

        // Ensure admin user can create the document in the folder
        authRule.permitAdminUser()
                .done();

        // Create a doc ref to hang the search from
        final Response createResponse = docRefClient.createDocument(
                authRule.adminUser(),
                docRef.getUuid(),
                docRef.getName());
        assertEquals(HttpStatus.OK_200, createResponse.getStatus());
        createResponse.close();

        // Give admin all the roles required to manipulate the document and it's underlying data
        authRule.permitAdminUser()
                .docRef(docRef)
                .permission(DocumentPermission.READ)
                .permission(DocumentPermission.UPDATE)
                .done();

        final DOC_REF_ENTITY docRefEntityToUse = (docRefEntity != null) ? docRefEntity : getValidEntity(docRef);
        final Response updateIndexResponse =
                docRefClient.update(authRule.adminUser(),
                        docRef.getUuid(),
                        docRefEntityToUse);
        assertEquals(HttpStatus.OK_200, updateIndexResponse.getStatus());
        updateIndexResponse.close();

        return docRef;
    }

    /**
     * Create document, use the 'valid' doc ref entity for this test class.
     *
     * @return The Doc Ref of the created document.
     */
    private DocRef createDocument() {
        return createDocument(null);
    }
}
