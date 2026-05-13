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

package com.palantir.cache.errorprone;

import com.google.errorprone.BugCheckerRefactoringTestHelper;
import com.google.errorprone.BugCheckerRefactoringTestHelper.TestMode;
import com.google.errorprone.CompilationTestHelper;
import org.junit.jupiter.api.Test;

final class CaffeineLoadingCacheRefactoringTest {

    private BugCheckerRefactoringTestHelper refactoringHelper() {
        return BugCheckerRefactoringTestHelper.newInstance(CaffeineLoadingCacheRefactoring.class, getClass());
    }

    private CompilationTestHelper compilationHelper() {
        return CompilationTestHelper.newInstance(CaffeineLoadingCacheRefactoring.class, getClass());
    }

    private void assertCompiles(String path, String... lines) {
        compilationHelper().addSourceLines(path, lines).matchAllDiagnostics().doTest();
    }

    @SuppressWarnings("for-rollout:deprecation")
    @Test
    void disallowed_cache_methods() {
        // language=java
        String input = """
            import com.github.benmanes.caffeine.cache.Caffeine;
            import com.github.benmanes.caffeine.cache.LoadingCache;
            import java.util.Map;
            class Test {
                private LoadingCache<String, String> cache;
                void setupCache() {
                    this.cache = Caffeine.newBuilder()
                            .maximumSize(100)
                            .build(this::load);
                }
                private String load(String key) { return key; }
                private String valueFromMap(String key) {
                    Map<String, String> map = cache.asMap();
                    return map.get(key);
                }
            }
            """.stripIndent();
        refactoringHelper().addInputLines("Test.java", input).expectUnchanged().doTest(TestMode.TEXT_MATCH);
        assertCompiles("Test.java", input);
    }

    @SuppressWarnings("for-rollout:deprecation")
    @Test
    void disallowed_cache_passed_as_method_argument() {
        // language=java
        String input = """
            import com.github.benmanes.caffeine.cache.Caffeine;
            import com.github.benmanes.caffeine.cache.LoadingCache;
            class Test {
                private LoadingCache<String, String> cache;
                void setupCache() {
                    this.cache = Caffeine.newBuilder()
                            .maximumSize(100)
                            .build(this::load);
                }
                private String load(String key) { return key; }
                void useCache() {
                    doSomething(cache);
                }
                private void doSomething(LoadingCache<String, String> c) {}
            }
            """.stripIndent();
        refactoringHelper().addInputLines("Test.java", input).expectUnchanged().doTest(TestMode.TEXT_MATCH);
        assertCompiles("Test.java", input);
    }

    @SuppressWarnings("for-rollout:deprecation")
    @Test
    void disallowed_cache_passed_to_constructor() {
        // language=java
        String input = """
            import com.github.benmanes.caffeine.cache.Caffeine;
            import com.github.benmanes.caffeine.cache.LoadingCache;
            class Test {
                private LoadingCache<String, String> cache;
                void setupCache() {
                    this.cache = Caffeine.newBuilder()
                            .maximumSize(100)
                            .build(this::load);
                }
                private String load(String key) { return key; }
                void useCache() {
                    SomeWrapper wrapper = new SomeWrapper(cache);
                }
                static class SomeWrapper {
                    SomeWrapper(LoadingCache<String, String> c) {}
                }
            }
            """.stripIndent();
        refactoringHelper().addInputLines("Test.java", input).expectUnchanged().doTest(TestMode.TEXT_MATCH);
        assertCompiles("Test.java", input);
    }

    @SuppressWarnings("for-rollout:deprecation")
    @Test
    void disallowed_builder_methods() {
        // language=java
        String input = """
            import com.github.benmanes.caffeine.cache.Caffeine;
            import com.github.benmanes.caffeine.cache.LoadingCache;
            import java.time.Duration;
            import java.util.Map;
            class Test {
                private LoadingCache<String, String> cache;
                void setupCache() {
                    this.cache = Caffeine.newBuilder()
                            .maximumSize(100)
                            .refreshAfterWrite(Duration.ofMinutes(1))
                            .build(this::load);
                }
                private String load(String key) { return key; }
                private String valueFromMap(String key) {
                    Map<String, String> map = cache.asMap();
                    return map.get(key);
                }
            }
            """.stripIndent();
        refactoringHelper().addInputLines("Test.java", input).expectUnchanged().doTest(TestMode.TEXT_MATCH);
        assertCompiles("Test.java", input);
    }

