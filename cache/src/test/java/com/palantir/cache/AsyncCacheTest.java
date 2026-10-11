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
import static org.assertj.core.api.Assertions.fail;

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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.ObjectAssert;
import org.assertj.core.api.ThrowableAssertAlternative;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class AsyncCacheTest {

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
        AsyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsync();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(1);

        Future<String> future = cache.getAsync("keyA", _key -> {
            startLatch.countDown();
            await(finishLatch);
            return "valueA1";
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
        AsyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsync();

        String value = cache.get("keyA", _key -> {
            return "valueA1";
        });

        assertThat(value).isEqualTo("valueA1");

        assertThat(cache.getIfPresent("keyA")).isEqualTo("valueA1");
        assertThat(cache.entries()).toIterable().containsExactlyInAnyOrder(Map.entry("keyA", "valueA1"));
    }

    @Test
    void get_exception() {
        AsyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsync();

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
    void getAsync() {
        AsyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsync();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(1);

        Future<String> future1 = cache.getAsync("keyA", _key -> {
            startLatch.countDown();
            await(finishLatch);
            return "valueA1";
        });

        await(startLatch);

        Future<String> future2 = cache.getAsync("keyA", _key -> {
            return fail();
        });

        assertThat(future1).isSameAs(future2);

        assertThat(cache.getIfPresent("keyA")).isNull();
        assertThat(cache.entries()).toIterable().isEmpty();

        finishLatch.countDown();

        assertSucceeds(future1).isEqualTo("valueA1");

        assertThat(cache.getIfPresent("keyA")).isEqualTo("valueA1");
        assertThat(cache.entries()).toIterable().containsExactlyInAnyOrder(Map.entry("keyA", "valueA1"));
    }

    @Test
    void getAsync_exception() {
        AsyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsync();

        RuntimeException exception = new RuntimeException();

        Future<String> future = cache.getAsync("keyA", _key -> {
            throw exception;
        });

        assertFails(future).isInstanceOf(ExecutionException.class).havingCause().isSameAs(exception);

        assertThat(cache.getIfPresent("keyA")).isNull();
        assertThat(cache.entries()).toIterable().isEmpty();
    }

    @Test
    void getAsync_cancel() {
        AsyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsync();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(1);

        Future<String> future = cache.getAsync("keyA", _key -> {
            startLatch.countDown();
            await(finishLatch);
            return "valueA1";
        });

        await(startLatch);

        future.cancel(true);

        finishLatch.countDown();

        // Ensure the cache loader task has completed
        shutdownAndAwaitTermination(executor);

        assertThat(cache.getIfPresent("keyA")).isNull();
        assertThat(cache.entries()).toIterable().isEmpty();
    }

    @Test
    void getAll() {
        AsyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsync();

        Map<String, String> values = cache.getAll(Set.of("keyA", "keyB"), _keys -> {
            return Map.of("keyA", "valueA1");
        });

        assertThat(values).containsExactlyInAnyOrderEntriesOf(Map.of("keyA", "valueA1"));

        assertThat(cache.getIfPresent("keyA")).isEqualTo("valueA1");
        assertThat(cache.getIfPresent("keyB")).isNull();
        assertThat(cache.entries()).toIterable().containsExactlyInAnyOrder(Map.entry("keyA", "valueA1"));
    }

    @Test
    void getAll_exception() {
        AsyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsync();

        RuntimeException exception = new RuntimeException();

        assertThatThrownBy(() -> {
                    cache.getAll(Set.of("keyA", "keyB"), _keys -> {
                        throw exception;
                    });
                })
                .isSameAs(exception);

        assertThat(cache.getIfPresent("keyA")).isNull();
        assertThat(cache.getIfPresent("keyB")).isNull();
        assertThat(cache.entries()).toIterable().isEmpty();
    }

    @Test
    void getAllAsync() {
        AsyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsync();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(1);

        Future<Map<String, String>> future = cache.getAllAsync(Set.of("keyA", "keyB"), _keys -> {
            startLatch.countDown();
            await(finishLatch);
            return Map.of("keyA", "valueA1");
        });

        assertThat(cache.getIfPresent("keyA")).isNull();
        assertThat(cache.getIfPresent("keyB")).isNull();
        assertThat(cache.entries()).toIterable().isEmpty();

        await(startLatch);
        finishLatch.countDown();

        assertSucceeds(future).isEqualTo(Map.of("keyA", "valueA1"));

        assertThat(cache.getIfPresent("keyA")).isEqualTo("valueA1");
        assertThat(cache.getIfPresent("keyB")).isNull();
        assertThat(cache.entries()).toIterable().containsExactlyInAnyOrder(Map.entry("keyA", "valueA1"));
    }

    @Test
    void getAllAsync_exception() {
        AsyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsync();

        RuntimeException exception = new RuntimeException();

        Future<Map<String, String>> future = cache.getAllAsync(Set.of("keyA", "keyB"), _keys -> {
            throw exception;
        });

        assertFails(future).isInstanceOf(ExecutionException.class).havingCause().isSameAs(exception);

        assertThat(cache.getIfPresent("keyA")).isNull();
        assertThat(cache.getIfPresent("keyB")).isNull();
        assertThat(cache.entries()).toIterable().isEmpty();
    }

    @Test
    void getAllAsync_cancel() {
        AsyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsync();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(1);

        Future<Map<String, String>> future = cache.getAllAsync(Set.of("keyA", "keyB"), _keys -> {
            startLatch.countDown();
            await(finishLatch);
            return Map.of("keyA", "valueA1");
        });

        await(startLatch);

        future.cancel(true);

        finishLatch.countDown();

        // Ensure the cache loader task has completed
        shutdownAndAwaitTermination(executor);

        assertThat(cache.getIfPresent("keyA")).isEqualTo("valueA1");
        assertThat(cache.getIfPresent("keyB")).isNull();
        assertThat(cache.entries()).toIterable().containsExactlyInAnyOrder(Map.entry("keyA", "valueA1"));
    }

    @Test
    void put() {
        AsyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsync();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(1);

        Future<String> future = cache.getAsync("keyA", _key -> {
            startLatch.countDown();
            await(finishLatch);
            return "valueA1";
        });

        await(startLatch);

        cache.put("keyA", "valueA2");

        assertThat(cache.getIfPresent("keyA")).isEqualTo("valueA2");
        assertThat(cache.entries()).toIterable().containsExactlyInAnyOrder(Map.entry("keyA", "valueA2"));

        finishLatch.countDown();

        assertSucceeds(future).isEqualTo("valueA1");

        assertThat(cache.getIfPresent("keyA")).isEqualTo("valueA2");
        assertThat(cache.entries()).toIterable().containsExactlyInAnyOrder(Map.entry("keyA", "valueA2"));
    }

    @Test
    void putAll() {
        AsyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsync();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(1);

        Future<String> future = cache.getAsync("keyA", _key -> {
            startLatch.countDown();
            await(finishLatch);
            return "valueA1";
        });

        await(startLatch);

        cache.putAll(Map.of("keyA", "valueA2", "keyB", "valueB1"));

        assertThat(cache.getIfPresent("keyA")).isEqualTo("valueA2");
        assertThat(cache.getIfPresent("keyB")).isEqualTo("valueB1");
        assertThat(cache.entries())
                .toIterable()
                .containsExactlyInAnyOrder(Map.entry("keyA", "valueA2"), Map.entry("keyB", "valueB1"));

        finishLatch.countDown();

        assertSucceeds(future).isEqualTo("valueA1");

        assertThat(cache.getIfPresent("keyA")).isEqualTo("valueA2");
        assertThat(cache.getIfPresent("keyB")).isEqualTo("valueB1");
        assertThat(cache.entries())
                .toIterable()
                .containsExactlyInAnyOrder(Map.entry("keyA", "valueA2"), Map.entry("keyB", "valueB1"));
    }

    @Test
    void invalidate() {
        AsyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsync();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(1);

        cache.put("keyA", "valueA1");
        cache.put("keyB", "valueB1");

        Future<String> future = cache.getAsync("keyC", _key -> {
            startLatch.countDown();
            await(finishLatch);
            return "valueC1";
        });

        await(startLatch);

        cache.invalidate("keyB");
        cache.invalidate("keyC");

        assertThat(cache.getIfPresent("keyA")).isEqualTo("valueA1");
        assertThat(cache.getIfPresent("keyB")).isNull();
        assertThat(cache.getIfPresent("keyC")).isNull();
        assertThat(cache.entries()).toIterable().containsExactlyInAnyOrder(Map.entry("keyA", "valueA1"));

        finishLatch.countDown();

        assertSucceeds(future).isEqualTo("valueC1");

        assertThat(cache.getIfPresent("keyA")).isEqualTo("valueA1");
        assertThat(cache.getIfPresent("keyB")).isNull();
        assertThat(cache.getIfPresent("keyC")).isNull();
        assertThat(cache.entries()).toIterable().containsExactlyInAnyOrder(Map.entry("keyA", "valueA1"));
    }

    @Test
    void invalidateAll() {
        AsyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsync();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(1);

        cache.put("keyA", "valueA1");
        cache.put("keyB", "valueB1");

        Future<String> future = cache.getAsync("keyC", _key -> {
            startLatch.countDown();
            await(finishLatch);
            return "valueC1";
        });

        await(startLatch);

        cache.invalidateAll(Set.of("keyB", "keyC"));

        assertThat(cache.getIfPresent("keyA")).isEqualTo("valueA1");
        assertThat(cache.getIfPresent("keyB")).isNull();
        assertThat(cache.getIfPresent("keyC")).isNull();
        assertThat(cache.entries()).toIterable().containsExactlyInAnyOrder(Map.entry("keyA", "valueA1"));

        finishLatch.countDown();

        assertSucceeds(future).isEqualTo("valueC1");

        assertThat(cache.getIfPresent("keyA")).isEqualTo("valueA1");
        assertThat(cache.getIfPresent("keyB")).isNull();
        assertThat(cache.getIfPresent("keyC")).isNull();
        assertThat(cache.entries()).toIterable().containsExactlyInAnyOrder(Map.entry("keyA", "valueA1"));
    }

    @Test
    void invalidateAllEntries() {
        AsyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsync();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(1);

        cache.put("keyA", "valueA1");

        Future<String> future = cache.getAsync("keyB", _key -> {
            startLatch.countDown();
            await(finishLatch);
            return "valueB1";
        });

        await(startLatch);

        cache.invalidateAll();

        assertThat(cache.getIfPresent("keyA")).isNull();
        assertThat(cache.getIfPresent("keyB")).isNull();
        assertThat(cache.entries()).toIterable().isEmpty();

        finishLatch.countDown();

        assertSucceeds(future).isEqualTo("valueB1");

        assertThat(cache.getIfPresent("keyA")).isNull();
        assertThat(cache.getIfPresent("keyB")).isNull();
        assertThat(cache.entries()).toIterable().isEmpty();
    }

    @Test
    void entries() {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(1);

        AsyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsync();

        cache.put("keyA", "valueA1");

        Future<String> future = cache.getAsync("keyB", _key -> {
            startLatch.countDown();
            await(finishLatch);
            return "valueB1";
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
        AsyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(1)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsync();

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

        AsyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .expiry(expiry)
                .noMetrics()
                .executor(_name -> executor)
                .ticker(ticker)
                .buildAsync();

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

        AsyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .expiry(expiry)
                .noMetrics()
                .executor(_name -> executor)
                .ticker(ticker)
                .buildAsync();

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

        AsyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .expiry(expiry)
                .noMetrics()
                .executor(_name -> executor)
                .ticker(ticker)
                .buildAsync();

        cache.put("keyA", "valueA1");

        ticker.plus(Duration.ofNanos(1));

        assertThat(cache.getIfPresent("keyA")).isEqualTo("valueA1");

        ticker.plus(Duration.ofNanos(1));

        assertThat(cache.getIfPresent("keyA")).isNull();
    }

    @Test
    void tracing() {
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

        cache.get("keyA", _key -> {
            Span span = Tracer.completeSpan().orElseThrow();
            assertThat(span.getTraceId()).isEqualTo(traceId);
            assertThat(span.getParentSpanId()).contains(parentSpan.getSpanId());
            assertThat(span.getOperation()).isEqualTo("test cache load");

            return "valueA1";
        });
    }

    private static <T> ObjectAssert<T> assertSucceeds(Future<T> future) {
        return assertThat(future).succeedsWithin(1, TimeUnit.SECONDS);
    }

    private static ThrowableAssertAlternative<?> assertFails(Future<?> future) {
        return assertThat(future).failsWithin(1, TimeUnit.SECONDS).withThrowableThat();
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
