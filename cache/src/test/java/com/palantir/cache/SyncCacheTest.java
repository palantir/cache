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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.palantir.tracing.Observability;
import com.palantir.tracing.Tracer;
import com.palantir.tracing.Tracers;
import com.palantir.tracing.api.OpenSpan;
import com.palantir.tracing.api.Span;
import com.palantir.tracing.api.SpanType;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ObjectAssert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class SyncCacheTest {

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
    void getIfPresent() {
        SyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildSync();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(1);

        Future<String> future = executor.submit(() -> {
            return cache.get("keyA", _key -> {
                startLatch.countDown();
                await(finishLatch);
                return "valueA1";
            });
        });

        assertThat(cache.getIfPresent("keyA")).isNull();
        assertThat(cache.entries()).toIterable().isEmpty();

        await(startLatch);

        assertThat(cache.getIfPresent("keyA")).isNull();
        assertThat(cache.entries()).toIterable().isEmpty();

        finishLatch.countDown();

        assertSucceeds(future).isEqualTo("valueA1");

        assertThat(cache.getIfPresent("keyA")).isEqualTo("valueA1");
        assertThat(cache.entries()).toIterable().containsExactlyInAnyOrder(Map.entry("keyA", "valueA1"));
    }

    @Test
    void get() {
        SyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildSync();

        String value = cache.get("keyA", _key -> {
            return "valueA1";
        });

        assertThat(value).isEqualTo("valueA1");

        assertThat(cache.getIfPresent("keyA")).isEqualTo("valueA1");
        assertThat(cache.entries()).toIterable().containsExactlyInAnyOrder(Map.entry("keyA", "valueA1"));
    }

    @Test
    void get_exception() {
        SyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildSync();

        RuntimeException exception = new RuntimeException();

        assertThatThrownBy(() -> {
                    cache.get("keyA", _key -> {
                        throw exception;
                    });
                })
                .isSameAs(exception);

        assertThat(cache.getIfPresent("keyA")).isNull();
        assertThat(cache.entries()).toIterable().isEmpty();
    }

    @Test
    void put() {
        SyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildSync();

        cache.put("keyA", "valueA1");

        assertThat(cache.getIfPresent("keyA")).isEqualTo("valueA1");
        assertThat(cache.entries()).toIterable().containsExactlyInAnyOrder(Map.entry("keyA", "valueA1"));

        cache.put("keyA", "valueA2");

        assertThat(cache.getIfPresent("keyA")).isEqualTo("valueA2");
        assertThat(cache.entries()).toIterable().containsExactlyInAnyOrder(Map.entry("keyA", "valueA2"));
    }

    @Test
    void putAll() {
        SyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildSync();

        cache.put("keyA", "valueA1");

        cache.putAll(Map.of("keyA", "valueA2", "keyB", "valueB1"));

        assertThat(cache.getIfPresent("keyA")).isEqualTo("valueA2");
        assertThat(cache.getIfPresent("keyB")).isEqualTo("valueB1");
        assertThat(cache.entries())
                .toIterable()
                .containsExactlyInAnyOrder(Map.entry("keyA", "valueA2"), Map.entry("keyB", "valueB1"));
    }

    @Test
    void invalidate() {
        SyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildSync();

        cache.put("keyA", "valueA1");
        cache.put("keyB", "valueB1");

        cache.invalidate("keyB");

        assertThat(cache.getIfPresent("keyA")).isEqualTo("valueA1");
        assertThat(cache.getIfPresent("keyB")).isNull();
        assertThat(cache.entries()).toIterable().containsExactlyInAnyOrder(Map.entry("keyA", "valueA1"));

        assertThat(cache.getIfPresent("keyA")).isEqualTo("valueA1");
        assertThat(cache.getIfPresent("keyB")).isNull();
        assertThat(cache.entries()).toIterable().containsExactlyInAnyOrder(Map.entry("keyA", "valueA1"));
    }

    @Test
    void invalidateAll() {
        SyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildSync();

        cache.put("keyA", "valueA1");
        cache.put("keyB", "valueB1");
        cache.put("keyC", "valueC1");

        cache.invalidateAll(Set.of("keyB", "keyC"));

        assertThat(cache.getIfPresent("keyA")).isEqualTo("valueA1");
        assertThat(cache.getIfPresent("keyB")).isNull();
        assertThat(cache.getIfPresent("keyC")).isNull();
        assertThat(cache.entries()).toIterable().containsExactlyInAnyOrder(Map.entry("keyA", "valueA1"));
    }

    @Test
    void entries() {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(1);

        SyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildSync();

        cache.put("keyA", "valueA1");

        Future<String> future = executor.submit(() -> {
            return cache.get("keyB", _key -> {
                startLatch.countDown();
                await(finishLatch);
                return "valueB1";
            });
        });

        await(startLatch);

        assertThat(cache.entries())
                .isUnmodifiable()
                .toIterable()
                .containsExactlyInAnyOrder(Map.entry("keyA", "valueA1"));

        finishLatch.countDown();

        assertSucceeds(future).isEqualTo("valueB1");
    }

    @Test
    void maximumSize() {
        SyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(1)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildSync();

        cache.put("keyA", "valueA1");

        assertThat(cache.entries()).toIterable().hasSize(1);

        cache.put("keyB", "valueB2");

        // maximumSize is enforced by a background task
        shutdownAndAwaitTermination(executor);

        assertThat(cache.getAllPresent(Set.of("keyA", "keyB"))).hasSize(1);
        assertThat(cache.entries()).toIterable().hasSize(1);
    }

    @Test
    void expiry_afterCreate() {
        Expiry<String, String> expiry = new DefaultExpiry<>() {
            @Override
            public long expireAfterCreate(String _key, String _value, long _currentTime) {
                return 1;
            }
        };
        FakeTicker ticker = new FakeTicker();

        SyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .expiry(expiry)
                .noMetrics()
                .executor(_name -> executor)
                .ticker(ticker)
                .buildSync();

        cache.put("keyA", "valueA1");

        assertThat(cache.getIfPresent("keyA")).isEqualTo("valueA1");

        ticker.plus(Duration.ofNanos(1));

        assertThat(cache.getIfPresent("keyA")).isNull();
    }

    @Test
    void expiry_afterUpdate() {
        Expiry<String, String> expiry = new DefaultExpiry<>() {
            @Override
            public long expireAfterUpdate(String _key, String _value, long _currentTime, long _currentDuration) {
                return 1;
            }
        };
        FakeTicker ticker = new FakeTicker();

        SyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .expiry(expiry)
                .noMetrics()
                .executor(_name -> executor)
                .ticker(ticker)
                .buildSync();

        cache.put("keyA", "valueA1");

        assertThat(cache.getIfPresent("keyA")).isEqualTo("valueA1");

        ticker.plus(Duration.ofNanos(1));

        assertThat(cache.getIfPresent("keyA")).isEqualTo("valueA1");

        cache.put("keyA", "valueB2");

        assertThat(cache.getIfPresent("keyA")).isEqualTo("valueB2");

        ticker.plus(Duration.ofNanos(1));

        assertThat(cache.getIfPresent("keyA")).isNull();
    }

    @Test
    void expiry_afterRead() {
        Expiry<String, String> expiry = new DefaultExpiry<>() {
            @Override
            public long expireAfterRead(String _key, String _value, long _currentTime, long _currentDuration) {
                return 1;
            }
        };
        FakeTicker ticker = new FakeTicker();

        SyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .expiry(expiry)
                .noMetrics()
                .executor(_name -> executor)
                .ticker(ticker)
                .buildSync();

        cache.put("keyA", "valueA1");

        ticker.plus(Duration.ofNanos(1));

        assertThat(cache.getIfPresent("keyA")).isEqualTo("valueA1");

        ticker.plus(Duration.ofNanos(1));

        assertThat(cache.getIfPresent("keyA")).isNull();
    }

    @Test
    void tracing() {
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

        cache.get("keyA", _key -> {
            Span span = Tracer.completeSpan().orElseThrow();
            assertThat(span.getTraceId()).isEqualTo(traceId);
            assertThat(span.getSpanId()).isEqualTo(parentSpan.getSpanId());
            assertThat(span.getOperation()).isEqualTo("parent");

            return "valueA1";
        });
    }

    private static <T> ObjectAssert<T> assertSucceeds(Future<T> future) {
        return assertThat(future).succeedsWithin(1, TimeUnit.SECONDS);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static void shutdownAndAwaitTermination(ExecutorService executor) {
        executor.shutdown();
        try {
            assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
