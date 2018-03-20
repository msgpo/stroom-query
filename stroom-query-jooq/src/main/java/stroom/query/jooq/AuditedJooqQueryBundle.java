package stroom.query.jooq;

import io.dropwizard.Configuration;
import io.dropwizard.setup.Environment;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import stroom.query.audit.authorisation.HasAuthorisationConfig;
import stroom.query.audit.model.IsDataSourceField;
import stroom.query.audit.model.QueryableEntity;
import stroom.query.audit.security.HasTokenConfig;
import stroom.query.audit.service.DocRefService;

/**
 * This Dropwizard bundle can be used to build the entire Query Resource implementation stack when the data source is
 * an SQL database, storing one annotated data type. The annotated data type will need to also annotate it's fields with
 * {@link IsDataSourceField}
 * @param <CONFIG> The configuration class
 * @param <QUERY_POJO> The annotated class.
 * @param <DOC_REF_POJO> POJO class for the Document
 * @param <DOC_REF_SERVICE> Implementation class for the DocRef Service
 */
public class AuditedJooqQueryBundle<CONFIG extends Configuration & HasTokenConfig & HasAuthorisationConfig & HasDataSourceFactory & HasFlywayFactory & HasJooqFactory,
                DOC_REF_SERVICE extends DocRefService<DOC_REF_POJO>,
                DOC_REF_POJO extends DocRefJooqEntity,
                QUERY_POJO extends QueryableJooqEntity>
        extends AuditedJooqDocRefBundle<CONFIG,
                DOC_REF_SERVICE,
                DOC_REF_POJO,
                QueryServiceJooqImpl> {
    private final Class<QUERY_POJO> queryableEntityClass;

    public AuditedJooqQueryBundle(final Class<DOC_REF_SERVICE> docRefServiceClass,
                                  final Class<DOC_REF_POJO> docRefEntityClass,
                                  final Class<QUERY_POJO> queryableEntityClass) {
        super(docRefServiceClass, docRefEntityClass, QueryServiceJooqImpl.class);

        this.queryableEntityClass = queryableEntityClass;
    }

    @Override
    public void run(final CONFIG configuration,
                    final Environment environment) {
        super.run(configuration, environment);

        environment.jersey().register(new AbstractBinder() {
            @Override
            protected void configure() {
                bind(new QueryableEntity.ClassProvider<>(queryableEntityClass)).to(QueryableEntity.ClassProvider.class);
            }
        });
    }
}
