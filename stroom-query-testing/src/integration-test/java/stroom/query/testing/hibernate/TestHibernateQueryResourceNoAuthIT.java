package stroom.query.testing.hibernate;

import org.junit.ClassRule;
import stroom.datasource.api.v2.DataSource;
import stroom.datasource.api.v2.DataSourceField;
import stroom.query.api.v2.DocRef;
import stroom.query.api.v2.ExpressionOperator;
import stroom.query.api.v2.Field;
import stroom.query.api.v2.OffsetRange;
import stroom.query.api.v2.Query;
import stroom.query.api.v2.ResultRequest;
import stroom.query.api.v2.SearchRequest;
import stroom.query.api.v2.TableSettings;
import stroom.query.audit.model.DocRefEntity;
import stroom.query.testing.DropwizardAppWithClientsRule;
import stroom.query.testing.QueryResourceNoAuthIT;
import stroom.query.testing.generic.app.TestQueryServiceImpl;
import stroom.query.testing.hibernate.app.HibernateApp;
import stroom.query.testing.hibernate.app.HibernateConfig;
import stroom.query.testing.hibernate.app.TestDocRefHibernateEntity;
import stroom.query.testing.hibernate.app.TestQueryableEntity;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static io.dropwizard.testing.ResourceHelpers.resourceFilePath;
import static org.junit.Assert.assertTrue;

public class TestHibernateQueryResourceNoAuthIT extends QueryResourceNoAuthIT<TestDocRefHibernateEntity, HibernateConfig> {

    @ClassRule
    public static final DropwizardAppWithClientsRule<HibernateConfig> appRule =
            new DropwizardAppWithClientsRule<>(HibernateApp.class, resourceFilePath("hibernate_noauth/config.yml"));

    public TestHibernateQueryResourceNoAuthIT() {
        super(TestDocRefHibernateEntity.class,
                TestDocRefHibernateEntity.TYPE,
                appRule);
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
                                        .name(TestQueryableEntity.FLAVOUR)
                                        .expression("${" + TestQueryableEntity.FLAVOUR + "}")
                                        .build())
                                .addMaxResults(10)
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
        assertTrue(resultFieldNames.contains(TestQueryableEntity.ID));
        assertTrue(resultFieldNames.contains(TestQueryableEntity.FLAVOUR));
    }

    @Override
    protected TestDocRefHibernateEntity getValidEntity(final DocRef docRef) {
        return new TestDocRefHibernateEntity.Builder()
                .docRef(docRef)
                .indexName(TestQueryServiceImpl.VALID_INDEX_NAME)
                .build();
    }
}
