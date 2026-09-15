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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.util.concurrent.Uninterruptibles;
import com.palantir.deadlines.DeadlineExpiredException;
import com.palantir.deadlines.Deadlines;
import com.palantir.deadlines.Deadlines.Enforcement;
import com.palantir.tracing.Observability;
import com.palantir.tracing.Tracer;
import com.palantir.tracing.Tracers;
import com.palantir.tracing.api.SpanType;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

final class DeadlinePropagationTest {

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
    @Timeout(10)
    void asyncLoad_expiredDeadlineFailsBothCallers() throws Exception {
        AsyncCache<String, String> cache = Cache.<String, String>builder()
                .name("test")
                .maximumSize(10)
                .noExpiry()
                .noMetrics()
                .executor(_name -> executor)
                .buildAsync();
        CountDownLatch loadStarted = new CountDownLatch(1);
        CountDownLatch releaseLoad = new CountDownLatch(1);

        startTraceWithDeadline(Duration.ZERO);
        Future<String> shortRequest = executor.submit(Tracers.wrap(() -> cache.get("key", _key -> {
            loadStarted.countDown();
            assertThat(Uninterruptibles.awaitUninterruptibly(releaseLoad, 10, TimeUnit.SECONDS))
                    .isTrue();
            Deadlines.encodeToRequest(Duration.ofSeconds(30), new HashMap<>(), Map::put, Enforcement.DEFER);
            return "value";
        })));
        loadStarted.await();

        startTraceWithDeadline(Duration.ofHours(1));
        CompletableFuture<Thread> longCaller = new CompletableFuture<>();
        Future<String> longRequest = executor.submit(Tracers.wrap(() -> {
            longCaller.complete(Thread.currentThread());
            return cache.get("key", _key -> "value");
        }));
        Thread longThread = longCaller.get();
        // Wait until the second caller has joined the in-flight load before letting it fail.
        while (longThread.getState() != Thread.State.WAITING) {
            Thread.sleep(1);
        }
        assertThat(longRequest).isNotDone();
        releaseLoad.countDown();

        assertThatThrownBy(shortRequest::get).hasCauseInstanceOf(DeadlineExpiredException.class);
        assertThatThrownBy(longRequest::get).hasCauseInstanceOf(DeadlineExpiredException.class);
    }

    private static void startTraceWithDeadline(Duration deadline) {
        Tracer.initTraceWithSpan(Observability.SAMPLE, Tracers.randomId(), "request", SpanType.LOCAL);
        Deadlines.parseFromRequest(
                Optional.of(deadline), Map.of(), (_request, _header) -> Optional.empty(), Enforcement.ENFORCE);
    }
}
