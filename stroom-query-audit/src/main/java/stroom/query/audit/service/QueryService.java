package stroom.query.audit.service;

import stroom.datasource.api.v2.DataSource;
import stroom.query.api.v2.DocRef;
import stroom.query.api.v2.QueryKey;
import stroom.query.api.v2.SearchRequest;
import stroom.query.api.v2.SearchResponse;
import stroom.query.audit.security.ServiceUser;

import java.util.Optional;

public interface QueryService {
    /**
     * Get the details of the DataSource given by the DocRef.
     * Used to build user interfaces for querying the specific data.
     *
     * @param user   The authenticated user
     * @param docRef The Doc Ref of the DataSource to fetch
     * @return The DataSource definition for the given doc ref
     */
    Optional<DataSource> getDataSource(ServiceUser user,
                                       DocRef docRef);

    /**
     * Conduct a search on the data, it may be a successive call for long running searches.
     *
     * @param user    The authenticated user
     * @param request The details of the search
     */
    Optional<SearchResponse> search(ServiceUser user,
                                    SearchRequest request);

    /**
     * Destroy any existing query being conducted under the given key.
     *
     * @param user     The authenticated user
     * @param queryKey The query key that was given with the search request.
     * @return Success indicator
     */
    Boolean destroy(ServiceUser user,
                    QueryKey queryKey);

    /**
     * Used by REST layer to retrieve the doc ref for a given query key.
     * Primarily used to check the permissions for the given doc ref.
     *
     * @param user     The authenticated user
     * @param queryKey The query key, it should match a current query
     * @return The DocRef of the query, if found, if not found the result will be empty.
     */
    Optional<DocRef> getDocRefForQueryKey(ServiceUser user,
                                          QueryKey queryKey);
}
