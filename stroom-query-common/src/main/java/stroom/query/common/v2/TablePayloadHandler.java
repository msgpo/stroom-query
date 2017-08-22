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
import stroom.mapreduce.v2.PairQueue;
import stroom.mapreduce.v2.UnsafePairQueue;
import stroom.query.api.v2.Field;
import stroom.util.shared.HasTerminate;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class TablePayloadHandler implements PayloadHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(TablePayloadHandler.class);

    private final CompiledSorter compiledSorter;
    private final CompiledDepths compiledDepths;
    private final MaxResults maxResults;
    private final StoreSize storeSize;
    private final AtomicLong totalResults = new AtomicLong();
    private final LinkedBlockingQueue<UnsafePairQueue<Key, Item>> pendingMerges = new LinkedBlockingQueue<>();
    private final AtomicBoolean merging = new AtomicBoolean();

    private volatile PairQueue<Key, Item> currentQueue;
    private volatile Data data;

    public TablePayloadHandler(final List<Field> fields,
                               final boolean showDetails,
                               final MaxResults maxResults,
                               final StoreSize storeSize) {
        this.compiledSorter = new CompiledSorter(fields);
        this.maxResults = maxResults;
        this.storeSize = storeSize;
        this.compiledDepths = new CompiledDepths(fields, showDetails);
    }

    void clear() {
        totalResults.set(0);
        pendingMerges.clear();
        merging.set(false);
        currentQueue = null;
        data = null;
    }

    void addQueue(final UnsafePairQueue<Key, Item> newQueue, final HasTerminate hasTerminate) {
        if (newQueue != null) {
            if (hasTerminate.isTerminated()) {
                // Clear the queue if we should terminate.
                pendingMerges.clear();

            } else {
                // Add the new queue to the pending merge queue ready for
                // merging.
                try {
                    pendingMerges.put(newQueue);
                } catch (final InterruptedException | RuntimeException e) {
                    LOGGER.error(e.getMessage(), e);
                }

                // Try and merge all of the items on the pending merge queue.
                mergePending(hasTerminate);
            }
        }
    }

    private void mergePending(final HasTerminate hasTerminate) {
        // Only 1 thread will get to do a merge.
        if (merging.compareAndSet(false, true)) {
            try {
                if (hasTerminate.isTerminated()) {
                    // Clear the queue if we should terminate.
                    pendingMerges.clear();

                } else {
                    UnsafePairQueue<Key, Item> queue = pendingMerges.poll();
                    while (queue != null) {
                        try {
                            mergeQueue(queue);
                        } catch (final RuntimeException e) {
                            LOGGER.error(e.getMessage(), e);
                            throw e;
                        }

                        if (hasTerminate.isTerminated()) {
                            // Clear the queue if we should terminate.
                            pendingMerges.clear();
                        }

                        queue = pendingMerges.poll();
                    }
                }
            } finally {
                merging.set(false);
            }

            if (hasTerminate.isTerminated()) {
                // Clear the queue if we should terminate.
                pendingMerges.clear();
            }

            // Make sure we don't fail to merge items from the queue that have
            // just been added by another thread that didn't get to do the
            // merge.
            if (pendingMerges.peek() != null) {
                mergePending(hasTerminate);
            }
        }
    }

    private void mergeQueue(final UnsafePairQueue<Key, Item> newQueue) {
        /*
         * Update the total number of results that we have received.
         */
        totalResults.getAndAdd(newQueue.size());

        if (currentQueue == null) {
            currentQueue = updateResultStore(newQueue);

        } else {
            final PairQueue<Key, Item> outputQueue = new UnsafePairQueue<>();

            /*
             * Create a partitioner to perform result reduction if needed.
             */
            final ItemPartitioner partitioner = new ItemPartitioner(compiledDepths.getDepths(),
                    compiledDepths.getMaxDepth());
            partitioner.setOutputCollector(outputQueue);

            /* First deal with the current queue. */
            partitioner.read(currentQueue);

            /* New deal with the new queue. */
            partitioner.read(newQueue);

            /* Perform partitioning and reduction. */
            partitioner.partition();

            currentQueue = updateResultStore(outputQueue);
        }
    }

    private PairQueue<Key, Item> updateResultStore(final PairQueue<Key, Item> queue) {
        // Stick the new reduced results into a new result store.
        final ResultStoreCreator resultStoreCreator = new ResultStoreCreator(compiledSorter);
        resultStoreCreator.read(queue);

        // Trim the number of results in the store.
        resultStoreCreator.trim(storeSize);

        // Put the remaining items into the current queue ready for the next
        // result.
        final PairQueue<Key, Item> remaining = new UnsafePairQueue<>();
        long size = 0;
        for (final Items<Item> items : resultStoreCreator.getChildMap().values()) {
            for (final Item item : items) {
                remaining.collect(item.key, item);
                size++;
            }
        }

        // Update the result store reference to point at this new store.
        this.data = resultStoreCreator.create(size, totalResults.get());

        // Give back the remaining queue items ready for the next result.
        return remaining;
    }

    @Override
    public boolean shouldTerminateSearch() {
        if (!compiledSorter.hasSort() && !compiledDepths.hasGroupBy()) {
            //No sorting or grouping so we can stop the search as soon as we have the number
            //of results requested by the client
            if (data != null &&
                    maxResults != null &&
                    data.getTotalSize() >= maxResults.size(0)) {
                return true;
            }
        }
        return false;
    }

    public Data getData() {
        return data;
    }

}