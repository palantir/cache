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

import com.palantir.deadlines.DeadlineExpiredException;
import com.palantir.deadlines.Deadlines;
import com.palantir.deadlines.Deadlines.Enforcement;
import com.palantir.deadlines.Deadlines.RequestDecodingAdapter;
import com.palantir.tracing.Observability;
import com.palantir.tracing.Tracer;
import com.palantir.tracing.Tracers;
import com.palantir.tracing.api.SpanType;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

final class CacheDeadlineTest {

    private static final Duration LATCH_TIMEOUT = Duration.ofSeconds(10);

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
    @Timeout(30)
    void shared_load_is_not_failed_by_the_caller_with_the_smallest_budget() throws Exception {
        CountDownLatch loadStarted = new CountDownLatch(1);
        CountDownLatch releaseLoad = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();

        AsyncLoadingCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsyncWithLoader(_key -> {
                    loads.incrementAndGet();
                    loadStarted.countDown();
                    awaitUninterruptibly(releaseLoad);
                    // the load must not inherit the deadline of the caller that started it
                    Deadlines.checkDeadline(Enforcement.DEFER);
                    return "value";
                });

        Future<String> impatient = executor.submit(withDeadline(Duration.ofMillis(200), Enforcement.ENFORCE, cache));
        assertThat(loadStarted.await(LATCH_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();
        Future<String> patient = executor.submit(withDeadline(Duration.ofMinutes(1), Enforcement.ENFORCE, cache));

        // the caller that started the load gives up once its own budget is exhausted, without cancelling the load
        assertThat(impatient)
                .failsWithin(LATCH_TIMEOUT)
                .withThrowableThat()
                .withCauseInstanceOf(DeadlineExpiredException.class);

        releaseLoad.countDown();

        assertThat(patient).succeedsWithin(LATCH_TIMEOUT).isEqualTo("value");
        assertThat(loads).hasValue(1);
    }

    @Test
    @Timeout(30)
    void load_does_not_observe_the_deadline_of_the_caller_that_started_it() throws Exception {
        AtomicReference<Optional<Duration>> remainingDuringLoad = new AtomicReference<>();
        AtomicReference<Boolean> tracedDuringLoad = new AtomicReference<>();

        AsyncLoadingCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsyncWithLoader(_key -> {
                    remainingDuringLoad.set(Deadlines.getRemainingDeadline());
                    tracedDuringLoad.set(Tracer.hasTraceId());
                    return "value";
                });

        Optional<Duration> remainingAfterGet = executor.submit(() -> {
                    Tracer.initTraceWithSpan(Observability.SAMPLE, Tracers.randomId(), "request", SpanType.LOCAL);
                    try {
                        Deadlines.parseFromRequest(
                                Optional.of(Duration.ofMinutes(1)), Map.of(), NoHeaders.INSTANCE, Enforcement.ENFORCE);
                        assertThat(cache.get("key")).isEqualTo("value");
                        return Deadlines.getRemainingDeadline();
                    } finally {
                        Tracer.getAndClearTrace();
                    }
                })
                .get(LATCH_TIMEOUT.toSeconds(), TimeUnit.SECONDS);

        assertThat(tracedDuringLoad)
                .as("the load runs in the caller's trace, so the deadline would otherwise be visible")
                .hasValue(true);
        assertThat(remainingDuringLoad).hasValue(Optional.empty());
        assertThat(remainingAfterGet)
                .as("suppressing the deadline for the load must not clear it for the caller")
                .isPresent();
    }

    @Test
    @Timeout(30)
    void caller_waits_for_the_load_when_deadline_enforcement_is_disabled() throws Exception {
        CountDownLatch loadStarted = new CountDownLatch(1);
        CountDownLatch releaseLoad = new CountDownLatch(1);

        AsyncLoadingCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsyncWithLoader(_key -> {
                    loadStarted.countDown();
                    awaitUninterruptibly(releaseLoad);
                    return "value";
                });

        // a deadline this short is exhausted while waiting for the latched load, but must not abandon it
        Future<String> caller = executor.submit(withDeadline(Duration.ofMillis(1), Enforcement.DISABLE, cache));

        assertThat(loadStarted.await(LATCH_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .isTrue();
        releaseLoad.countDown();

        assertThat(caller).succeedsWithin(LATCH_TIMEOUT).isEqualTo("value");
    }

    private static Callable<String> withDeadline(
            Duration deadline, Enforcement enforcement, AsyncLoadingCache<String, String> cache) {
        return () -> {
            Tracer.initTraceWithSpan(Observability.SAMPLE, Tracers.randomId(), "request", SpanType.LOCAL);
            try {
                Deadlines.parseFromRequest(Optional.of(deadline), Map.of(), NoHeaders.INSTANCE, enforcement);
                return cache.get("key");
            } finally {
                Tracer.getAndClearTrace();
            }
        };
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            assertThat(latch.await(LATCH_TIMEOUT.toSeconds(), TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for latch", e);
        }
    }

    private enum NoHeaders implements RequestDecodingAdapter<Map<String, String>> {
        INSTANCE;

        @Override
        @Deprecated
        public Optional<String> getFirstHeader(Map<String, String> _request, String _headerName) {
            return Optional.empty();
        }
    }
}
