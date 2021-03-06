package stroom.query.testing.memory.app;

import stroom.datasource.api.v2.DataSource;
import stroom.datasource.api.v2.TextField;
import stroom.docref.DocRef;
import stroom.query.api.v2.QueryKey;
import stroom.query.api.v2.SearchRequest;
import stroom.query.api.v2.SearchResponse;
import stroom.query.audit.service.DocRefService;
import stroom.query.audit.service.QueryApiException;
import stroom.query.audit.service.QueryService;
import stroom.query.security.ServiceUser;

import javax.inject.Inject;
import java.util.Optional;

public class TestQueryServiceImpl implements QueryService {
    private final DocRefService<TestDocRefEntity> docRefService;

    @SuppressWarnings("unchecked")
    @Inject
    public TestQueryServiceImpl(final DocRefService docRefService) {
        this.docRefService = (DocRefService<TestDocRefEntity>) docRefService;
    }

    @Override
    public String getType() {
        return docRefService.getType();
    }

    @Override
    public Optional<DataSource> getDataSource(final ServiceUser user,
                                              final DocRef docRef) throws QueryApiException {
        final Optional<TestDocRefEntity> docRefEntity = docRefService.get(user, docRef.getUuid());

        if (!docRefEntity.isPresent()) {
            return Optional.empty();
        }

        return Optional.of(new DataSource.Builder()
                .addFields(new TextField(TestDocRefEntity.INDEX_NAME))
                .build());
    }

    @Override
    public Optional<SearchResponse> search(final ServiceUser user,
                                           final SearchRequest request) throws QueryApiException {
        final String dataSourceUuid = request.getQuery().getDataSource().getUuid();

        final Optional<TestDocRefEntity> docRefEntity = docRefService.get(user, dataSourceUuid);

        if (!docRefEntity.isPresent()) {
            return Optional.empty();
        }

        return Optional.of(new SearchResponse.TableResultBuilder()
                // Yeah whatever...
                .build());
    }

    @Override
    public Boolean destroy(final ServiceUser user,
                           final QueryKey queryKey) {
        return Boolean.TRUE;
    }

    @Override
    public Optional<DocRef> getDocRefForQueryKey(final ServiceUser user,
                                                 final QueryKey queryKey) {
        return Optional.empty();
    }
}
