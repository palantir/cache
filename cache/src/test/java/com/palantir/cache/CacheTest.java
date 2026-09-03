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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.util.concurrent.Uninterruptibles;
import com.palantir.logsafe.exceptions.SafeIllegalArgumentException;
import com.palantir.logsafe.exceptions.SafeRuntimeException;
import com.palantir.tracing.Observability;
import com.palantir.tracing.Tracer;
import com.palantir.tracing.Tracers;
import com.palantir.tracing.api.OpenSpan;
import com.palantir.tracing.api.Span;
import com.palantir.tracing.api.SpanType;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class CacheTest {

    private static final int CACHE_MAXIMUM_SIZE = 10;
    private static final int INVALID_MAXIMUM_BATCH_SIZE = 0;
    private static final int LARGE_MAXIMUM_BATCH_SIZE = Integer.MAX_VALUE - 1;
    private static final int MAXIMUM_BATCH_SIZE = 2;
    private static final int MINIMUM_BATCH_SIZE = 1;

    private ExecutorService executor;

    @BeforeEach
    void before() {
        executor = Executors.newCachedThreadPool();
    }

    @AfterEach
    void after() {
        Tracer.getAndClearTrace();
        executor.shutdownNow();
    }

    @Test
    void maximumSize_sync() throws Exception {
        SyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(1)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildSync();

        cache.put("key1", "value1");

        assertThat(cache.getAllPresent(Set.of("key1"))).hasSize(1);

        cache.put("key2", "value2");

        // maximumSize is enforced by a background task
        executor.shutdown();
        assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();

        assertThat(cache.getAllPresent(Set.of("key1", "key2"))).hasSize(1);
    }

    @Test
    void maximumSize_async() throws Exception {
        AsyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(1)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsync();

        cache.put("key1", "value1");

        assertThat(cache.getAllPresent(Set.of("key1"))).hasSize(1);

        cache.put("key2", "value2");

        // maximumSize is enforced by a background task
        executor.shutdown();
        assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();

        assertThat(cache.getAllPresent(Set.of("key1", "key2"))).hasSize(1);
    }

    @Test
    void expiry_afterCreate_sync() {
        Expiry<String, String> expiry = new DefaultExpiry<>() {
            @Override
            public long expireAfterCreate(String _key, String _value, long _currentTime) {
                return 1;
            }
        };
        FakeTicker ticker = new FakeTicker();

        SyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(1)
                .expiry(expiry)
                .noMetrics()
                .executor(_name -> executor)
                .ticker(ticker)
                .buildSync();

        cache.put("key", "value");

        assertThat(cache.getIfPresent("key")).isEqualTo("value");

        ticker.plus(Duration.ofNanos(1));

        assertThat(cache.getIfPresent("key")).isNull();
    }

    @Test
    void expiry_afterCreate_async() {
        Expiry<String, String> expiry = new DefaultExpiry<>() {
            @Override
            public long expireAfterCreate(String _key, String _value, long _currentTime) {
                return 1;
            }
        };
        FakeTicker ticker = new FakeTicker();

        AsyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(1)
                .expiry(expiry)
                .noMetrics()
                .executor(_name -> executor)
                .ticker(ticker)
                .buildAsync();

        cache.put("key", "value");

        assertThat(cache.getIfPresent("key")).isEqualTo("value");

        ticker.plus(Duration.ofNanos(1));

        assertThat(cache.getIfPresent("key1")).isNull();
    }

    @Test
    void expiry_afterUpdate_sync() {
        Expiry<String, String> expiry = new DefaultExpiry<>() {
            @Override
            public long expireAfterUpdate(String _key, String _value, long _currentTime, long _currentDuration) {
                return 1;
            }
        };
        FakeTicker ticker = new FakeTicker();

        SyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(1)
                .expiry(expiry)
                .noMetrics()
                .executor(_name -> executor)
                .ticker(ticker)
                .buildSync();

        cache.put("key", "value1");

        assertThat(cache.getIfPresent("key")).isEqualTo("value1");

        ticker.plus(Duration.ofNanos(1));

        assertThat(cache.getIfPresent("key")).isEqualTo("value1");

        cache.put("key", "value2");

        assertThat(cache.getIfPresent("key")).isEqualTo("value2");

        ticker.plus(Duration.ofNanos(1));

        assertThat(cache.getIfPresent("key")).isNull();
    }

    @Test
    void expiry_afterUpdate_async() {
        Expiry<String, String> expiry = new DefaultExpiry<>() {
            @Override
            public long expireAfterUpdate(String _key, String _value, long _currentTime, long _currentDuration) {
                return 1;
            }
        };
        FakeTicker ticker = new FakeTicker();

        AsyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(1)
                .expiry(expiry)
                .noMetrics()
                .executor(_name -> executor)
                .ticker(ticker)
                .buildAsync();

        cache.put("key", "value1");

        assertThat(cache.getIfPresent("key")).isEqualTo("value1");

        ticker.plus(Duration.ofNanos(1));

        assertThat(cache.getIfPresent("key")).isEqualTo("value1");

        cache.put("key", "value2");

        assertThat(cache.getIfPresent("key")).isEqualTo("value2");

        ticker.plus(Duration.ofNanos(1));

        assertThat(cache.getIfPresent("key")).isNull();
    }

    @Test
    void expiry_afterRead_sync() {
        Expiry<String, String> expiry = new DefaultExpiry<>() {
            @Override
            public long expireAfterRead(String _key, String _value, long _currentTime, long _currentDuration) {
                return 1;
            }
        };
        FakeTicker ticker = new FakeTicker();

        SyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(1)
                .expiry(expiry)
                .noMetrics()
                .executor(_name -> executor)
                .ticker(ticker)
                .buildSync();

        cache.put("key", "value1");

        ticker.plus(Duration.ofNanos(1));

        assertThat(cache.getIfPresent("key")).isEqualTo("value1");

        ticker.plus(Duration.ofNanos(1));

        assertThat(cache.getIfPresent("key")).isNull();
    }

    @Test
    void expiry_afterRead_async() {
        Expiry<String, String> expiry = new DefaultExpiry<>() {
            @Override
            public long expireAfterRead(String _key, String _value, long _currentTime, long _currentDuration) {
                return 1;
            }
        };
        FakeTicker ticker = new FakeTicker();

        AsyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(1)
                .expiry(expiry)
                .noMetrics()
                .executor(_name -> executor)
                .ticker(ticker)
                .buildAsync();

        cache.put("key", "value1");

        ticker.plus(Duration.ofNanos(1));

        assertThat(cache.getIfPresent("key")).isEqualTo("value1");

        ticker.plus(Duration.ofNanos(1));

        assertThat(cache.getIfPresent("key")).isNull();
    }

    @Test
    void entries_sync() throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(1);

        SyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildSync();

        cache.put("key1", "value1");

        Future<String> future = executor.submit(() -> {
            return cache.get("key2", _key -> {
                startLatch.countDown();
                Uninterruptibles.awaitUninterruptibly(finishLatch);
                return "value2";
            });
        });

        startLatch.await();

        assertThat(cache.entries())
                .isUnmodifiable()
                .toIterable()
                .containsExactlyInAnyOrder(Map.entry("key1", "value1"));

        finishLatch.countDown();

        assertThat(future).succeedsWithin(1, TimeUnit.SECONDS).isEqualTo("value2");
    }

    @Test
    void entries_async() throws Exception {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(1);

        AsyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsync();

        cache.put("key1", "value1");

        Future<String> future = executor.submit(() -> {
            return cache.get("key2", _key -> {
                startLatch.countDown();
                Uninterruptibles.awaitUninterruptibly(finishLatch);
                return "value2";
            });
        });

        startLatch.await();

        assertThat(cache.entries())
                .isUnmodifiable()
                .toIterable()
                .containsExactlyInAnyOrder(Map.entry("key1", "value1"));

        finishLatch.countDown();

        assertThat(future).succeedsWithin(1, TimeUnit.SECONDS).isEqualTo("value2");
    }

    @Test
    void tracing_sync() throws Exception {
        SyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildSync();

        String traceId = Tracers.randomId();
        Tracer.initTraceWithSpan(Observability.SAMPLE, traceId, "root", SpanType.LOCAL);

        OpenSpan parentSpan = Tracer.startSpan("parent");

        cache.get("key", _key -> {
            Span span = Tracer.completeSpan().orElseThrow();
            assertThat(span.getTraceId()).isEqualTo(traceId);
            assertThat(span.getSpanId()).isEqualTo(parentSpan.getSpanId());
            assertThat(span.getOperation()).isEqualTo("parent");

            return "value";
        });
    }

    @Test
    void tracing_async() throws Exception {
        AsyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsync();

        String traceId = Tracers.randomId();
        Tracer.initTraceWithSpan(Observability.SAMPLE, traceId, "root", SpanType.LOCAL);

        OpenSpan parentSpan = Tracer.startSpan("parent");

        cache.get("key", _key -> {
            Span span = Tracer.completeSpan().orElseThrow();
            assertThat(span.getTraceId()).isEqualTo(traceId);
            assertThat(span.getParentSpanId()).contains(parentSpan.getSpanId());
            assertThat(span.getOperation()).isEqualTo("test cache load");

            return "value";
        });
    }

    @Test
    @SuppressWarnings("deprecation")
    void name_validation() {
        assertThatCode(() -> {
                    Cache.<String, String>builder()
                            .name("valid-cache-name-123")
                            .maximumSize(1)
                            .noExpiry()
                            .noMetrics()
                            .executor(_name -> executor)
                            .buildSync();
                })
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> {
                    Cache.<String, String>builder()
                            .name("Invalid.CACHE.name.###")
                            .maximumSize(1)
                            .noExpiry()
                            .noMetrics()
                            .executor(_name -> executor)
                            .buildSync();
                })
                .isInstanceOf(SafeIllegalArgumentException.class);

        assertThatCode(() -> {
                    Cache.<String, String>builder()
                            .legacyName("Legacy.Name.Allows_Anything#1")
                            .maximumSize(1)
                            .noExpiry()
                            .noMetrics()
                            .executor(_name -> executor)
                            .buildSync();
                })
                .doesNotThrowAnyException();
    }

    @Test
    void getAll_withBoundedBulkLoader_loadsBatchesConcurrently() throws Exception {
        CountDownLatch startLatch = new CountDownLatch(2);
        CountDownLatch allowLoadsToComplete = new CountDownLatch(1);
        Queue<Set<String>> loadedBatches = new ConcurrentLinkedQueue<>();
        BulkCacheLoader<String, String> bulkCacheLoader = BulkCacheLoader.withMaximumBatchSize(
                keys -> {
                    loadedBatches.add(Set.copyOf(keys));
                    startLatch.countDown();
                    Uninterruptibles.awaitUninterruptibly(allowLoadsToComplete);
                    return keys.stream().collect(Collectors.toUnmodifiableMap(key -> key, key -> "value-" + key));
                },
                MAXIMUM_BATCH_SIZE);
        AsyncBulkLoadingCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(CACHE_MAXIMUM_SIZE)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsyncWithBulkLoader(bulkCacheLoader);

        Future<Map<String, String>> result = executor.submit(() -> cache.getAll(List.of("key1", "key2", "key3")));

        try {
            assertThat(startLatch.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            allowLoadsToComplete.countDown();
        }

        assertThat(loadedBatches).containsExactlyInAnyOrder(Set.of("key1", "key2"), Set.of("key3"));
        assertThat(result)
                .succeedsWithin(1, TimeUnit.SECONDS)
                .isEqualTo(Map.of("key1", "value-key1", "key2", "value-key2", "key3", "value-key3"));
    }

    @Test
    void getAll_withMappingFunction_usesSingleRequest() {
        AtomicInteger requestCount = new AtomicInteger();
        AsyncBulkLoadingCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(CACHE_MAXIMUM_SIZE)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsyncWithBulkLoader(BulkCacheLoader.withMaximumBatchSize(_keys -> Map.of(), MAXIMUM_BATCH_SIZE));

        Map<String, String> result = cache.getAll(List.of("key1", "key2", "key3"), keys -> {
            requestCount.incrementAndGet();
            return keys.stream().collect(Collectors.toUnmodifiableMap(key -> key, key -> "value-" + key));
        });

        assertThat(requestCount).hasValue(1);
        assertThat(result)
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("key1", "value-key1", "key2", "value-key2", "key3", "value-key3"));
    }

    @Test
    void buildAsyncWithBulkLoader_withNonPositiveMaximumBatchSize_throwsIllegalArgument() {
        BulkCacheLoader<String, String> bulkCacheLoader = new BulkCacheLoader<>() {
            @Override
            public int maximumBatchSize() {
                return INVALID_MAXIMUM_BATCH_SIZE;
            }

            @Override
            public Map<String, String> loadAll(Set<String> _keys) {
                return Map.of();
            }
        };

        assertThatThrownBy(() -> Cache.<String, String>builder()
                        .name("test")
                        .maximumSize(CACHE_MAXIMUM_SIZE)
                        .noExpiry()
                        .noMetrics()
                        .executor(_name -> executor)
                        .buildAsyncWithBulkLoader(bulkCacheLoader))
                .isInstanceOf(SafeIllegalArgumentException.class);
    }

    @Test
    void withMaximumBatchSize_withNonPositiveMaximumBatchSize_throwsIllegalArgument() {
        assertThatThrownBy(() -> BulkCacheLoader.withMaximumBatchSize(_keys -> Map.of(), INVALID_MAXIMUM_BATCH_SIZE))
                .isInstanceOf(SafeIllegalArgumentException.class);
    }

    @Test
    void getAll_withCachedKey_batchesOnlyMissingKeys() {
        Queue<Set<String>> loadedBatches = new ConcurrentLinkedQueue<>();
        AsyncBulkLoadingCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(CACHE_MAXIMUM_SIZE)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsyncWithBulkLoader(BulkCacheLoader.withMaximumBatchSize(
                        keys -> {
                            loadedBatches.add(Set.copyOf(keys));
                            return keys.stream()
                                    .collect(Collectors.toUnmodifiableMap(key -> key, key -> "value-" + key));
                        },
                        MAXIMUM_BATCH_SIZE));
        cache.put("cached-key", "cached-value");

        assertThat(cache.getAll(List.of("cached-key", "key1", "key2")))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("cached-key", "cached-value", "key1", "value-key1", "key2", "value-key2"));
        assertThat(loadedBatches).containsExactly(Set.of("key1", "key2"));
    }

    @Test
    void getAll_withLargeFiniteMaximumBatchSize_loadsSingleBatch() {
        AsyncBulkLoadingCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(CACHE_MAXIMUM_SIZE)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsyncWithBulkLoader(BulkCacheLoader.withMaximumBatchSize(
                        keys -> keys.stream().collect(Collectors.toUnmodifiableMap(key -> key, key -> "value-" + key)),
                        LARGE_MAXIMUM_BATCH_SIZE));

        assertThat(cache.getAll(List.of("key1"))).containsExactly(Map.entry("key1", "value-key1"));
    }

    @Test
    void getAll_withPartialAndExtraBatchResults_returnsPartialResultAndCachesExtraValue() {
        AsyncBulkLoadingCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(CACHE_MAXIMUM_SIZE)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsyncWithBulkLoader(BulkCacheLoader.withMaximumBatchSize(
                        keys -> Stream.concat(
                                        keys.stream().filter(key -> !key.equals("key2")),
                                        keys.contains("key1") ? Stream.of("extra-key") : Stream.empty())
                                .collect(Collectors.toUnmodifiableMap(key -> key, key -> "value-" + key)),
                        MAXIMUM_BATCH_SIZE));

        assertThat(cache.getAll(List.of("key1", "key2", "key3")))
                .containsExactlyInAnyOrderEntriesOf(Map.of("key1", "value-key1", "key3", "value-key3"));
        assertThat(cache.getAllPresent(List.of("key1", "key2", "key3", "extra-key")))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("key1", "value-key1", "key3", "value-key3", "extra-key", "value-extra-key"));
    }

    @Test
    void getAll_whenOneBatchFails_doesNotCacheSuccessfulBatches() {
        CountDownLatch successfulLoadCompleted = new CountDownLatch(1);
        AsyncBulkLoadingCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(CACHE_MAXIMUM_SIZE)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsyncWithBulkLoader(BulkCacheLoader.withMaximumBatchSize(
                        keys -> {
                            String key = keys.iterator().next();
                            if (key.equals("key1")) {
                                successfulLoadCompleted.countDown();
                                return Map.of(key, "value-" + key);
                            }
                            Uninterruptibles.awaitUninterruptibly(successfulLoadCompleted);
                            throw new SafeRuntimeException("Expected load failure");
                        },
                        MINIMUM_BATCH_SIZE));

        assertThatThrownBy(() -> cache.getAll(List.of("key1", "key2"))).isInstanceOf(SafeRuntimeException.class);
        assertThat(cache.getAllPresent(List.of("key1", "key2"))).isEmpty();
    }

    @Test
    void getAll_whenInvalidatedDuringFirstBatch_doesNotCacheLaterBatches() throws Exception {
        CountDownLatch firstLoadStarted = new CountDownLatch(1);
        CountDownLatch allowFirstLoadToComplete = new CountDownLatch(1);
        AtomicInteger loadCount = new AtomicInteger();
        AsyncBulkLoadingCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(CACHE_MAXIMUM_SIZE)
                .noExpiry()
                .noMetrics()
                .executor(_name -> Runnable::run)
                .buildAsyncWithBulkLoader(BulkCacheLoader.withMaximumBatchSize(
                        keys -> {
                            if (loadCount.incrementAndGet() == 1) {
                                firstLoadStarted.countDown();
                                Uninterruptibles.awaitUninterruptibly(allowFirstLoadToComplete);
                            }
                            return keys.stream()
                                    .collect(Collectors.toUnmodifiableMap(key -> key, key -> "value-" + key));
                        },
                        MINIMUM_BATCH_SIZE));
        Future<Map<String, String>> result = executor.submit(() -> cache.getAll(List.of("key1", "key2")));

        try {
            assertThat(firstLoadStarted.await(1, TimeUnit.SECONDS)).isTrue();
            cache.invalidateAll();
        } finally {
            allowFirstLoadToComplete.countDown();
        }

        assertThat(result)
                .succeedsWithin(1, TimeUnit.SECONDS)
                .isEqualTo(Map.of("key1", "value-key1", "key2", "value-key2"));
        assertThat(cache.getAllPresent(List.of("key1", "key2"))).isEmpty();
    }

    @Test
    void buildAsyncWithBulkLoader_withChangingMaximumBatchSize_readsMaximumBatchSizeOnce() {
        AtomicInteger maximumBatchSizeRequestCount = new AtomicInteger();
        Queue<Set<String>> loadedBatches = new ConcurrentLinkedQueue<>();
        BulkCacheLoader<String, String> bulkCacheLoader = new BulkCacheLoader<>() {
            @Override
            public int maximumBatchSize() {
                return maximumBatchSizeRequestCount.getAndIncrement() == 0 ? MAXIMUM_BATCH_SIZE : Integer.MAX_VALUE;
            }

            @Override
            public Map<String, String> loadAll(Set<String> keys) {
                loadedBatches.add(Set.copyOf(keys));
                return keys.stream().collect(Collectors.toUnmodifiableMap(key -> key, key -> "value-" + key));
            }
        };
        AsyncBulkLoadingCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(CACHE_MAXIMUM_SIZE)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsyncWithBulkLoader(bulkCacheLoader);

        assertThat(cache.getAll(List.of("key1", "key2", "key3")))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("key1", "value-key1", "key2", "value-key2", "key3", "value-key3"));
        assertThat(cache.getAll(List.of("key4", "key5", "key6")))
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of("key4", "value-key4", "key5", "value-key5", "key6", "value-key6"));
        assertThat(maximumBatchSizeRequestCount).hasValue(1);
        assertThat(loadedBatches)
                .containsExactlyInAnyOrder(
                        Set.of("key1", "key2"), Set.of("key3"), Set.of("key4", "key5"), Set.of("key6"));
    }

    @Test
    void withMaximumBatchSize_withLargerLimit_preservesDelegateLimit() {
        BulkCacheLoader<String, String> delegate =
                BulkCacheLoader.withMaximumBatchSize(_keys -> Map.of(), MINIMUM_BATCH_SIZE);

        assertThat(BulkCacheLoader.withMaximumBatchSize(delegate, MAXIMUM_BATCH_SIZE)
                        .maximumBatchSize())
                .isEqualTo(MINIMUM_BATCH_SIZE);
    }

    private interface DefaultExpiry<K, V> extends Expiry<K, V> {

        @Override
        default long expireAfterCreate(K _key, V _value, long _currentTime) {
            return Long.MAX_VALUE;
        }

        @Override
        default long expireAfterUpdate(K _key, V _value, long _currentTime, long currentDuration) {
            return currentDuration;
        }

        @Override
        default long expireAfterRead(K _key, V _value, long _currentTime, long currentDuration) {
            return currentDuration;
        }
    }
}
