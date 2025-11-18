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

import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * A cache that loads values synchronously and stores values directly in the cache.
 *
 * For many workloads, {@link AsyncCache} should be prefered to {@link SyncCache} because it avoids contention on
 * independent keys by not holding cache locks while loading values. {@link SyncCache} should be used when the cache
 * loader completes quickly without blocking (ie. does not perform I/O) and you cannot afford to wrap every cache value
 * in a {@link java.util.concurrent.Future} (due to memory constraints).
 *
 * @param <K> the type of keys maintained by this cache
 * @param <V> the type of mapped values.
 */
public interface SyncCache<K, V> {

    /**
     * Returns the value associated with the {@code key} in this cache, or {@code null} if there is no cached value for
     * the {@code key}.
     *
     * @param key the key whose associated value is to be returned
     * @return the value to which the specified key is mapped, or {@code null} if this cache does not contain a mapping
     *         for the key
     * @throws NullPointerException if the specified key is null
     */
    @Nullable
    V getIfPresent(K key);

    /**
     * Returns the value associated with the {@code key} in this cache, obtaining that value from the
     * {@code mappingFunction} if necessary. This method provides a simple substitute for the conventional "if cached,
     * return; otherwise create, cache and return" pattern.
     * <p>
     * If the specified key is not already associated with a value, attempts to compute its value using the given
     * mapping function and enters it into this cache unless {@code null}. The entire method invocation is performed
     * atomically, so the function is applied at most once per key. Some attempted update operations on this cache by
     * other threads may be blocked while the computation is in progress, so the computation should be short and simple,
     * and must not attempt to update any other mappings of this cache.
     * <p>
     * <b>Warning:</b> as with {@link CacheLoader#load}, {@code mappingFunction} <b>must not</b>
     * attempt to update any other mappings of this cache.
     *
     * @param key the key with which the specified value is to be associated
     * @param mappingFunction the function to compute a value
     * @return the current (existing or computed) value associated with the specified key, or null if the computed value
     *         is null
     * @throws NullPointerException if the specified key or mappingFunction is null
     * @throws IllegalStateException if the computation detectably attempts a recursive update to this cache that would
     *         otherwise never complete
     * @throws RuntimeException or Error if the mappingFunction does so, in which case the mapping is left unestablished
     */
    @Nullable
    V get(K key, Function<? super K, ? extends V> mappingFunction);

    /**
     * Returns a map of the values associated with the {@code keys} in this cache. The returned map will only contain
     * entries which are already present in the cache.
     * <p>
     * Note that duplicate elements in {@code keys}, as determined by {@link Object#equals}, will be ignored.
     *
     * @param keys the keys whose associated values are to be returned
     * @return an unmodifiable mapping of keys to values for the specified keys found in this cache
     * @throws NullPointerException if the specified collection is null or contains a null element
     */
    Map<K, V> getAllPresent(Iterable<? extends K> keys);

    // This interface intentionally does not support getAll() because it is does not provide the desired linearizability
    // properties. The loads in getAll() are performed outside of the backing concurrent map locks and thus will not be
    // removed by a concurrent invalidate() request. This can lead to surprising behavior where outdated entries are
    // added to the cache after an invalidation has occurred.
    //
    // See https://github.com/ben-manes/caffeine/issues/1739#issuecomment-2200862708.

    /**
     * Associates the {@code value} with the {@code key} in this cache. If the cache previously contained a value
     * associated with the {@code key}, the old value is replaced by the new {@code value}.
     * <p>
     * Prefer {@link #get(Object, Function)} when using the conventional "if cached, return; otherwise create, cache and
     * return" pattern.
     *
     * @param key the key with which the specified value is to be associated
     * @param value value to be associated with the specified key
     * @throws NullPointerException if the specified key or value is null
     */
    void put(K key, V value);

    /**
     * Copies all of the mappings from the specified map to the cache. The effect of this call is equivalent to that of
     * calling {@code put(k, v)} on this map once for each mapping from key
     * {@code k} to value {@code v} in the specified map. The behavior of this operation is undefined if the specified
     * map is modified while the operation is in progress.
     *
     * @param map the mappings to be stored in this cache
     * @throws NullPointerException if the specified map is null or the specified map contains null keys or values
     */
    void putAll(Map<? extends K, ? extends V> map);

    /**
     * Discards any cached value for the {@code key}. This operation is guaranteed to invalidate an entry that is being
     * loaded.
     *
     * @param key the key whose mapping is to be removed from the cache
     * @throws NullPointerException if the specified key is null
     */
    void invalidate(K key);

    /**
     * Discards any cached values for the {@code keys}. This operation is guaranteed to invalidate an entry that is
     * being loaded.
     *
     * @param keys the keys whose associated values are to be removed
     * @throws NullPointerException if the specified collection is null or contains a null element
     */
    void invalidateAll(Iterable<? extends K> keys);

    // This interface intentionally does not support invalidateAll() because it is does not provide the desired
    // linearizability properties. The cache does not have visibility into in-flight computations (because the backing
    // concurrent map does not expose them) and thus cannot invalidate these in-flight computations. This can lead to
    // surprising behavior where outdated entries are added to the cache after an invalidation has occurred.
    //
    // See https://github.com/ben-manes/caffeine/issues/1739#issuecomment-2200862708.

    // This interface intentionally does not support asMap() because we do not want to support cache mutations outside
    // of the methods explicitly provide as part of this interface. We cannot guarantee the map implementation returned
    // by Caffeine has the desired behavior. For example, it is impractical to provide a sensible, performant
    // implementation of methods like Map.compute() for an async cache with in-flight computations.
    //
    // It is reasonable for callers to want to obtain snapshots of cache entries, so we provide this method to allow
    // callers to iterate over the realized cache entries. This method intentionally returns an Iterator to avoid
    // providing Collection methods which may have surprising behavior.
    Iterator<Entry<K, V>> entries();
}
