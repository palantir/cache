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
 * Computes or retrieves values, based on a key, for use in populating a cache.
 *
 * @param <K> the type of keys
 * @param <V> the type of values
 */
public interface CacheLoader<K, V extends @Nullable Object> {

    /**
     * Computes or retrieves the value corresponding to {@code key}.
     * <p>
     * <b>Warning:</b> loading <b>must not</b> attempt to update any mappings of this cache directly.
     *
     * @param key the non-null key whose value should be loaded
     * @return the value associated with {@code key} or {@code null} if not found
     */
    V load(K key);
}
