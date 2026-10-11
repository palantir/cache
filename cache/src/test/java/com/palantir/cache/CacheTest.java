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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.util.concurrent.MoreExecutors;
import com.palantir.logsafe.exceptions.SafeIllegalArgumentException;
import org.junit.jupiter.api.Test;

final class CacheTest {

    @Test
    @SuppressWarnings("deprecation")
    void name_validation() {
        assertThatCode(() -> {
                    Cache.<String, String>builder()
                            .name("valid-cache-name-123")
                            .maximumSize(1)
                            .noExpiry()
                            .noMetrics()
                            .executor(_name -> MoreExecutors.directExecutor())
                            .buildSync();
                })
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> {
                    Cache.<String, String>builder()
                            .name("Invalid.CACHE.name.###")
                            .maximumSize(1)
                            .noExpiry()
                            .noMetrics()
                            .executor(_name -> MoreExecutors.directExecutor())
                            .buildSync();
                })
                .isInstanceOf(SafeIllegalArgumentException.class);

        assertThatCode(() -> {
                    Cache.<String, String>builder()
                            .legacyName("Invalid.CACHE.name.###")
                            .maximumSize(1)
                            .noExpiry()
                            .noMetrics()
                            .executor(_name -> MoreExecutors.directExecutor())
                            .buildSync();
                })
                .doesNotThrowAnyException();
    }
}
