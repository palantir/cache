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

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.Uninterruptibles;
import com.palantir.deadlines.DeadlineContext;
import com.palantir.deadlines.DeadlineExpiredException;
import com.palantir.deadlines.DeadlineScope;
import com.palantir.deadlines.Deadlines;
import com.palantir.deadlines.Deadlines.Enforcement;
import com.palantir.deadlines.DeadlinesHttpHeaders;
import com.palantir.logsafe.exceptions.SafeIllegalArgumentException;
import com.palantir.logsafe.exceptions.SafeIllegalStateException;
import com.palantir.tracing.CloseableTracer;
import com.palantir.tracing.Observability;
import com.palantir.tracing.Tracer;
import com.palantir.tracing.Tracers;
import com.palantir.tracing.api.OpenSpan;
import com.palantir.tracing.api.Span;
import com.palantir.tracing.api.SpanType;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

final class CacheTest {

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

    @Nested
    @Timeout(10)
    class AsyncLoadDeadlines {

        @Test
        void a_load_runs_without_the_deadline_of_the_caller_that_started_it() {
            // An executor that carries the submitting thread's deadline, as Witchcraft executors can.
            ExecutorService propagatingExecutor = Deadlines.wrap(executor);
            AtomicReference<Optional<Duration>> loadSees = new AtomicReference<>();
            AtomicReference<Optional<Duration>> handOffSees = new AtomicReference<>();
            AsyncLoadingCache<String, String> cache = Cache.<String, String>builder()
                    .name("test")
                    .maximumSize(10)
                    .noExpiry()
                    .noMetrics()
                    .executor(_name -> propagatingExecutor)
                    .buildAsyncWithLoader(_key -> {
                        loadSees.set(Deadlines.getRemainingDeadline());
                        handOffSees.set(
                                Futures.getUnchecked(propagatingExecutor.submit(Deadlines::getRemainingDeadline)));
                        return "value";
                    });

            try (DeadlineScope ignored =
                    deadline(Duration.ofSeconds(10), Enforcement.ENFORCE).attach()) {
                assertThat(cache.get("key")).isEqualTo("value");
                assertThat(Deadlines.getRemainingDeadline())
                        .as("the caller keeps its deadline")
                        .isPresent();
            }
            assertThat(loadSees.get()).isEmpty();
            assertThat(handOffSees.get())
                    .as("work the load hands off has no deadline either")
                    .isEmpty();
        }

        @Test
        @SuppressWarnings("deprecation") // Witchcraft versions without DeadlineContext store deadlines on the trace
        void a_load_runs_without_a_deadline_stored_on_the_callers_trace() {
            AtomicReference<Optional<Duration>> loadSees = new AtomicReference<>();
            AsyncLoadingCache<String, String> cache = Cache.<String, String>builder()
                    .name("test")
                    .maximumSize(10)
                    .noExpiry()
                    .noMetrics()
                    .executor(_name -> executor)
                    .buildAsyncWithLoader(_key -> {
                        loadSees.set(Deadlines.getRemainingDeadline());
                        return "value";
                    });

            try (CloseableTracer ignored = CloseableTracer.startSpan("test")) {
                Deadlines.parseFromRequest(
                        Optional.of(Duration.ofSeconds(10)),
                        Map.<String, String>of(),
                        (request, header) -> Optional.ofNullable(request.get(header)),
                        Enforcement.ENFORCE);

                assertThat(cache.get("key")).isEqualTo("value");
                assertThat(Deadlines.getRemainingDeadline())
                        .as("the caller keeps its deadline")
                        .isPresent();
            }
            assertThat(loadSees.get())
                    .as("the load runs on the caller's trace, but not under its deadline")
                    .isEmpty();
        }

        @Test
        void a_bulk_load_runs_without_the_deadline_of_the_caller_that_started_it() {
            ExecutorService propagatingExecutor = Deadlines.wrap(executor);
            AtomicReference<Optional<Duration>> loadSees = new AtomicReference<>();
            AsyncBulkLoadingCache<String, String> cache = Cache.<String, String>builder()
                    .name("test")
                    .maximumSize(10)
                    .noExpiry()
                    .noMetrics()
                    .executor(_name -> propagatingExecutor)
                    .buildAsyncWithBulkLoader(_keys -> {
                        loadSees.set(Deadlines.getRemainingDeadline());
                        return Map.of("key", "value");
                    });

            try (DeadlineScope ignored =
                    deadline(Duration.ofSeconds(10), Enforcement.ENFORCE).attach()) {
                assertThat(cache.getAll(Set.of("key"))).containsExactly(Map.entry("key", "value"));
            }
            assertThat(loadSees.get()).isEmpty();
        }

        @Test
        void a_shared_load_is_not_failed_by_the_deadline_of_the_caller_that_started_it() throws Exception {
            CountDownLatch releaseLoad = new CountDownLatch(1);
            AtomicInteger loads = new AtomicInteger();
            AsyncLoadingCache<String, String> cache = Cache.<String, String>builder()
                    .name("test")
                    .maximumSize(10)
                    .noExpiry()
                    .noMetrics()
                    .executor(_name -> Deadlines.wrap(executor))
                    .buildAsyncWithLoader(_key -> {
                        loads.incrementAndGet();
                        Uninterruptibles.awaitUninterruptibly(releaseLoad);
                        // Checks the deadline, as making a request with Dialogue does.
                        Deadlines.checkDeadline(Enforcement.ENFORCE);
                        return "value";
                    });

            try (DeadlineScope ignored =
                    deadline(Duration.ofMillis(50), Enforcement.ENFORCE).attach()) {
                assertThatThrownBy(() -> cache.get("key"))
                        .as("the caller that started the load stops waiting at its own deadline")
                        .isInstanceOf(DeadlineExpiredException.External.class);
            }

            Future<String> otherCaller = executor.submit(() -> {
                try (DeadlineScope ignored =
                        deadline(Duration.ofSeconds(10), Enforcement.ENFORCE).attach()) {
                    return cache.get("key");
                }
            });
            releaseLoad.countDown();

            assertThat(otherCaller.get()).isEqualTo("value");
            assertThat(loads).as("both callers share one load").hasValue(1);
        }

