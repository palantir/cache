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

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import java.time.Duration;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
        AtomicLong time = new AtomicLong();
        AtomicInteger counter = new AtomicInteger();

        CyclicBarrier refreshBarrier = new CyclicBarrier(2);

        LoadingCache<String, Integer> cache = Caffeine.newBuilder()
                .refreshAfterWrite(Duration.ofSeconds(1))
                .executor(executor)
                .ticker(time::get)
                .<String, Integer>build(_key -> {
                    int value = counter.incrementAndGet();

                    System.out.println("Start load: " + value);

                    switch (value) {
                        case 2 -> {
                            refreshBarrier.await();
                        }
                    }

                    System.out.println("  End load: " + value);

                    return value;
                });

        System.out.println("Get1");
        assertThat(cache.get("key")).isEqualTo(1);

        time.addAndGet(1_000_000_001);

        System.out.println("Get2");
        assertThat(cache.get("key")).isEqualTo(1);

        refreshBarrier.await(1, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();

        assertThat(cache.get("key")).isEqualTo(2);
    }

    @Test
    void async() throws Exception {
        AtomicLong time = new AtomicLong();
        AtomicInteger counter = new AtomicInteger();

        CyclicBarrier refreshBarrier = new CyclicBarrier(2);

        LoadingCache<String, Integer> cache = Caffeine.newBuilder()
                .refreshAfterWrite(Duration.ofSeconds(1))
                .executor(executor)
                .ticker(time::get)
                .<String, Integer>buildAsync(_key -> {
                    int value = counter.incrementAndGet();

                    System.out.println("Start load: " + value);

                    switch (value) {
                        case 2 -> {
                            refreshBarrier.await();
                        }
                    }

                    System.out.println("  End load: " + value);

                    return value;
                })
                .synchronous();

        System.out.println("Get1");
        assertThat(cache.get("key")).isEqualTo(1);

        time.addAndGet(1_000_000_001);

        System.out.println("Get2");
        assertThat(cache.get("key")).isEqualTo(1);

        refreshBarrier.await(1, TimeUnit.SECONDS);
        executor.shutdown();
        assertThat(executor.awaitTermination(1, TimeUnit.SECONDS)).isTrue();

        assertThat(cache.get("key")).isEqualTo(2);
    }
}
