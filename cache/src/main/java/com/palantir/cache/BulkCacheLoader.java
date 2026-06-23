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
import java.util.Set;
import org.jspecify.annotations.Nullable;

public interface BulkCacheLoader<K, V extends @Nullable Object> extends CacheLoader<K, V> {

    @Override
    /*
     * If loadAll() may ever return partial results, then calls to load() may return null. In that case, the type of
     * this cache loader should be declared as CacheLoader<Foo, @Nullable Bar> rather than CacheLoader<Foo, Bar>.
     */
    @SuppressWarnings("NullAway")
    default V load(K key) {
        return loadAll(Set.of(key)).get(key);
    }

    /**
     * Computes or retrieves the values corresponding to {@code keys}.
     * <p>
     * If the returned map doesn't contain all requested {@code keys}, then the entries it does
     * contain will be cached, and {@code getAll} will return the partial results. If the returned map
     * contains extra keys not present in {@code keys} then all returned entries will be cached, but
     * only the entries for {@code keys}, will be returned from {@code getAll}.
     * <p>
     * <b>Warning:</b> loading <b>must not</b> attempt to update any mappings of this cache directly.
     *
     * @param keys the unique, non-null keys whose values should be loaded
     * @return a map from each key in {@code keys} to the value associated with that key; <b>may not contain null
     *         values</b>
     */
    Map<K, V> loadAll(Set<K> keys);
}