    @SuppressWarnings("for-rollout:deprecation")
    @Test
    void disallowed_non_fluent_builder() {
        // language=java
        String input = """
            import com.github.benmanes.caffeine.cache.Caffeine;
            import com.github.benmanes.caffeine.cache.LoadingCache;
            import java.time.Duration;

            class Test {
                private LoadingCache<String, String> cache;

                void setupCache() {
                    Caffeine<Object, Object> builder = Caffeine.newBuilder();
                    builder.maximumSize(100);
                    builder.expireAfterWrite(Duration.ofMinutes(5));
                    this.cache = builder.build(this::load);
                }

                private String load(String key) { return key; }
            }
            """.stripIndent();
        refactoringHelper().addInputLines("Test.java", input).expectUnchanged().doTest(TestMode.TEXT_MATCH);
        assertCompiles("Test.java", input);
    }

    @SuppressWarnings("for-rollout:deprecation")
    @Test
    void handles_null_checks_on_cache_reads() {
        // language=java
        String output = """
            import com.github.benmanes.caffeine.cache.Caffeine;
            import com.github.benmanes.caffeine.cache.LoadingCache;
            import com.palantir.cache.AsyncLoadingCache;
            import com.palantir.cache.Cache;
            import com.palantir.logsafe.Preconditions;
            import java.util.concurrent.Executors;

            class Test {
                private AsyncLoadingCache<String, String> cache;

                void setupCache() {
                    this.cache =
                            Cache.<String, String>builder()
                                    .name("test-cache-0")
                                    .maximumSize(100)
                                    .noExpiry()
                                    .noMetrics()
                                    .executor(_name -> Executors.newCachedThreadPool())
                                    .buildAsyncWithLoader(this::load);
                }

                private String load(String key) {
                    return key;
                }

                String getValue(String key) {
                    return Preconditions.checkNotNull(cache.get(key));
                }
            }
            """.stripIndent();
        refactoringHelper()
                .addInputLines(
                        "Test.java",
                        // language=java
                        """
                        import com.github.benmanes.caffeine.cache.Caffeine;
                        import com.github.benmanes.caffeine.cache.LoadingCache;

                        class Test {
                            private LoadingCache<String, String> cache;

                            void setupCache() {
                                this.cache = Caffeine.newBuilder()
                                        .maximumSize(100)
                                        .build(this::load);
                            }

                            private String load(String key) {
                                return key;
                            }

                            String getValue(String key) {
                                return cache.get(key);
                            }
                        }
                        """.stripIndent())
                .addOutputLines("Test.java", output)
                .doTest(TestMode.TEXT_MATCH);
        assertCompiles("Test.java", output);
    }

    @SuppressWarnings("for-rollout:deprecation")
    @Test
    void handles_existing_preconditions_check_on_cache_reads() {
        // language=java
        String output = """
            import com.github.benmanes.caffeine.cache.Caffeine;
            import com.github.benmanes.caffeine.cache.LoadingCache;
            import com.palantir.cache.AsyncLoadingCache;
            import com.palantir.cache.Cache;
            import com.palantir.logsafe.Preconditions;
            import java.util.concurrent.Executors;

            class Test {
                private AsyncLoadingCache<String, String> cache;

                void setupCache() {
                    this.cache =
                            Cache.<String, String>builder()
                                    .name("test-cache-0")
                                    .maximumSize(100)
                                    .noExpiry()
                                    .noMetrics()
                                    .executor(_name -> Executors.newCachedThreadPool())
                                    .buildAsyncWithLoader(this::load);
                }

                private String load(String key) {
                    return key;
                }

                String getValue(String key) {
                    return Preconditions.checkNotNull(cache.get(key));
                }
            }
            """.stripIndent();
        refactoringHelper()
                .addInputLines(
                        "Test.java",
                        // language=java
                        """
                        import com.github.benmanes.caffeine.cache.Caffeine;
                        import com.github.benmanes.caffeine.cache.LoadingCache;
                        import com.palantir.logsafe.Preconditions;

                        class Test {
                            private LoadingCache<String, String> cache;

                            void setupCache() {
                                this.cache = Caffeine.newBuilder()
                                        .maximumSize(100)
                                        .build(this::load);
                            }

                            private String load(String key) {
                                return key;
                            }

                            String getValue(String key) {
                                return Preconditions.checkNotNull(cache.get(key));
                            }
                        }
                        """.stripIndent())
                .addOutputLines("Test.java", output)
                .doTest(TestMode.TEXT_MATCH);
        assertCompiles("Test.java", output);
    }

