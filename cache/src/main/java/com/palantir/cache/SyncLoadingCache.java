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

import java.util.concurrent.CompletionException;
import org.jspecify.annotations.Nullable;

/**
 * A cache that loads values synchronously and stores values directly in the cache. The cache loads values using a
 * default cache loader if a query does not provide a mapping function.
 *
 * For many workloads, {@link AsyncLoadingCache} should be prefered to {@link SyncLoadingCache} because it avoids
 * contention on independent keys by not holding cache locks while loading values. {@link SyncLoadingCache} should be
 * used when the cache loader completes quickly without blocking (ie. does not perform I/O) and you cannot afford to
 * wrap every cache value in a {@link java.util.concurrent.Future} (due to memory constraints).
 *
 * @param <K> the type of keys maintained by this cache
 * @param <V> the type of mapped values.
 */
public interface SyncLoadingCache<K, V extends @Nullable Object> extends SyncCache<K, V> {

    /**
     * Returns the value associated with the {@code key} in this cache, obtaining that value from
     * {@link CacheLoader#load(Object)} if necessary.
     * <p>
     * If another call to {@link #get} is currently loading the value for the {@code key}, this thread simply waits for
     * that thread to finish and returns its loaded value. Note that multiple threads can concurrently load values for
     * distinct keys.
     * <p>
     * If the specified key is not already associated with a value, attempts to compute its value and enters it into
     * this cache unless {@code null}. The entire method invocation is performed atomically, so the function is applied
     * at most once per key. Some attempted update operations on this cache by other threads may be blocked while the
     * computation is in progress, so the computation should be short and simple, and must not attempt to update any
     * other mappings of this cache.
     *
     * @param key the key with which the specified value is to be associated
     * @return the current (existing or computed) value associated with the specified key, or null if the computed value
     *         is null
     * @throws NullPointerException if the specified key is null
     * @throws IllegalStateException if the computation detectably attempts a recursive update to this cache that would
     *         otherwise never complete
     * @throws CompletionException if a checked exception was thrown while loading the value
     * @throws RuntimeException or Error if the {@link CacheLoader} does so, in which case the mapping is left
     *         unestablished
     */
    V get(K key);
}
