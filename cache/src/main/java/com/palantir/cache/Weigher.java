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

import org.jspecify.annotations.Nullable;

/**
 * Calculates the weights of cache entries. The total weight threshold is used to determine when an
 * eviction is required.
 *
 * @param <K> the type of keys
 * @param <V> the type of values
 */
public interface Weigher<K, V extends @Nullable Object> {

    /**
     * Returns the weight of a cache entry. There is no unit for entry weights; rather they are simply
     * relative to each other.
     *
     * @param key the key to weigh
     * @param value the value to weigh
     * @return the weight of the entry; must be non-negative
     */
    int weigh(K key, V value);

    /**
     * Returns a weigher where an entry has a weight of {@code 1}.
     *
     * @param <K> the type of keys
     * @param <V> the type of values
     * @return a weigher where an entry has a weight of {@code 1}
     */
    @SuppressWarnings("unchecked")
    static <K, V extends @Nullable Object> Weigher<K, V> singleton() {
        return (Weigher<K, V>) SingletonWeigher.INSTANCE;
    }
}