    @Test
    void refactors_simple_loading_cache() {
        // language=java
        String output = """
            import com.github.benmanes.caffeine.cache.Caffeine;
            import com.github.benmanes.caffeine.cache.LoadingCache;
            import com.palantir.cache.AsyncLoadingCache;
            import com.palantir.cache.Cache;
            import java.util.concurrent.Executors;

            class Test {
                private AsyncLoadingCache<String, String> cache;

                void setupCache() {
                    this.cache =
                            Cache.<String, String>builder()
                                    .name("test-cache-0")
                                    .maximumSize(100)
                                    .noExpiry()
                                    .noMetrics()
                                    .executor(_name -> Executors.newCachedThreadPool())
                                    .buildAsyncWithLoader(this::load);
                }

                private String load(String key) {
                    return key;
                }
            }
            """.stripIndent();
        refactoringHelper()
                .addInputLines(
                        "Test.java",
                        // language=java
                        """
                        import com.github.benmanes.caffeine.cache.Caffeine;
                        import com.github.benmanes.caffeine.cache.LoadingCache;

                        class Test {
                            private LoadingCache<String, String> cache;

                            void setupCache() {
                                this.cache = Caffeine.newBuilder()
                                        .maximumSize(100)
                                        .build(this::load);
                            }

                            private String load(String key) {
                                return key;
                            }
                        }
                        """.stripIndent())
                .addOutputLines("Test.java", output)
                .doTest(TestMode.TEXT_MATCH);
        assertCompiles("Test.java", output);
    }

    @SuppressWarnings("for-rollout:deprecation")
    @Test
    void infers_cache_name() {
        // language=java
        String output = """
            package com.example;
            import com.github.benmanes.caffeine.cache.Caffeine;
            import com.github.benmanes.caffeine.cache.LoadingCache;
            import com.palantir.cache.AsyncLoadingCache;
            import com.palantir.cache.Cache;
            import java.util.concurrent.Executors;

            class Test {
                private AsyncLoadingCache<String, String> myValueCache;

                void setupCache() {
                    this.myValueCache =
                            Cache.<String, String>builder()
                                    .name("com-example-test-cache-0")
                                    .maximumSize(100)
                                    .noExpiry()
                                    .noMetrics()
                                    .executor(_name -> Executors.newCachedThreadPool())
                                    .buildAsyncWithLoader(this::load);
                }

                private String load(String key) {
                    return key;
                }
            }
            """.stripIndent();
        refactoringHelper()
                .addInputLines(
                        "Test.java",
                        // language=java
                        """
                        package com.example;
                        import com.github.benmanes.caffeine.cache.Caffeine;
                        import com.github.benmanes.caffeine.cache.LoadingCache;

                        class Test {
                            private LoadingCache<String, String> myValueCache;

                            void setupCache() {
                                this.myValueCache = Caffeine.newBuilder()
                                        .maximumSize(100)
                                        .build(this::load);
                            }

                            private String load(String key) {
                                return key;
                            }
                        }
                        """.stripIndent())
                .addOutputLines("Test.java", output)
                .doTest(TestMode.TEXT_MATCH);
        assertCompiles("Test.java", output);
    }