        @Test
        void a_caller_whose_deadline_has_expired_does_not_start_a_load() {
            AtomicInteger loads = new AtomicInteger();
            AsyncLoadingCache<String, String> cache = Cache.<String, String>builder()
                    .name("test")
                    .maximumSize(10)
                    .noExpiry()
                    .noMetrics()
                    // Runs loads on the calling thread, so any load would have completed before get returns.
                    .executor(ExecutorFactory.direct())
                    .buildAsyncWithLoader(_key -> {
                        loads.incrementAndGet();
                        return "value";
                    });

            try (DeadlineScope ignored =
                    deadline(Duration.ZERO, Enforcement.ENFORCE).attach()) {
                assertThatThrownBy(() -> cache.get("key")).isInstanceOf(DeadlineExpiredException.External.class);
            }
            assertThat(loads).hasValue(0);
            assertThat(cache.getIfPresent("key")).isNull();
        }

        @Test
        void a_caller_whose_deadline_has_expired_gets_loaded_values() {
            AsyncBulkLoadingCache<String, String> cache = Cache.<String, String>builder()
                    .name("test")
                    .maximumSize(10)
                    .noExpiry()
                    .noMetrics()
                    .executor(_name -> executor)
                    .buildAsyncWithBulkLoader(_keys -> {
                        throw new SafeIllegalStateException("loaded values are not loaded again");
                    });
            cache.put("key", "value");

            try (DeadlineScope ignored =
                    deadline(Duration.ZERO, Enforcement.ENFORCE).attach()) {
                assertThat(cache.get("key")).isEqualTo("value");
                assertThat(cache.getAll(Set.of("key"))).containsExactly(Map.entry("key", "value"));
            }
        }

        @Test
        void a_caller_whose_deadline_has_expired_starts_a_bulk_load_without_waiting_for_it() throws Exception {
            CountDownLatch loadStarted = new CountDownLatch(1);
            CountDownLatch releaseLoad = new CountDownLatch(1);
            AsyncBulkLoadingCache<String, String> cache = Cache.<String, String>builder()
                    .name("test")
                    .maximumSize(10)
                    .noExpiry()
                    .noMetrics()
                    .executor(_name -> executor)
                    .buildAsyncWithBulkLoader(_keys -> {
                        loadStarted.countDown();
                        Uninterruptibles.awaitUninterruptibly(releaseLoad);
                        return Map.of("key", "value");
                    });

            try (DeadlineScope ignored =
                    deadline(Duration.ZERO, Enforcement.ENFORCE).attach()) {
                assertThatThrownBy(() -> cache.getAll(Set.of("key")))
                        .isInstanceOf(DeadlineExpiredException.External.class);
            }
            assertThat(loadStarted.await(5, TimeUnit.SECONDS))
                    .as("Caffeine inserts entries for the keys before loading them, so the load runs for anyone"
                            + " waiting on them")
                    .isTrue();
            releaseLoad.countDown();

            assertThat(cache.get("key"))
                    .as("later callers share the load's value")
                    .isEqualTo("value");
        }

        @Test
        void a_caller_whose_deadline_is_not_enforced_waits_for_the_load() {
            AsyncLoadingCache<String, String> cache = Cache.<String, String>builder()
                    .name("test")
                    .maximumSize(10)
                    .noExpiry()
                    .noMetrics()
                    .executor(_name -> executor)
                    .buildAsyncWithLoader(_key -> "value");

            try (DeadlineScope ignored =
                    deadline(Duration.ZERO, Enforcement.DEFER).attach()) {
                assertThat(cache.get("key")).isEqualTo("value");
            }
        }

        @Test
        void a_load_on_a_direct_executor_and_its_completion_callbacks_run_without_the_callers_deadline() {
            AtomicReference<Optional<Duration>> loadSees = new AtomicReference<>();
            AtomicReference<Optional<Duration>> expirySees = new AtomicReference<>();
            AsyncCache<String, String> cache = Cache.<String, String>builder()
                    .name("test")
                    .maximumSize(10)
                    .expiry(new DefaultExpiry<>() {
                        @Override
                        public long expireAfterCreate(String _key, String _value, long _currentTime) {
                            expirySees.set(Deadlines.getRemainingDeadline());
                            return Long.MAX_VALUE;
                        }
                    })
                    .noMetrics()
                    .executor(ExecutorFactory.direct())
                    .buildAsync();

            try (DeadlineScope ignored =
                    deadline(Duration.ofSeconds(10), Enforcement.ENFORCE).attach()) {
                assertThat(cache.get("key", _key -> {
                            loadSees.set(Deadlines.getRemainingDeadline());
                            return "value";
                        }))
                        .isEqualTo("value");
                assertThat(Deadlines.getRemainingDeadline())
                        .as("the caller's deadline is restored after the load runs on its thread")
                        .isPresent();
            }
            assertThat(loadSees.get()).isEmpty();
            assertThat(expirySees.get()).isEmpty();
        }

        private DeadlineContext deadline(Duration remaining, Enforcement enforcement) {
            return DeadlineContext.fromRequest(
                    Optional.empty(),
                    Map.of(DeadlinesHttpHeaders.EXPECT_WITHIN, String.valueOf(remaining.toMillis() / 1000.0)),
                    (request, header) -> Optional.ofNullable(request.get(header)),
                    enforcement);
        }
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
