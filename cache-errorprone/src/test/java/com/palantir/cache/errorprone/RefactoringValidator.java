/*
 * (c) Copyright 2023 Palantir Technologies Inc. All rights reserved.
 */

package com.palantir.cache.errorprone;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.errorprone.BugCheckerRefactoringTestHelper;
import com.google.errorprone.CompilationTestHelper;
import com.google.errorprone.annotations.CheckReturnValue;
import com.google.errorprone.bugpatterns.BugChecker;

final class RefactoringValidator {

    private final BugCheckerRefactoringTestHelper delegate;
    private final CompilationTestHelper compilationHelper;
    private String outputPath;
    private String[] outputLines;

    private RefactoringValidator(Class<? extends BugChecker> checkerClass, Class<?> clazz, String... args) {
        this.delegate =
                BugCheckerRefactoringTestHelper.newInstance(checkerClass, clazz).setArgs(args);
        this.compilationHelper =
                CompilationTestHelper.newInstance(checkerClass, clazz).setArgs(args);
    }

    @CheckReturnValue
    static RefactoringValidator of(Class<? extends BugChecker> checkerClass, Class<?> clazz, String... args) {
        return new RefactoringValidator(checkerClass, clazz, args);
    }

    @CheckReturnValue
    OutputStage addInputLines(String path, String... input) {
        // If expectUnchanged is unused, the input is used as output
        this.outputPath = path;
        this.outputLines = input;
        return new OutputStage(this, delegate.addInputLines(path, input));
    }

    static final class OutputStage {
        private final RefactoringValidator helper;
        private final BugCheckerRefactoringTestHelper.ExpectOutput delegate;

        private OutputStage(RefactoringValidator helper, BugCheckerRefactoringTestHelper.ExpectOutput delegate) {
            this.helper = helper;
            this.delegate = delegate;
        }

        @CheckReturnValue
        TestStage addOutputLines(String path, String... output) {
            helper.outputPath = path;
            helper.outputLines = output;
            return new TestStage(helper, delegate.addOutputLines(path, output));
        }

        @CheckReturnValue
        TestStage expectUnchanged() {
            return new TestStage(helper, delegate.expectUnchanged());
        }
    }

    static final class TestStage {

        private final RefactoringValidator helper;
        private final BugCheckerRefactoringTestHelper delegate;

        private TestStage(RefactoringValidator helper, BugCheckerRefactoringTestHelper delegate) {
            this.helper = helper;
            this.delegate = delegate;
        }

        void doTest() {
            delegate.doTest();
            helper.compilationHelper
                    .addSourceLines(helper.outputPath, helper.outputLines)
                    .matchAllDiagnostics()
                    .doTest();
        }

        void doTest(BugCheckerRefactoringTestHelper.TestMode testMode) {
            delegate.doTest(testMode);
            helper.compilationHelper
                    .addSourceLines(helper.outputPath, helper.outputLines)
                    .matchAllDiagnostics()
                    .doTest();
        }

        void doTestExpectingFailure(BugCheckerRefactoringTestHelper.TestMode testMode) {
            delegate.doTest(testMode);
            assertThatThrownBy(() -> helper.compilationHelper
                            .addSourceLines(helper.outputPath, helper.outputLines)
                            .doTest())
                    .describedAs("Expected the result to fail validation")
                    .isInstanceOf(AssertionError.class);
        }
    }
}
