package stroom.query.testing.jooq;

import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import stroom.datasource.api.v2.DataSource;
import stroom.datasource.api.v2.DataSourceField;
import stroom.query.api.v2.*;
import stroom.query.authorisation.DocumentPermission;
import stroom.query.audit.model.DocRefEntity;
import stroom.query.audit.rest.AuditedDocRefResourceImpl;
import stroom.query.audit.service.QueryApiException;
import stroom.query.security.UrlTokenReplacer;
import stroom.query.testing.DropwizardAppWithClientsRule;
import stroom.query.testing.QueryRemoteServiceIT;
import stroom.query.testing.StroomAuthenticationRule;
import stroom.query.testing.data.CreateTestDataClient;
import stroom.query.testing.jooq.app.*;

import javax.ws.rs.core.Response;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static io.dropwizard.testing.ResourceHelpers.resourceFilePath;
import static org.junit.Assert.*;

public class TestJooqQueryRemoteServiceIT extends QueryRemoteServiceIT<TestDocRefJooqEntity, JooqConfig> {
    @ClassRule
    public static final DropwizardAppWithClientsRule<JooqConfig> appRule =
            new DropwizardAppWithClientsRule<>(JooqApp.class, resourceFilePath("jooq/config.yml"));

    @ClassRule
    public static StroomAuthenticationRule authRule =
            new StroomAuthenticationRule(WireMockConfiguration.options().dynamicPort());

    private final CreateTestDataClient testDataClient;

    private String testDataSeed;
    private DocRef testDataDocRef;

    public TestJooqQueryRemoteServiceIT() {
        super(TestDocRefJooqEntity.TYPE,
                TestDocRefJooqEntity.class,
                appRule,
                authRule);
        testDataClient = appRule.getClient(CreateTestDataClient::new);
        UrlTokenReplacer.setPort(authRule.port());
    }

    @Before
    public void beforeTest() throws QueryApiException {
        testDataSeed = UUID.randomUUID().toString();

        testDataDocRef = new DocRef.Builder()
                .uuid(UUID.randomUUID().toString())
                .name(UUID.randomUUID().toString())
                .type(TestDocRefJooqEntity.TYPE)
                .build();
        authRule.permitAdminUser()
                .done();
        authRule.permitAdminUser()
                .docRef(testDataDocRef)
                .permission(DocumentPermission.READ)
                .done();

        docRefClient.createDocument(authRule.adminUser(), testDataDocRef.getUuid(), testDataDocRef.getName())
                .orElseThrow(() -> new AssertionError("Response body missing"));

        final Response createTestDataResponse = testDataClient.createTestData(authRule.adminUser(), testDataDocRef.getUuid(), testDataSeed);
        assertEquals(HttpStatus.NO_CONTENT_204, createTestDataResponse.getStatus());
        createTestDataResponse.close();

        // Clear the audit log for the create document
        auditLogRule.check().containsOrdered(d -> d.contains(AuditedDocRefResourceImpl.CREATE_DOC_REF));
    }

    @Override
    protected SearchRequest getValidSearchRequest(final DocRef docRef,
                                                  final ExpressionOperator expressionOperator,
                                                  final OffsetRange offsetRange) {
        final String queryKey = UUID.randomUUID().toString();
        return new SearchRequest.Builder()
                .query(new Query.Builder()
                        .dataSource(docRef)
                        .expression(expressionOperator)
                        .build())
                .key(queryKey)
                .dateTimeLocale("en-gb")
                .incremental(true)
                .addResultRequests(new ResultRequest.Builder()
                        .fetch(ResultRequest.Fetch.ALL)
                        .resultStyle(ResultRequest.ResultStyle.FLAT)
                        .componentId("componentId")
                        .requestedRange(offsetRange)
                        .addMappings(new TableSettings.Builder()
                                .queryId(queryKey)
                                .extractValues(false)
                                .showDetail(false)
                                .addFields(new Field.Builder()
                                        .name(TestQueryableJooqEntity.COLOUR)
                                        .expression("${" + TestQueryableJooqEntity.COLOUR + "}")
                                        .build())
                                //.addMaxResults(10)
                                .build())
                        .build())
                .build();
    }

    @Override
    protected void assertValidDataSource(final DataSource dataSource) {
        final Set<String> resultFieldNames = dataSource.getFields().stream()
                .map(DataSourceField::getName)
                .collect(Collectors.toSet());

        assertTrue(resultFieldNames.contains(DocRefEntity.CREATE_TIME));
        assertTrue(resultFieldNames.contains(DocRefEntity.CREATE_USER));
        assertTrue(resultFieldNames.contains(DocRefEntity.UPDATE_TIME));
        assertTrue(resultFieldNames.contains(DocRefEntity.UPDATE_USER));
        assertTrue(resultFieldNames.contains(TestQueryableJooqEntity.ID));
        assertTrue(resultFieldNames.contains(TestQueryableJooqEntity.COLOUR));
    }

    @Override
    protected TestDocRefJooqEntity getValidEntity(final DocRef docRef) {
        return new TestDocRefJooqEntity.Builder()
                .docRef(docRef)
                .planetName("TestPlanet")
                .build();
    }

    @Test
    public void testQuerySearch() {

        // Precalculate the page offsets
        int numberPages = 10;
        int pageSize = CreateTestDataJooqImpl.RECORDS_TO_CREATE / numberPages;
        final Collection<OffsetRange> pageOffsets = IntStream.range(0, numberPages)
                .mapToObj(value -> new OffsetRange.Builder()
                        .length((long) pageSize)
                        .offset((long) (value * pageSize))
                        .build())
                .collect(Collectors.toList());

        final ExpressionOperator expressionOperator = new ExpressionOperator.Builder(ExpressionOperator.Op.OR)
                .addTerm(TestQueryableJooqEntity.COLOUR, ExpressionTerm.Condition.CONTAINS, this.testDataSeed)
                .build();
        final Set<String> resultsSet = new HashSet<>();

        pageOffsets.forEach(offsetRange -> {
            try {
                final SearchRequest request = getValidSearchRequest(testDataDocRef, expressionOperator, offsetRange);

                final SearchResponse searchResponse = queryClient.search(authRule.adminUser(), request)
                        .orElseThrow(() -> new AssertionError("Response body missing"));

                for (final Result result : searchResponse.getResults()) {
                    assertTrue(result instanceof FlatResult);

                    final FlatResult flatResult = (FlatResult) result;
                    flatResult.getValues().stream()
                            .map(objects -> objects.get(3)) // skip over :ParentKey, :Key, :Depth
                            .map(Object::toString)
                            .forEach(resultsSet::add);
                }
            } catch (final RuntimeException | QueryApiException e) {
                fail(e.getLocalizedMessage());
            }
        });

        assertEquals(CreateTestDataJooqImpl.RECORDS_TO_CREATE, resultsSet.size());
    }
}