    @SuppressWarnings("for-rollout:deprecation")
    @Test
    void handles_default_maximum_size() {
        // language=java
        String output = """
            import com.github.benmanes.caffeine.cache.Caffeine;
            import com.github.benmanes.caffeine.cache.LoadingCache;
            import com.palantir.cache.AsyncLoadingCache;
            import com.palantir.cache.Cache;
            import java.util.concurrent.Executors;

            class Test {
                private AsyncLoadingCache<String, String> cache;

                void setupCache() {
                    this.cache =
                            Cache.<String, String>builder()
                                    .name("test-cache-0")
                                    .maximumSize(Long.MAX_VALUE)
                                    .noExpiry()
                                    .noMetrics()
                                    .executor(_name -> Executors.newCachedThreadPool())
                                    .buildAsyncWithLoader(this::load);
                }

                private String load(String key) {
                    return key;
                }
            }
            """.stripIndent();
        refactoringHelper()
                .addInputLines(
                        "Test.java",
                        // language=java
                        """
                        import com.github.benmanes.caffeine.cache.Caffeine;
                        import com.github.benmanes.caffeine.cache.LoadingCache;

                        class Test {
                            private LoadingCache<String, String> cache;

                            void setupCache() {
                                this.cache = Caffeine.newBuilder()
                                        .build(this::load);
                            }

                            private String load(String key) {
                                return key;
                            }
                        }
                        """.stripIndent())
                .addOutputLines("Test.java", output)
                .doTest(TestMode.TEXT_MATCH);
        assertCompiles("Test.java", output);
    }

    @SuppressWarnings("for-rollout:deprecation")
    @Test
    void handles_expire_after_write_duration() {
        // language=java
        String output = """
            import com.github.benmanes.caffeine.cache.Caffeine;
            import com.github.benmanes.caffeine.cache.LoadingCache;
            import com.palantir.cache.AsyncLoadingCache;
            import com.palantir.cache.Cache;
            import com.palantir.cache.Expiry;
            import java.time.Duration;
            import java.util.concurrent.Executors;

            class Test {
                private AsyncLoadingCache<String, String> cache;

                void setupCache() {
                    this.cache =
                            Cache.<String, String>builder()
                                    .name("test-cache-0")
                                    .maximumSize(100)
                                    .expiry(Expiry.afterWrite(Duration.ofMinutes(1)))
                                    .noMetrics()
                                    .executor(_name -> Executors.newCachedThreadPool())
                                    .buildAsyncWithLoader(this::load);
                }

                private String load(String key) {
                    return key;
                }
            }
            """.stripIndent();
        refactoringHelper()
                .addInputLines(
                        "Test.java",
                        // language=java
                        """
                        import com.github.benmanes.caffeine.cache.Caffeine;
                        import com.github.benmanes.caffeine.cache.LoadingCache;
                        import java.time.Duration;

                        class Test {
                            private LoadingCache<String, String> cache;

                            void setupCache() {
                                this.cache = Caffeine.newBuilder()
                                        .maximumSize(100)
                                        .expireAfterWrite(Duration.ofMinutes(1))
                                        .build(this::load);
                            }

                            private String load(String key) {
                                return key;
                            }
                        }
                        """.stripIndent())
                .addOutputLines("Test.java", output)
                .doTest(TestMode.TEXT_MATCH);
        assertCompiles("Test.java", output);
    }

    @SuppressWarnings("for-rollout:deprecation")
    @Test
    void handles_expire_after_write_time_unit() {
        // language=java
        String output = """
            import com.github.benmanes.caffeine.cache.Caffeine;
            import com.github.benmanes.caffeine.cache.LoadingCache;
            import com.palantir.cache.AsyncLoadingCache;
            import com.palantir.cache.Cache;
            import com.palantir.cache.Expiry;
            import java.time.Duration;
            import java.util.concurrent.Executors;
            import java.util.concurrent.TimeUnit;

            class Test {
                private AsyncLoadingCache<String, String> cache;

                void setupCache() {
                    this.cache =
                            Cache.<String, String>builder()
                                    .name("test-cache-0")
                                    .maximumSize(100)
                                    .expiry(Expiry.afterWrite(Duration.of(1, TimeUnit.MINUTES.toChronoUnit())))
                                    .noMetrics()
                                    .executor(_name -> Executors.newCachedThreadPool())
                                    .buildAsyncWithLoader(this::load);
                }

                private String load(String key) {
                    return key;
                }
            }
            """.stripIndent();
        refactoringHelper()
                .addInputLines(
                        "Test.java",
                        // language=java
                        """
                        import com.github.benmanes.caffeine.cache.Caffeine;
                        import com.github.benmanes.caffeine.cache.LoadingCache;
                        import java.util.concurrent.TimeUnit;

                        class Test {
                            private LoadingCache<String, String> cache;

                            void setupCache() {
                                this.cache = Caffeine.newBuilder()
                                        .maximumSize(100)
                                        .expireAfterWrite(1, TimeUnit.MINUTES)
                                        .build(this::load);
                            }

                            private String load(String key) {
                                return key;
                            }
                        }
                        """.stripIndent())
                .addOutputLines("Test.java", output)
                .doTest(TestMode.TEXT_MATCH);
        assertCompiles("Test.java", output);
    }

