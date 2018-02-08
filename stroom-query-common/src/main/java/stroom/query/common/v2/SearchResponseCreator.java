/*
 * Copyright 2017 Crown Copyright
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package stroom.query.common.v2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.MessageFormatter;
import stroom.query.api.v2.Result;
import stroom.query.api.v2.ResultRequest;
import stroom.query.api.v2.ResultRequest.Fetch;
import stroom.query.api.v2.ResultRequest.ResultStyle;
import stroom.query.api.v2.SearchRequest;
import stroom.query.api.v2.SearchResponse;
import stroom.query.api.v2.TableResult;
import stroom.query.common.v2.format.FieldFormatter;
import stroom.query.common.v2.format.FormatterFactory;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

//TODO copy SearchResultCreatorManager from stroom into here for a common solution to
//caching the response creators. Will require an abstraction of a cache and a cache manager

public class SearchResponseCreator {
    private static final Logger LOGGER = LoggerFactory.getLogger(SearchResponseCreator.class);

    private static final Duration FALL_BACK_DEFAULT_TIMEOUT = Duration.ofMinutes(5);

    private final Store store;
    private final Duration defaultTimeout;

    private final Map<String, ResultCreator> cachedResultCreators = new HashMap<>();

    // Cache the last results for each component.
    private final Map<String, Result> resultCache = new HashMap<>();

    //TODO add arg for default timeout duration as the service has to supply this
    public SearchResponseCreator(final Store store) {

        this.store = Objects.requireNonNull(store);
        this.defaultTimeout = FALL_BACK_DEFAULT_TIMEOUT;
    }

    public SearchResponseCreator(final Store store, final Duration defaultTimeout) {

        this.store = Objects.requireNonNull(store);
        this.defaultTimeout = Objects.requireNonNull(defaultTimeout);
    }

    public SearchResponse create(final SearchRequest searchRequest) {
        final boolean isComplete = store.isComplete();

        //TODO determine effective timeout from request, default timeout and incremental=true/false
        Duration effectiveTimeout = getEffectiveTimeout(searchRequest);

        final CountDownLatch storeCompletionLatch = new CountDownLatch(1);

        store.registerCompletionListener(storeCompletionLatch::countDown);

        final boolean didComplete;
        try {
            //wait for the store to notify us of its completion, or timeout
            didComplete = storeCompletionLatch.await(effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.debug("Thread {} interrupted", Thread.currentThread().getName(), e);
            return createErrorResponse(
                    store, "Thread was interrupted before the search could complete");
        }

        if (didComplete || searchRequest.incremental()) {
            //either it completed or this is an incremental search so just return what we have
            //regardless of state
            List<Result> results = getResults(searchRequest, isComplete);

            if (results.size() == 0) {
                results = null;
            }
            return new SearchResponse(store.getHighlights(), results, store.getErrors(), isComplete);

        } else {
            //search didn't complete in time so return a timed out response
            createErrorResponse(
                    store,
                    MessageFormatter.format("The search timed out after {}", effectiveTimeout.toString()));

        }

        //TODO refactor to use ConditionalWait

    }

    private SearchResponse createErrorResponse(final Store store, String errorMessage) {
        Objects.requireNonNull(store);
        Objects.requireNonNull(errorMessage);
        List<String> errors = new ArrayList<>();
        errors.add(errorMessage);
        errors.addAll(store.getErrors());
        return new SearchResponse(
                null,
                null,
                errors,
                false);
    }

    private Duration getEffectiveTimeout(final SearchRequest searchRequest) {
        Duration requestedTimeout = searchRequest.getTimeout() == null
                ? null
                : Duration.ofMillis(searchRequest.getTimeout());
        if (requestedTimeout != null) {
            return requestedTimeout;
        } else {
            if (searchRequest.incremental()) {
                //no timeout supplied so they want a response immediately
                return Duration.ZERO;
            }else {
                //this is synchronous so just use the service's default
                return defaultTimeout;
            }
        }
    }

    private List<Result> getResults(final SearchRequest searchRequest, final boolean complete) {

        // Provide results if this search is incremental or the search is complete.
        List<Result> results = new ArrayList<>(searchRequest.getResultRequests().size());
        //TODO move this conditon outside this method
        if (searchRequest.incremental() || complete) {
            // Copy the requested portion of the result cache into the result.
            for (final ResultRequest resultRequest : searchRequest.getResultRequests()) {
                final String componentId = resultRequest.getComponentId();

                // Only deliver data to components that actually want it.
                final Fetch fetch = resultRequest.getFetch();
                if (!Fetch.NONE.equals(fetch)) {
                    Result result = null;

                    final Data data = store.getData(componentId);
                    if (data != null) {
                        try {
                            final ResultCreator resultCreator = getResultCreator(componentId,
                                    resultRequest, searchRequest.getDateTimeLocale());
                            if (resultCreator != null) {
                                result = resultCreator.create(data, resultRequest);
                            }
                        } catch (final Exception e) {
                            result = new TableResult(componentId, null, null, null, e.getMessage());
                        }
                    }

                    if (result != null) {
                        if (fetch == null || Fetch.ALL.equals(fetch)) {
                            // If the fetch option has not been set or is set to ALL we deliver the full result.
                            results.add(result);
                            LOGGER.info("Delivering " + result + " for " + componentId);

                        } else if (Fetch.CHANGES.equals(fetch)) {
                            // Cache the new result and get the previous one.
                            final Result lastResult = resultCache.put(componentId, result);

                            // See if we have delivered an identical result before so we
                            // don't send more data to the client than we need to.
                            if (!result.equals(lastResult)) {
                                //
                                // CODE TO HELP DEBUGGING.
                                //

                                // try {
                                // if (lastComponentResult instanceof
                                // ChartResult) {
                                // final ChartResult lr = (ChartResult)
                                // lastComponentResult;
                                // final ChartResult cr = (ChartResult)
                                // componentResult;
                                // final File dir = new
                                // File(FileUtil.getTempDir());
                                // StreamUtil.stringToFile(lr.getJSON(), new
                                // File(dir, "last.json"));
                                // StreamUtil.stringToFile(cr.getJSON(), new
                                // File(dir, "current.json"));
                                // }
                                // } catch (final Exception e) {
                                // LOGGER.error(e.getMessage(), e);
                                // }

                                // Either we haven't returned a result before or this result
                                // is different from the one delivered previously so deliver it to the client.
                                results.add(result);
                                LOGGER.info("Delivering " + result + " for " + componentId);
                            }
                        }
                    }
                }
            }
        }
        return results;
    }

    private ResultCreator getResultCreator(final String componentId,
                                           final ResultRequest resultRequest,
                                           final String dateTimeLocale) {
        if (cachedResultCreators.containsKey(componentId)) {
            return cachedResultCreators.get(componentId);
        }

        ResultCreator resultCreator = null;
        try {
            if (ResultStyle.TABLE.equals(resultRequest.getResultStyle())) {
                final FieldFormatter fieldFormatter = new FieldFormatter(new FormatterFactory(dateTimeLocale));
                resultCreator = new TableResultCreator(fieldFormatter, store.getDefaultMaxResultsSizes());
            } else {
                resultCreator = new FlatResultCreator(
                        resultRequest,
                        null,
                        null,
                        store.getDefaultMaxResultsSizes(),
                        store.getStoreSize());
            }
        } catch (final Exception e) {
            throw new RuntimeException(e.getMessage());
        } finally {
            cachedResultCreators.put(componentId, resultCreator);
        }

        return resultCreator;
    }

    public void destroy() {
        store.destroy();
    }
}