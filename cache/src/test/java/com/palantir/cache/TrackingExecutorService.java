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

import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;

final class TrackingExecutorService extends AbstractExecutorService {

    private final ExecutorService delegate;

    private final Phaser phaser = new Phaser(1);

    TrackingExecutorService(ExecutorService delegate) {
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable command) {
        phaser.register();
        try {
            delegate.execute(() -> {
                try {
                    command.run();
                } finally {
                    phaser.arriveAndDeregister();
                }
            });
        } catch (RuntimeException e) {
            phaser.arriveAndDeregister();
            throw e;
        }
    }

    void awaitAllTasks() {
        phaser.arriveAndAwaitAdvance();
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public java.util.List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }
}