    @SuppressWarnings("for-rollout:deprecation")
    @Test
    void handles_expire_after_access_duration() {
        // language=java
        String output = """
            import com.github.benmanes.caffeine.cache.Caffeine;
            import com.github.benmanes.caffeine.cache.LoadingCache;
            import com.palantir.cache.AsyncLoadingCache;
            import com.palantir.cache.Cache;
            import com.palantir.cache.Expiry;
            import java.time.Duration;
            import java.util.concurrent.Executors;

            class Test {
                private AsyncLoadingCache<String, String> cache;

                void setupCache() {
                    this.cache =
                            Cache.<String, String>builder()
                                    .name("test-cache-0")
                                    .maximumSize(100)
                                    .expiry(Expiry.afterAccess(Duration.ofMinutes(1)))
                                    .noMetrics()
                                    .executor(_name -> Executors.newCachedThreadPool())
                                    .buildAsyncWithLoader(this::load);
                }

                private String load(String key) {
                    return key;
                }
            }
            """.stripIndent();
        refactoringHelper()
                .addInputLines(
                        "Test.java",
                        // language=java
                        """
                        import com.github.benmanes.caffeine.cache.Caffeine;
                        import com.github.benmanes.caffeine.cache.LoadingCache;
                        import java.time.Duration;

                        class Test {
                            private LoadingCache<String, String> cache;

                            void setupCache() {
                                this.cache = Caffeine.newBuilder()
                                        .maximumSize(100)
                                        .expireAfterAccess(Duration.ofMinutes(1))
                                        .build(this::load);
                            }

                            private String load(String key) {
                                return key;
                            }
                        }
                        """.stripIndent())
                .addOutputLines("Test.java", output)
                .doTest(TestMode.TEXT_MATCH);
        assertCompiles("Test.java", output);
    }

    @SuppressWarnings("for-rollout:deprecation")
    @Test
    void handles_expire_after_access_time_unit() {
        // language=java
        String output = """
            import com.github.benmanes.caffeine.cache.Caffeine;
            import com.github.benmanes.caffeine.cache.LoadingCache;
            import com.palantir.cache.AsyncLoadingCache;
            import com.palantir.cache.Cache;
            import com.palantir.cache.Expiry;
            import java.time.Duration;
            import java.util.concurrent.Executors;
            import java.util.concurrent.TimeUnit;

            class Test {
                private AsyncLoadingCache<String, String> cache;

                void setupCache() {
                    this.cache =
                            Cache.<String, String>builder()
                                    .name("test-cache-0")
                                    .maximumSize(100)
                                    .expiry(Expiry.afterAccess(Duration.of(1, TimeUnit.MINUTES.toChronoUnit())))
                                    .noMetrics()
                                    .executor(_name -> Executors.newCachedThreadPool())
                                    .buildAsyncWithLoader(this::load);
                }

                private String load(String key) {
                    return key;
                }
            }
            """.stripIndent();
        refactoringHelper()
                .addInputLines(
                        "Test.java",
                        // language=java
                        """
                        import com.github.benmanes.caffeine.cache.Caffeine;
                        import com.github.benmanes.caffeine.cache.LoadingCache;
                        import java.util.concurrent.TimeUnit;

                        class Test {
                            private LoadingCache<String, String> cache;

                            void setupCache() {
                                this.cache = Caffeine.newBuilder()
                                        .maximumSize(100)
                                        .expireAfterAccess(1, TimeUnit.MINUTES)
                                        .build(this::load);
                            }

                            private String load(String key) {
                                return key;
                            }
                        }
                        """.stripIndent())
                .addOutputLines("Test.java", output)
                .doTest(TestMode.TEXT_MATCH);
        assertCompiles("Test.java", output);
    }

