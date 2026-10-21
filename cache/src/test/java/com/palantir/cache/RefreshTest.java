/*
 * (c) Copyright 2026 Palantir Technologies Inc. All rights reserved.
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

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class RefreshTest {

    private ExecutorService executor;

    @BeforeEach
    void before() {
        executor = Executors.newCachedThreadPool();
    }

    @AfterEach
    void after() {
        executor.shutdownNow();
    }

    @Test
    void sync() throws Exception {
        CountDownLatch getStartLatch = new CountDownLatch(1);
        CountDownLatch refreshStartLatch = new CountDownLatch(1);

        CountDownLatch finishLatch = new CountDownLatch(1);

        CacheLoader<String, Integer> cacheLoader = new CacheLoader<>() {

            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public @Nullable Integer load(String _key) throws Exception {
                int value = counter.incrementAndGet();

                switch (value) {
                    // get() load
                    case 1 -> getStartLatch.countDown();
                    // refresh() load
                    case 2 -> refreshStartLatch.countDown();
                    default -> throw new IllegalStateException();
                }

                finishLatch.await();

                return value;
            }
        };

        LoadingCache<String, Integer> cache =
                Caffeine.newBuilder().executor(executor).build(cacheLoader);

        // Get value
        Future<Integer> getFuture = executor.submit(() -> {
            return cache.get("key");
        });

        // Wait for the get() load to start
        assertThat(getStartLatch.await(1, TimeUnit.SECONDS)).isTrue();

        // Refresh value
        CompletableFuture<Integer> refreshFuture = cache.refresh("key");

        // Wait for the refresh() load to start
        assertThat(refreshStartLatch.await(1, TimeUnit.SECONDS)).isTrue();

        // Allow the loads to complete
        finishLatch.countDown();

        // get() returns the expected value
        assertThat(getFuture.get(1, TimeUnit.SECONDS)).isEqualTo(1);

        // It appears that refresh() returns the updated value, but...
        assertThat(refreshFuture.get(1, TimeUnit.SECONDS)).isEqualTo(2);

        // Cache ends up with value from the get() load, even though refresh() was triggered after
        // the get() load had started!
        //
        // The newer value from the refresh load is simply discarded. This is especially confusing given that the
        // refresh() future completed with the newer value.
        //
        // Unlike get(), refresh() cannot hold the lock for the duration of the cache load because that would preent
        // concurrent readers. So the refresh() happens out of band and is effectively a load + putCompareAndExchange.
        // If the cache value has been modified by the time the refresh completes, Caffeine has to decide whether to
        // keep this modified value or use the refresh computed value. Caffeine assumes that the modified value is more
        // current and discards the refresh computed value.
        assertThat(cache.getIfPresent("key")).isEqualTo(1);
    }

    @Test
    void async() throws Exception {
        CountDownLatch getStartLatch = new CountDownLatch(1);

        CountDownLatch finishLatch = new CountDownLatch(1);

        CacheLoader<String, Integer> cacheLoader = new CacheLoader<>() {

            private final AtomicInteger counter = new AtomicInteger();

            @Override
            public @Nullable Integer load(String _key) throws Exception {
                int value = counter.incrementAndGet();

                switch (value) {
                    // get() load
                    case 1 -> getStartLatch.countDown();
                    // refresh() will not trigger a new load
                    default -> throw new IllegalStateException();
                }

                finishLatch.await();

                return value;
            }
        };

        LoadingCache<String, Integer> cache =
                Caffeine.newBuilder().executor(executor).buildAsync(cacheLoader).synchronous();

        // Get value
        Future<Integer> getFuture = executor.submit(() -> {
            return cache.get("key");
        });

        // Wait for the get() load to start
        assertThat(getStartLatch.await(1, TimeUnit.SECONDS)).isTrue();

        // Refresh value
        CompletableFuture<Integer> refreshFuture = cache.refresh("key");

        // Allow the load to complete
        finishLatch.countDown();

        // get() returns the expected value
        assertThat(getFuture.get(1, TimeUnit.SECONDS)).isEqualTo(1);

        // It appears that refresh() didn't update the value...
        assertThat(refreshFuture.get(1, TimeUnit.SECONDS)).isEqualTo(1);

        // Cache ends up with value from the get() load, even though refresh() was triggered after
        // the get() load had started!
        //
        // This is because refresh() sees the in-flight load and returns the future for that in-flight load.
        //
        // When using refresh() with concurrent readers, you cannot guarantee that refresh() will result in
        // a cached value that is at least as current as the current value when refresh() was invoked.
        assertThat(cache.getIfPresent("key")).isEqualTo(1);
    }
}
