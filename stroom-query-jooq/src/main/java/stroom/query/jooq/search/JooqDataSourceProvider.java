package stroom.query.jooq.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import stroom.datasource.api.v2.DataSourceField;
import stroom.query.audit.model.IsDataSourceField;
import stroom.query.audit.model.QueryableEntity;
import stroom.query.jooq.QueryableJooqEntity;

import javax.inject.Inject;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class JooqDataSourceProvider {
    private static final Logger LOGGER = LoggerFactory.getLogger(JooqDataSourceProvider.class);

    private final List<DataSourceField> fields;

    @Inject
    public JooqDataSourceProvider(final QueryableEntity.ClassProvider dtoClassProvider) {

        final Class<? extends QueryableJooqEntity> dtoClass = dtoClassProvider.get();

        this.fields = QueryableEntity.getFields(dtoClass)
                .map(IsDataSourceField::fieldSupplier)
                .map(aClass -> {
                    try {
                        return aClass.newInstance();
                    } catch (InstantiationException | IllegalAccessException e) {
                        LOGGER.warn("Could not create instance of DataSourceField supplier with " + aClass.getName());
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .map(Supplier::get)
                .collect(Collectors.toList());
    }

    public List<DataSourceField> getFields() {
        return fields;
    }
}