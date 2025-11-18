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

/**
 * A time source that returns a time value representing the number of nanoseconds elapsed since some
 * fixed but arbitrary point in time.
 */
public interface Ticker {

    /**
     * Returns the number of nanoseconds elapsed since this ticker's fixed point of reference.
     *
     * @return the number of nanoseconds elapsed since this ticker's fixed point of reference
     */
    long read();

    /**
     * Returns a ticker that reads the current time using {@link System#nanoTime}.
     *
     * @return a ticker that reads the current time using {@link System#nanoTime}
     */
    static Ticker system() {
        return SystemTicker.INSTANCE;
    }
}