    @SuppressWarnings("for-rollout:deprecation")
    @Test
    void handles_cache_stats_wrapper() {
        // language=java
        String output = """
            import com.github.benmanes.caffeine.cache.Caffeine;
            import com.github.benmanes.caffeine.cache.LoadingCache;
            import com.palantir.cache.AsyncLoadingCache;
            import com.palantir.cache.Cache;
            import com.palantir.tritium.metrics.caffeine.CacheStats;
            import com.palantir.tritium.metrics.registry.TaggedMetricRegistry;
            import java.util.concurrent.Executors;

            class Test {
                private AsyncLoadingCache<String, String> cache;

                void setup(TaggedMetricRegistry metrics) {
                    this.cache =
                            Cache.<String, String>builder()
                                    .name("my-cache-name")
                                    .maximumSize(100)
                                    .noExpiry()
                                    .metrics(metrics)
                                    .executor(_name -> Executors.newCachedThreadPool())
                                    .buildAsyncWithLoader(this::load);
                }

                private String load(String key) {
                    return key;
                }
            }
            """.stripIndent();
        refactoringHelper()
                .addInputLines(
                        "Test.java",
                        // language=java
                        """
                        import com.github.benmanes.caffeine.cache.Caffeine;
                        import com.github.benmanes.caffeine.cache.LoadingCache;
                        import com.palantir.tritium.metrics.caffeine.CacheStats;
                        import com.palantir.tritium.metrics.registry.TaggedMetricRegistry;

                        class Test {
                            private LoadingCache<String, String> cache;

                            void setup(TaggedMetricRegistry metrics) {
                                this.cache = CacheStats.of(metrics, "my-cache-name")
                                        .register(stats -> Caffeine.newBuilder()
                                                .maximumSize(100)
                                                .recordStats(stats)
                                                .build(this::load));
                            }

                            private String load(String key) {
                                return key;
                            }
                        }
                        """.stripIndent())
                .addOutputLines("Test.java", output)
                .doTest(TestMode.TEXT_MATCH);
        assertCompiles("Test.java", output);
    }

    @SuppressWarnings("for-rollout:deprecation")
    @Test
    void handles_legacy_cache_name() {
        // language=java
        String output = """
            package com.example;
            import com.github.benmanes.caffeine.cache.Caffeine;
            import com.github.benmanes.caffeine.cache.LoadingCache;
            import com.palantir.cache.AsyncLoadingCache;
            import com.palantir.cache.Cache;
            import com.palantir.tritium.metrics.caffeine.CacheStats;
            import com.palantir.tritium.metrics.registry.TaggedMetricRegistry;
            import java.util.concurrent.Executors;

            class Test {
                private AsyncLoadingCache<String, String> cache;

                // legacyName() is used to preserve metric flows for existing caches; consider changing the cache
                // name to one compatible with com.palantir.cache.CacheBuilder#name()
                @SuppressWarnings("for-rollout:deprecation")
                void setupCache(TaggedMetricRegistry metrics) {
                    this.cache =
                            Cache.<String, String>builder()
                                    .legacyName("com.example.Test")
                                    .maximumSize(100)
                                    .noExpiry()
                                    .metrics(metrics)
                                    .executor(_name -> Executors.newCachedThreadPool())
                                    .buildAsyncWithLoader(this::load);
                }

                private String load(String key) {
                    return key;
                }
            }
            """.stripIndent();
        refactoringHelper()
                .addInputLines(
                        "Test.java",
                        // language=java
                        """
                        package com.example;
                        import com.github.benmanes.caffeine.cache.Caffeine;
                        import com.github.benmanes.caffeine.cache.LoadingCache;
                        import com.palantir.tritium.metrics.caffeine.CacheStats;
                        import com.palantir.tritium.metrics.registry.TaggedMetricRegistry;

                        class Test {
                            private LoadingCache<String, String> cache;

                            void setupCache(TaggedMetricRegistry metrics) {
                                this.cache = CacheStats.of(metrics, "com.example.Test")
                                        .register(stats -> Caffeine.newBuilder()
                                                .maximumSize(100)
                                                .recordStats(stats)
                                                .build(this::load));
                            }

                            private String load(String key) {
                                return key;
                            }
                        }
                        """.stripIndent())
                .addOutputLines("Test.java", output)
                .doTest(TestMode.TEXT_MATCH);
        assertCompiles("Test.java", output);
    }

