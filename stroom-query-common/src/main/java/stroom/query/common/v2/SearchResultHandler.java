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
import stroom.mapreduce.v2.UnsafePairQueue;
import stroom.query.api.v2.TableSettings;
import stroom.query.common.v2.CoprocessorSettingsMap.CoprocessorKey;
import stroom.query.util.LambdaLogger;
import stroom.query.util.LambdaLoggerFactory;

import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

public class SearchResultHandler implements ResultHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SearchResultHandler.class);
    private static final LambdaLogger LAMBDA_LOGGER = LambdaLoggerFactory.getLogger(SearchResultHandler.class);

    private final CoprocessorSettingsMap coprocessorSettingsMap;
    private final CompletionState completionState;
    private final Map<CoprocessorKey, TablePayloadHandler> handlerMap;

    public SearchResultHandler(final CompletionState completionState,
                               final CoprocessorSettingsMap coprocessorSettingsMap,
                               final Sizes defaultMaxResultsSizes,
                               final Sizes storeSize) {

        this.completionState = completionState;
        this.coprocessorSettingsMap = coprocessorSettingsMap;
        this.handlerMap = coprocessorSettingsMap.getMap().entrySet().stream()
                .filter(entry -> entry.getValue() instanceof TableCoprocessorSettings)
                .collect(Collectors.toMap(Entry::getKey, entry -> {
                    final TableCoprocessorSettings tableCoprocessorSettings = (TableCoprocessorSettings) entry.getValue();
                    final TableSettings tableSettings = tableCoprocessorSettings.getTableSettings();
                    // Create a set of sizes that are the minimum values for the combination of user provided sizes for the table and the default maximum sizes.
                    final Sizes maxResults = Sizes.min(Sizes.create(tableSettings.getMaxResults()), defaultMaxResultsSizes);
                    return new TablePayloadHandler(tableSettings.getFields(), tableSettings.showDetail(), maxResults, storeSize);
                }));
    }

    @Override
    public void handle(final Map<CoprocessorKey, Payload> payloadMap) {
        if (payloadMap != null) {
            for (final Entry<CoprocessorKey, Payload> entry : payloadMap.entrySet()) {
                final Payload payload = entry.getValue();
                if (payload instanceof TablePayload) {
                    final TablePayload tablePayload = (TablePayload) payload;

                    final TablePayloadHandler payloadHandler = handlerMap.get(entry.getKey());
                    final UnsafePairQueue<GroupKey, Item> newQueue = tablePayload.getQueue();
                    if (newQueue != null) {
                        payloadHandler.addQueue(newQueue);
                    }
                }
            }
        }

        // See if we should terminate.
        boolean terminate = true;
        for (final PayloadHandler payloadHandler : handlerMap.values()) {
            if (!payloadHandler.shouldTerminateSearch()) {
                terminate = false;
                break;
            }
        }
        if (terminate) {
            completionState.complete();
        }
    }

    private TablePayloadHandler getPayloadHandler(final String componentId) {
        final CoprocessorKey coprocessorKey = coprocessorSettingsMap.getCoprocessorKey(componentId);
        if (coprocessorKey == null) {
            return null;
        }

        return handlerMap.get(coprocessorKey);
    }

    @Override
    public Data getResultStore(final String componentId) {
        final TablePayloadHandler tablePayloadHandler = getPayloadHandler(componentId);
        if (tablePayloadHandler != null) {
            return tablePayloadHandler.getData();
        }
        return null;
    }

    @Override
    public void waitForPendingWork() throws InterruptedException {
        // wait for each handler to complete any outstanding work
        for (final TablePayloadHandler handler : handlerMap.values()) {
            LOGGER.trace("About to wait for handler {}", handler);
            handler.waitForPendingWork();
        }
    }
}
