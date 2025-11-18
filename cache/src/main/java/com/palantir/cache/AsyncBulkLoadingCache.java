/*
 * (c) Copyright 2025 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.cache;

import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Future;

/**
 * A cache that loads values asynchronousky using the provided executor and stores {@link Future} wrapped values in the
 * cache.The cache loads values using a default cache loader that supports bulk loads a query does not provide a
 * mapping function.
 *
 * @param <K> the type of keys maintained by this cache
 * @param <V> the type of mapped values.
 */
public interface AsyncBulkLoadingCache<K, V> extends AsyncLoadingCache<K, V> {

    /**
     * Returns a map of the values associated with the {@code keys}, creating or retrieving those values if necessary.
     * The returned map contains entries that were already cached, combined with the newly loaded entries; it will never
     * contain null keys or values.
     * <p>
     * Caches loaded by a {@link CacheLoader} will issue a single request to {@link BulkCacheLoader#loadAll} for all
     * keys which are not already present in the cache. All entries returned by {@link BulkCacheLoader#loadAll} will be
     * stored in the cache, overwriting any previously cached values. If another call to {@link #get} tries to load the
     * value for key in {@code keys}, implementations may either have that thread load the entry or simply wait for this
     * thread to finish and return the loaded value. In the case of overlapping non-blocking loads, the last load to
     * complete will replace the existing entry. Note that multiple threads can concurrently load values for distinct
     * keys.
     * <p>
     * Note that duplicate elements in {@code keys}, as determined by {@link Object#equals}, will be ignored.
     *
     * @param keys the keys whose associated values are to be returned
     * @return an unmodifiable mapping of keys to values for the specified keys in this cache
     * @throws NullPointerException if the specified collection is null or contains a null element
     * @throws CompletionException if a checked exception was thrown while loading the value
     * @throws RuntimeException or Error if the {@link CacheLoader} does so, if {@link BulkCacheLoader#loadAll} returns
     *         {@code null}, or returns a map containing null keys or values. In all cases, the mapping is left
     *         unestablished.
     */
    Map<K, V> getAll(Iterable<? extends K> keys);
}