    @SuppressWarnings("for-rollout:deprecation")
    @Test
    void nested_type_params_on_cache_type_params_are_preserved() {
        // language=java
        String output = """
            import com.github.benmanes.caffeine.cache.Caffeine;
            import com.github.benmanes.caffeine.cache.LoadingCache;
            import com.palantir.cache.AsyncLoadingCache;
            import com.palantir.cache.Cache;
            import java.util.Map;
            import java.util.concurrent.Executors;

            class Test {
                private interface List {}
                private AsyncLoadingCache<java.util.List<String>, Map<String, Integer>> cache;

                void setupCache() {
                    this.cache =
                            Cache.<java.util.List<String>, Map<String, Integer>>builder()
                                    .name("test-cache-0")
                                    .maximumSize(100)
                                    .noExpiry()
                                    .noMetrics()
                                    .executor(_name -> Executors.newCachedThreadPool())
                                    .buildAsyncWithLoader(this::load);
                }

                private Map<String, Integer> load(java.util.List<String> key) {
                    return Map.of();
                }
            }
            """.stripIndent();
        refactoringHelper()
                .addInputLines(
                        "Test.java",
                        // language=java
                        """
                        import com.github.benmanes.caffeine.cache.Caffeine;
                        import com.github.benmanes.caffeine.cache.LoadingCache;
                        import java.util.Map;

                        class Test {
                            private interface List {}
                            private LoadingCache<java.util.List<String>, Map<String, Integer>> cache;

                            void setupCache() {
                                this.cache = Caffeine.newBuilder()
                                        .maximumSize(100)
                                        .build(this::load);
                            }

                            private Map<String, Integer> load(java.util.List<String> key) {
                                return Map.of();
                            }
                        }
                        """.stripIndent())
                .addOutputLines("Test.java", output)
                .doTest(TestMode.TEXT_MATCH);
        assertCompiles("Test.java", output);
    }

    @SuppressWarnings("for-rollout:deprecation")
    @Test
    void refactors_multiple_caches_in_same_class() {
        // language=java
        String output = """
            import com.github.benmanes.caffeine.cache.Caffeine;
            import com.github.benmanes.caffeine.cache.LoadingCache;
            import com.palantir.cache.AsyncLoadingCache;
            import com.palantir.cache.Cache;
            import com.palantir.cache.Expiry;
            import java.time.Duration;
            import java.util.concurrent.Executors;

            class Test {
                private AsyncLoadingCache<String, String> firstCache;
                private AsyncLoadingCache<String, Integer> secondCache;

                void setupCaches() {
                    this.firstCache =
                            Cache.<String, String>builder()
                                    .name("test-cache-0")
                                    .maximumSize(100)
                                    .noExpiry()
                                    .noMetrics()
                                    .executor(_name -> Executors.newCachedThreadPool())
                                    .buildAsyncWithLoader(this::loadFirst);
                    this.secondCache =
                            Cache.<String, Integer>builder()
                                    .name("test-cache-1")
                                    .maximumSize(Long.MAX_VALUE)
                                    .expiry(Expiry.afterWrite(Duration.ofMinutes(1)))
                                    .noMetrics()
                                    .executor(_name -> Executors.newCachedThreadPool())
                                    .buildAsyncWithLoader(this::loadSecond);
                }

                private String loadFirst(String key) {
                    return key;
                }

                private int loadSecond(String key) {
                    return 0;
                }
            }
            """.stripIndent();
        refactoringHelper()
                .addInputLines(
                        "Test.java",
                        // language=java
                        """
                        import com.github.benmanes.caffeine.cache.Caffeine;
                        import com.github.benmanes.caffeine.cache.LoadingCache;
                        import java.time.Duration;

                        class Test {
                            private LoadingCache<String, String> firstCache;
                            private LoadingCache<String, Integer> secondCache;

                            void setupCaches() {
                                this.firstCache = Caffeine.newBuilder()
                                        .maximumSize(100)
                                        .build(this::loadFirst);
                                this.secondCache = Caffeine.newBuilder()
                                        .expireAfterWrite(Duration.ofMinutes(1))
                                        .build(this::loadSecond);
                            }

                            private String loadFirst(String key) {
                                return key;
                            }

                            private int loadSecond(String key) {
                                return 0;
                            }
                        }
                        """.stripIndent())
                .addOutputLines("Test.java", output)
                .doTest(TestMode.TEXT_MATCH);
        assertCompiles("Test.java", output);
    }

