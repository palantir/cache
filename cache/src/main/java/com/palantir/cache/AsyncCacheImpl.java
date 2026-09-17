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

import static com.palantir.logsafe.Preconditions.checkState;

import com.google.common.collect.Iterators;
import com.google.errorprone.annotations.MustBeClosed;
import com.palantir.deadlines.CloseableDeadlineSuppression;
import com.palantir.deadlines.Deadlines;
import com.palantir.deadlines.Deadlines.Enforcement;
import com.palantir.logsafe.exceptions.SafeRuntimeException;
import com.palantir.tracing.Tracers;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

class AsyncCacheImpl<K, V> implements AsyncCache<K, V> {

    private final String loadOperation;
    private final com.github.benmanes.caffeine.cache.AsyncCache<K, V> cache;

    AsyncCacheImpl(String name, com.github.benmanes.caffeine.cache.AsyncCache<K, V> cache) {
        this.loadOperation = name + " cache load";
        this.cache = cache;
    }

    @Override
    @Nullable
    public final V getIfPresent(K key) {
        return cache.synchronous().getIfPresent(key);
    }

    @Override
    public final V get(K key, Function<? super K, ? extends V> mappingFunction) {
        CompletableFuture<V> future;
        try (Loader<K, V> loader = loader(mappingFunction)) {
            future = cache.get(key, loader);
        }

        return await(future);
    }

    @Override
    public final Map<K, V> getAllPresent(Iterable<? extends K> keys) {
        return cache.synchronous().getAllPresent(keys);
    }

    @Override
    public final Map<K, V> getAll(
            Iterable<? extends K> keys,
            Function<? super Set<? extends K>, ? extends Map<? extends K, ? extends V>> mappingFunction) {
        CompletableFuture<Map<K, V>> future;
        try (Loader<Set<? extends K>, Map<? extends K, ? extends V>> loader = loader(mappingFunction)) {
            future = cache.getAll(keys, loader);
        }

        return await(future);
    }

    @Override
    public final void put(K key, V value) {
        cache.synchronous().put(key, value);
    }

    @Override
    public final void putAll(Map<? extends K, ? extends V> map) {
        cache.synchronous().putAll(map);
    }

    @Override
    public final void invalidate(K key) {
        cache.synchronous().invalidate(key);
    }

    @Override
    public final void invalidateAll(Iterable<? extends K> keys) {
        cache.synchronous().invalidateAll(keys);
    }

    @Override
    public final void invalidateAll() {
        cache.synchronous().invalidateAll();
    }

    @Override
    public Iterator<Entry<K, V>> entries() {
        return Iterators.unmodifiableIterator(
                cache.synchronous().asMap().entrySet().iterator());
    }

    private static <T> T await(Future<T> future) {
        try {
            return getWithinDeadline(future);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                runtimeException.addSuppressed(new SafeRuntimeException("Cache load failed"));
                throw runtimeException;
            } else if (e.getCause() instanceof Error error) {
                error.addSuppressed(new SafeRuntimeException("Cache load failed"));
                throw error;
            } else {
                throw new SafeRuntimeException("Cache load failed", e.getCause());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SafeRuntimeException("Cache load interrupted", e);
        }
    }

    // Waits for the load this caller is sharing, but only for as long as this caller's own deadline allows. Waiting
    // for the full load would let a caller with a small budget be held up for as long as the largest budget among
    // the waiters, which is the budget the load itself is allowed to take.
    private static <T> T getWithinDeadline(Future<T> future) throws ExecutionException, InterruptedException {
        Optional<Duration> remaining = Deadlines.getRemainingDeadline();
        if (remaining.isEmpty()) {
            return future.get();
        }

        try {
            return future.get(remaining.get().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            // Throws if this caller's expired deadline is enforced. If it is not, the caller has not asked us to
            // abandon work on its behalf, so wait for the load to finish.
            Deadlines.checkDeadline(Enforcement.DEFER);
            return future.get();
        }
    }

    @MustBeClosed
    private <I, O> Loader<I, O> loader(Function<? super I, ? extends O> mappingFunction) {
        return new Loader<>(loadOperation, mappingFunction);
    }

    // This class exists to ensure that we do not start loading values until the entry is inserted into the cache.
    // This ensures that invalidateAll will remove entries that are being loaded.
    //
    // By default, Caffeine creates async cache entries with something like CompletableFuture.supplyAsync. If the load
    // completes and invalidateAll is called before the entry is inserted into the cache, then we may insert a stale
    // entry into the cache.
    private static final class Loader<I, O> implements BiFunction<I, Executor, CompletableFuture<O>>, AutoCloseable {

        private final String loadOperation;
        private final Function<? super I, ? extends O> mappingFunction;

        @Nullable
        private Runnable runnable;

        @MustBeClosed
        Loader(String loadOperation, Function<? super I, ? extends O> mappingFunction) {
            this.loadOperation = loadOperation;
            this.mappingFunction = mappingFunction;
        }

        @Override
        public CompletableFuture<O> apply(I key, Executor executor) {
            checkState(runnable == null);

            CompletableFuture<O> future = new CompletableFuture<>();

            runnable = () -> {
                try {
                    executor.execute(Tracers.wrap(loadOperation, () -> {
                        try {
                            future.complete(load(key));
                        } catch (Throwable t) {
                            future.completeExceptionally(t);
                        }
                    }));
                } catch (Throwable t) {
                    future.obtrudeException(t);
                }
            };

            return future;
        }

        // The load runs in the trace, and therefore under the deadline, of whichever caller happened to start it,
        // but every caller waiting on it shares the result. Hide that deadline so the caller with the smallest
        // remaining budget cannot fail a load the other waiters still have ample time for.
        private O load(I key) {
            try (CloseableDeadlineSuppression ignored = Deadlines.suppressDeadline()) {
                return mappingFunction.apply(key);
            }
        }

        @Override
        public void close() {
            if (runnable != null) {
                runnable.run();
            }
        }
    }
}
