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

import com.palantir.logsafe.exceptions.SafeIllegalArgumentException;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class BulkCacheLoaderTest {

    @Test
    void withMaximumBatchSize_withNonPositiveMaximumBatchSize_throwsIllegalArgument() {
        assertThatThrownBy(() -> BulkCacheLoader.withMaximumBatchSize(_keys -> Map.of(), 0))
                .isInstanceOf(SafeIllegalArgumentException.class);
    }

    @Test
    void withMaximumBatchSize_withLargerLimit_preservesDelegateLimit() {
        BulkCacheLoader<String, String> delegate = BulkCacheLoader.withMaximumBatchSize(_keys -> Map.of(), 1);
        assertThat(BulkCacheLoader.withMaximumBatchSize(delegate, 2).maximumBatchSize())
                .isEqualTo(1);
    }
}