    @SuppressWarnings("for-rollout:deprecation")
    @Test
    void refactors_multiple_caches_in_multiple_classes() {
        // language=java
        String output = """
            package com.example;

            import com.github.benmanes.caffeine.cache.Caffeine;
            import com.github.benmanes.caffeine.cache.LoadingCache;
            import com.palantir.cache.AsyncLoadingCache;
            import com.palantir.cache.Cache;
            import com.palantir.cache.Expiry;
            import java.time.Duration;
            import java.util.concurrent.Executors;

            class Test {
                private AsyncLoadingCache<String, String> firstCache;
                private AsyncLoadingCache<String, Integer> secondCache;

                void setupCaches() {
                    this.firstCache =
                            Cache.<String, String>builder()
                                    .name("com-example-test-cache-0")
                                    .maximumSize(100)
                                    .noExpiry()
                                    .noMetrics()
                                    .executor(_name -> Executors.newCachedThreadPool())
                                    .buildAsyncWithLoader(this::loadFirst);
                    this.secondCache =
                            Cache.<String, Integer>builder()
                                    .name("com-example-test-cache-1")
                                    .maximumSize(Long.MAX_VALUE)
                                    .expiry(Expiry.afterWrite(Duration.ofMinutes(1)))
                                    .noMetrics()
                                    .executor(_name -> Executors.newCachedThreadPool())
                                    .buildAsyncWithLoader(this::loadSecond);
                }

                private String loadFirst(String key) {
                    return key;
                }

                private int loadSecond(String key) {
                    return 0;
                }

                private static final class InnerClass {
                    private AsyncLoadingCache<String, String> innerCache;

                    void setupInner() {
                        this.innerCache =
                                Cache.<String, String>builder()
                                        .name("com-example-test-inner-class-cache-0")
                                        .maximumSize(Long.MAX_VALUE)
                                        .expiry(Expiry.afterWrite(Duration.ofMinutes(1)))
                                        .noMetrics()
                                        .executor(_name -> Executors.newCachedThreadPool())
                                        .buildAsyncWithLoader(this::load);
                    }

                    private String load(String key) {
                        return key;
                    }
                }
            }
            """.stripIndent();
        refactoringHelper()
                .addInputLines(
                        "Test.java",
                        // language=java
                        """
                        package com.example;

                        import com.github.benmanes.caffeine.cache.Caffeine;
                        import com.github.benmanes.caffeine.cache.LoadingCache;
                        import java.time.Duration;

                        class Test {
                            private LoadingCache<String, String> firstCache;
                            private LoadingCache<String, Integer> secondCache;

                            void setupCaches() {
                                this.firstCache = Caffeine.newBuilder()
                                        .maximumSize(100)
                                        .build(this::loadFirst);
                                this.secondCache = Caffeine.newBuilder()
                                        .expireAfterWrite(Duration.ofMinutes(1))
                                        .build(this::loadSecond);
                            }

                            private String loadFirst(String key) {
                                return key;
                            }

                            private int loadSecond(String key) {
                                return 0;
                            }

                            private static final class InnerClass {
                                private LoadingCache<String, String> innerCache;

                                void setupInner() {
                                    this.innerCache = Caffeine.newBuilder()
                                            .expireAfterWrite(Duration.ofMinutes(1))
                                            .build(this::load);
                                }

                                private String load(String key) {
                                    return key;
                                }
                            }
                        }
                        """.stripIndent())
                .addOutputLines("Test.java", output)
                .doTest(TestMode.TEXT_MATCH);
        assertCompiles("Test.java", output);
    }
}
