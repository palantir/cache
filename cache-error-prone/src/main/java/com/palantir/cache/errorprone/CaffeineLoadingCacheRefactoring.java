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

import com.google.auto.service.AutoService;
import com.google.common.base.Ascii;
import com.google.common.base.CharMatcher;
import com.google.common.collect.Iterables;
import com.google.errorprone.BugPattern;
import com.google.errorprone.VisitorState;
import com.google.errorprone.bugpatterns.BugChecker;
import com.google.errorprone.fixes.SuggestedFix;
import com.google.errorprone.fixes.SuggestedFixes;
import com.google.errorprone.matchers.Description;
import com.google.errorprone.matchers.Matcher;
import com.google.errorprone.matchers.Matchers;
import com.google.errorprone.util.ASTHelpers;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.processing.JavacProcessingEnvironment;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.immutables.value.Value;

@AutoService(BugChecker.class)
@BugPattern(
        link = "https://github.com/palantir/cache/cache-errorprone",
        linkType = BugPattern.LinkType.CUSTOM,
        severity = BugPattern.SeverityLevel.SUGGESTION,
        summary = "Where possible, refactor Caffeine LoadingCache to com.palantir.cache.AsyncLoadingCache.")
public final class CaffeineLoadingCacheRefactoring extends BugChecker
        implements BugChecker.MethodInvocationTreeMatcher {

    // this check attempts to convert a Caffeine LoadingCache to a com.palantir.cache.AsyncLoadingCache
    // it currently takes a very conservative approach and will only catch very narrow cases, but we can expand
    // this over time to handle more complex scenarios.
    //
    // approach:
    // - start by matching calls to `Caffeine#build(CacheLoader)`, which are the construction points for LoadingCache
    // - we place restrictions on which methods may be invoked in this compilation unit on the constructed cache;
    //   some methods are not easily mirrored by the com.palantir.cache library, and we don't attempt to convert
    //   in those cases. Note that this requires a _second_ pass over the AST to find usages of the symbol for the cache
    //   itself.
    // - we additionally place restrictions on which builder methods may be invoked, as certain methods we cannot easily
    //   convert automatically yet with this check
    // - assuming the above checks pass and we determine we can convert this cache, we:
    //     - change the declared type on the cache variable/field to be com.palantir.cache.AsyncLoadingCache (and
    //       retain the original type parameters)
    //     - rewrite the fluid builder methods to construct a com.palantir.cache.AsyncLoadingCache
    //
    // Note that building an AsyncLoadingCache also requires a com.palantir.cache.ExecutorFactory.
    // Ideally this is something that could be passed in from an enclosing scope, giving callers control over what
    // ExecutorFactory instance will be used. For now to keep things simple, we just use
    // Executors.newCachedThreadPool(),
    // but this is perhaps a future improvement to consider.
    //
    // Also note that this check doesn't try to clean up unused imports if they still exist after refactoring; we leave
    // that to other automation to handle separately (e.g. via a ./gradlew format)

    private static final String CAFFEINE_CACHE = "com.github.benmanes.caffeine.cache.Cache";

    // matchers for allowed cache methods, i.e. methods on com.github.benmanes.caffeine.cache.Cache or derivatives
    // that we allow to be called. Note that we only check the current compilation unit, and there isn't any fancy
    // escape analysis to determine if the cache we're trying to refactor is returned by a public method, etc.
    private static final class AllowedCacheMethods {
        private static final Matcher<ExpressionTree> GET_IF_PRESENT = Matchers.instanceMethod()
                .onDescendantOf(CAFFEINE_CACHE)
                .named("getIfPresent")
                .withParameters(Object.class.getName());

        private static final Matcher<ExpressionTree> GET = Matchers.instanceMethod()
                .onDescendantOf(CAFFEINE_CACHE)
                .named("get")
                .withParameters(Object.class.getName());

        private static final Matcher<ExpressionTree> GET_WITH_LOADER = Matchers.instanceMethod()
                .onDescendantOf(CAFFEINE_CACHE)
                .named("get")
                .withParameters(Object.class.getName(), Function.class.getName());

        private static final Matcher<ExpressionTree> GET_ALL_PRESENT = Matchers.instanceMethod()
                .onDescendantOf(CAFFEINE_CACHE)
                .named("getAllPresent")
                .withParameters(Iterable.class.getName());

        private static final Matcher<ExpressionTree> GET_ALL = Matchers.instanceMethod()
                .onDescendantOf(CAFFEINE_CACHE)
                .named("getAll")
                .withParameters(Iterable.class.getName(), Function.class.getName());

        private static final Matcher<ExpressionTree> PUT = Matchers.instanceMethod()
                .onDescendantOf(CAFFEINE_CACHE)
                .named("put")
                .withParameters(Object.class.getName(), Object.class.getName());

        private static final Matcher<ExpressionTree> PUT_ALL = Matchers.instanceMethod()
                .onDescendantOf(CAFFEINE_CACHE)
                .named("putAll")
                .withParameters(Map.class.getName());

        private static final Matcher<ExpressionTree> INVALIDATE = Matchers.instanceMethod()
                .onDescendantOf(CAFFEINE_CACHE)
                .named("invalidate")
                .withParameters(Object.class.getName());

        private static final Matcher<ExpressionTree> INVALIDATE_ALL_FROM_KEYS = Matchers.instanceMethod()
                .onDescendantOf(CAFFEINE_CACHE)
                .named("invalidateAll")
                .withParameters(Iterable.class.getName());

        private static final Matcher<ExpressionTree> INVALIDATE_ALL = Matchers.instanceMethod()
                .onDescendantOf(CAFFEINE_CACHE)
                .named("invalidateAll")
                .withNoParameters();

        private static final Matcher<ExpressionTree> ALL_ALLOWED = Matchers.anyOf(
                GET_IF_PRESENT,
                GET,
                GET_WITH_LOADER,
                GET_ALL_PRESENT,
                GET_ALL,
                PUT,
                PUT_ALL,
                INVALIDATE,
                INVALIDATE_ALL_FROM_KEYS,
                INVALIDATE_ALL);
    }

    private static final String CAFFEINE_CLASS = "com.github.benmanes.caffeine.cache.Caffeine";

    // matchers for allowed builder methods, i.e. methods on com.github.benmanes.caffeine.cache.Caffeine
    // not all of caffeine's fluid builer methods are supported
    private static final class AllowedBuilderMethods {
        private static final Matcher<ExpressionTree> NEW_BUILDER = Matchers.staticMethod()
                .onDescendantOf(CAFFEINE_CLASS)
                .named("newBuilder")
                .withNoParameters();

        private static final Matcher<ExpressionTree> MAXIMUM_SIZE = Matchers.instanceMethod()
                .onDescendantOf(CAFFEINE_CLASS)
                .named("maximumSize")
                .withParameters(long.class.getName());

        private static final Matcher<ExpressionTree> EXPIRE_AFTER_WRITE_DURATION = Matchers.instanceMethod()
                .onDescendantOf(CAFFEINE_CLASS)
                .named("expireAfterWrite")
                .withParameters(Duration.class.getName());

        private static final Matcher<ExpressionTree> EXPIRE_AFTER_WRITE_TIME_UNIT = Matchers.instanceMethod()
                .onDescendantOf(CAFFEINE_CLASS)
                .named("expireAfterWrite")
                .withParameters(long.class.getName(), TimeUnit.class.getName());

        private static final Matcher<ExpressionTree> EXPIRE_AFTER_ACCESS_DURATION = Matchers.instanceMethod()
                .onDescendantOf(CAFFEINE_CLASS)
                .named("expireAfterAccess")
                .withParameters(Duration.class.getName());

        private static final Matcher<ExpressionTree> EXPIRE_AFTER_ACCESS_TIME_UNIT = Matchers.instanceMethod()
                .onDescendantOf(CAFFEINE_CLASS)
                .named("expireAfterAccess")
                .withParameters(long.class.getName(), TimeUnit.class.getName());

        // we don't currently support calls to `.expireAfter(Expiry)`
        // although the palantir cache library exposes an interface that is 1-1 compatible with Caffeine's and we could
        // do this in a straightforward way, there are quite a few edge cases to handle within errorprone depending on
        // how an Expiry object is handed to the builder.
        // this should be doable and is a future optimization, but to keep things simple right now we just skip it.
        // TODO(blaub): add this in when we're ready to handle expireAfter() calls correctly
        //        private static final Matcher<ExpressionTree> EXPIRE_AFTER = Matchers.instanceMethod()
        //                    .onDescendantOf(CAFFEINE_CLASS)
        //                    .named("expireAfter")
        //                    .withParameters("com.github.benmanes.caffeine.cache.Expiry");

        // we also don't currently handle `.ticker(Ticker)` calls, for the same reasons as `.expireAfter(Expiry)`.
        // this is a future optimization
        // TODO(blaub): add this in when we're ready to handle ticker() calls correctly
        //        private static final Matcher<ExpressionTree> TICKER = Matchers.instanceMethod()
        //                    .onDescendantOf(CAFFEINE_CLASS)
        //                    .named("ticker")
        //                    .withParameters("com.github.benmanes.caffeine.cache.Ticker");

        // recordStats() is allowed, but gets removed since we already scaffold metrics directly in com.palantir.cache
        private static final Matcher<ExpressionTree> RECORD_STATS = Matchers.instanceMethod()
                .onDescendantOf(CAFFEINE_CLASS)
                .named("recordStats")
                .withNoParameters();

        private static final Matcher<ExpressionTree> RECORD_STATS_SUPPLIER = Matchers.instanceMethod()
                .onDescendantOf(CAFFEINE_CLASS)
                .named("recordStats")
                .withParameters(Supplier.class.getName());

        private static final Matcher<ExpressionTree> ALL_ALLOWED = Matchers.anyOf(
                NEW_BUILDER,
                MAXIMUM_SIZE,
                EXPIRE_AFTER_WRITE_DURATION,
                EXPIRE_AFTER_WRITE_TIME_UNIT,
                EXPIRE_AFTER_ACCESS_DURATION,
                EXPIRE_AFTER_ACCESS_TIME_UNIT,
                // see notes above
                //                EXPIRE_AFTER,
                //                TICKER,
                RECORD_STATS,
                RECORD_STATS_SUPPLIER);
    }

    private static final String CACHE_STATS_CLASS = "com.palantir.tritium.metrics.caffeine.CacheStats";

    private static final Matcher<ExpressionTree> CACHE_STATS_REGISTER =
            Matchers.instanceMethod().onDescendantOf(CACHE_STATS_CLASS).named("register");
    private static final Matcher<ExpressionTree> CACHE_STATS_OF = Matchers.staticMethod()
            .onClass(CACHE_STATS_CLASS)
            .named("of")
            .withParameters("com.palantir.tritium.metrics.registry.TaggedMetricRegistry", String.class.getName());

    private static final Matcher<ExpressionTree> CAFFEINE_LOADING_CACHE_BUILD = Matchers.instanceMethod()
            .onDescendantOf(CAFFEINE_CLASS)
            .named("build")
            .withParameters("com.github.benmanes.caffeine.cache.CacheLoader");

    @Override
    public Description matchMethodInvocation(MethodInvocationTree tree, VisitorState state) {
        // are we looking at Caffeine.newBuilder()...build(CacheLoader)?
        if (!CAFFEINE_LOADING_CACHE_BUILD.matches(tree, state)) {
            return Description.NO_MATCH;
        }

        // for now we only support matching on fluent builder patterns, because the logic is much simpler to handle
        if (!usesFluentBuilder(tree, state)) {
            return Description.NO_MATCH;
        }

        // are we using any banned builder methods?
        if (usesBannedBuilderMethods(tree, state)) {
            return Description.NO_MATCH;
        }

        // get the assigned symbol (i.e. a variable or field being assigned to)
        // TODO(blaub): there is a third case we aren't handling here, which is a return value from a method, e.g.
        //     private final Cache<K, V> cache = buildCache();
        // where buildCache() is some static method that returns a caffeine cache
        Optional<Symbol> maybeSymbol = getAssignedSymbol(state);
        if (maybeSymbol.isEmpty()) {
            return Description.NO_MATCH;
        }

        if (!(maybeSymbol.get() instanceof VarSymbol varSymbol)) {
            return Description.NO_MATCH;
        }

        // walk the AST again to look for usage of banned methods on that symbol
        // we also collect up fixes to refactor cache reads to check for nullness at the same time
        CheckCacheMethodUsageResults checkCacheMethodUsageResults = checkAndRefactorCacheMethodUsages(varSymbol, state);
        if (checkCacheMethodUsageResults.usesBannedCacheMethods()) {
            return Description.NO_MATCH;
        }

        Optional<SuggestedFix> maybeFix = buildFix(varSymbol, tree, state);
        SuggestedFix fixCacheReads = checkCacheMethodUsageResults
                .fixCacheReads()
                .orElseGet(() -> SuggestedFix.builder().build());
        return maybeFix.map(fix -> buildDescription(tree)
                        .setMessage("This Caffeine LoadingCache is a candidate for conversion to"
                                + " com.palantir.cache.AsyncLoadingCache.")
                        .addFix(SuggestedFix.builder()
                                .merge(fix)
                                .merge(fixCacheReads)
                                .build())
                        .build())
                .orElse(Description.NO_MATCH);
    }

    private record CheckCacheMethodUsageResults(boolean usesBannedCacheMethods, Optional<SuggestedFix> fixCacheReads) {}

    private CheckCacheMethodUsageResults checkAndRefactorCacheMethodUsages(VarSymbol varSymbol, VisitorState state) {
        // this method accomplishes two things:
        // (1) it checks for any usages of banned methods on the cache object itself
        // (2) it potentially refactors retrieval method (e.g. a call to get()) to be wrapped in a
        //     Preconditions.checkNotNull(), to appease NullAway
        // we combine the two steps here because they both require another pass over the AST looking specifically at
        // method invocations on the cache object itself, and we can easily get results for both in a single pass
        // the TreeScanner below returns a Boolean, where true means we saw an invocation on a banned cache method;
        // as a side-effect, if it encounters a call to get() (or one of its variants), it will append fixes to a
        // SuggestedFix to refactor those to include a Preconditions.checkNotNull call as well
        // we return both from this method, though in the case that we found a banned cache method call we always
        // return an empty optional for the suggested fix
        SuggestedFix.Builder fixBuilder = SuggestedFix.builder();
        if (varSymbol != null) {
            Boolean maybeResult = state.getPath()
                    .getCompilationUnit()
                    .accept(
                            new TreeScanner<Boolean, Void>() {
                                @Override
                                public Boolean reduce(Boolean lhs, Boolean rhs) {
                                    // fail if any invocation is on a banned method
                                    return Boolean.TRUE.equals(lhs) || Boolean.TRUE.equals(rhs);
                                }

                                @Override
                                public Boolean visitMethodInvocation(MethodInvocationTree tree, Void _unused) {
                                    ExpressionTree receiver = ASTHelpers.getReceiver(tree);
                                    Symbol thisSymbol = ASTHelpers.getSymbol(receiver);
                                    if (thisSymbol != null) {
                                        // are we looking at a method invocation on the caffeine cache symbol we are
                                        // trying to convert?
                                        if (thisSymbol.equals(varSymbol)) {
                                            // are we invoking a banned method?
                                            if (!AllowedCacheMethods.ALL_ALLOWED.matches(tree, state)) {
                                                // not allowed to convert this cache
                                                return true;
                                            }

                                            // are we invoking any get method that might return null?
                                            // if so, refactor it
                                            // TODO(blaub): does not check for cases where the read was already
                                            // wrapped in a null check, or that the check is performed elsewhere
                                            if (AllowedCacheMethods.GET.matches(tree, state)
                                                    || AllowedCacheMethods.GET_IF_PRESENT.matches(tree, state)
                                                    || AllowedCacheMethods.GET_WITH_LOADER.matches(tree, state)) {
                                                String preconditionsType = SuggestedFixes.qualifyType(
                                                        state, fixBuilder, "com.palantir.logsafe.Preconditions");
                                                fixBuilder
                                                        .prefixWith(tree, preconditionsType + ".checkNotNull(")
                                                        .postfixWith(tree, ")");
                                            }
                                        }
                                    }
                                    return super.visitMethodInvocation(tree, null);
                                }
                            },
                            null);
            // TreeScanner may return null
            if (Boolean.TRUE.equals(maybeResult)) {
                return new CheckCacheMethodUsageResults(true, Optional.empty());
            }
        }
        return new CheckCacheMethodUsageResults(false, Optional.of(fixBuilder.build()));
    }

    @SuppressWarnings({"CyclomaticComplexity", "MethodLength"})
    private Optional<SuggestedFix> buildFix(VarSymbol varSymbol, MethodInvocationTree buildCall, VisitorState state) {
        if (varSymbol == null
                || varSymbol.type == null
                || varSymbol.type.getTypeArguments().size() != 2) {
            // varSymbol must be a parameterized type with length 2 (e.g. "Cache<Key, Value>")
            return Optional.empty();
        }

        Optional<String> inferredName = inferNameArg(varSymbol, state);
        // we should always be able to derive a name for the cache, even if we end up not using it
        // if we can't, then we can't actually refactor this cache
        if (inferredName.isEmpty()) {
            return Optional.empty();
        }

        ImmutableCacheBuilderSpec.Builder specBuilder = ImmutableCacheBuilderSpec.builder();

        // check if we are wrapped in a "CacheStats.of(...)"
        // if yes, the first arg is a TaggedMetricRegistry to use for metrics, and the second arg is a cache name
        Optional<MethodInvocationTree> maybeCacheStatsWrapper = findCacheStatsOf(state);
        if (maybeCacheStatsWrapper.isPresent()) {
            MethodInvocationTree cacheStatsOf = maybeCacheStatsWrapper.get();
            List<? extends ExpressionTree> arguments = cacheStatsOf.getArguments();
            if (arguments.size() == 2) {
                // set the name and metrics based on arguments
                String metricsArg = state.getSourceForNode(arguments.get(0));
                specBuilder.metricsArg(Optional.ofNullable(metricsArg));

                String nameArg = state.getSourceForNode(arguments.get(1));
                specBuilder.nameArg(new CacheName(Objects.requireNonNullElseGet(nameArg, inferredName::get)));
            }
        } else {
            specBuilder.nameArg(new CacheName(inferredName.get()));
        }

        // buildCall points to the invocation tree for Caffeine.newBuilder...build(loaderFn) - the loaderArg
        // to our new cache should be the same
        String loaderArg = state.getSourceForNode(Iterables.getOnlyElement(buildCall.getArguments()));
        if (loaderArg == null) {
            // buildCall must have a single argument
            return Optional.empty();
        }
        specBuilder.loaderArg(loaderArg);

        SuggestedFix.Builder fixBuilder = SuggestedFix.builder();

        String typeParams = varSymbol.type.getTypeArguments().stream()
                .map(typeArg -> SuggestedFixes.qualifyType(state, fixBuilder, typeArg))
                .collect(Collectors.joining(", "));
        specBuilder.typeParams("<" + typeParams + ">");

        // currently, we always use "Executors.newCachedThreadPool" for the executor arg, but in the future might be
        // something different
        String executorsType = SuggestedFixes.qualifyType(state, fixBuilder, "java.util.concurrent.Executors");
        specBuilder.executorArg("_name -> " + executorsType + ".newCachedThreadPool()");

        // try to capture arguments from existing builder methods
        ASTHelpers.streamReceivers(buildCall).forEach(builderCallTree -> {
            if (AllowedBuilderMethods.MAXIMUM_SIZE.matches(builderCallTree, state)) {
                // convert "maximumSize(long)" -> "maximumSize(long)"
                if (builderCallTree instanceof MethodInvocationTree t) {
                    String maximumSizeArg = state.getSourceForNode(Iterables.getOnlyElement(t.getArguments()));
                    if (maximumSizeArg != null) {
                        specBuilder.maximumSizeArg(maximumSizeArg);
                    }
                }
            } else if (AllowedBuilderMethods.EXPIRE_AFTER_WRITE_DURATION.matches(builderCallTree, state)
                    || AllowedBuilderMethods.EXPIRE_AFTER_WRITE_TIME_UNIT.matches(builderCallTree, state)) {
                // convert "expireAfterWrite(...)" -> "expiry(Expiry)"
                if (builderCallTree instanceof MethodInvocationTree t) {
                    getExpiryArgForExpireAfter(t, state, WriteOrAccess.WRITE).ifPresent(specBuilder::expiryArg);
                }
            } else if (AllowedBuilderMethods.EXPIRE_AFTER_ACCESS_DURATION.matches(builderCallTree, state)
                    || AllowedBuilderMethods.EXPIRE_AFTER_ACCESS_TIME_UNIT.matches(builderCallTree, state)) {
                // convert "expireAfterAccess(...)" -> "expiry(Expiry)"
                if (builderCallTree instanceof MethodInvocationTree t) {
                    getExpiryArgForExpireAfter(t, state, WriteOrAccess.ACCESS).ifPresent(specBuilder::expiryArg);
                }
            }
        });

        CacheBuilderSpec spec = specBuilder.build();

        // construct the replacement expression
        String cacheType = SuggestedFixes.qualifyType(state, fixBuilder, "com.palantir.cache.Cache");
        StringBuilder replacement = new StringBuilder();
        replacement.append(cacheType).append(".").append(spec.typeParams()).append("builder()");

        // set the cache name
        // see: https://github.com/palantir/cache/pull/77
        if (spec.nameArg().isLegacy()) {
            // legacyName is deprecated, so we must also add a suppression for that, which isn't ideal
            // however, using legacyName allows us to preserve existing metrics flows for caches that were
            // wrapped in a CacheStats.of(...) that used a string for metrics reporting that is incompatible with
            // the palantir cache library's name() requirements.
            SuggestedFixes.addSuppressWarnings(
                    fixBuilder,
                    state,
                    "for-rollout:deprecation",
                    "legacyName() is used to preserve metric flows for existing caches; consider changing the cache"
                            + " name to one compatible with com.palantir.cache.CacheBuilder#name()",
                    true);
            replacement.append(".legacyName(").append(spec.nameArg().name()).append(")");
        } else {
            replacement.append(".name(").append(spec.nameArg().name()).append(")");
        }
        replacement.append(".maximumSize(").append(spec.maximumSizeArg()).append(")");
        spec.expiryArg()
                .ifPresentOrElse(
                        expiryArg -> {
                            replacement.append(".expiry(").append(expiryArg).append(")");
                            // make sure we add imports for Duration, and com.palantir.cache.Expiry
                            fixBuilder.addImport("java.time.Duration");
                            fixBuilder.addImport("com.palantir.cache.Expiry");
                        },
                        () -> replacement.append(".noExpiry()"));
        spec.metricsArg()
                .ifPresentOrElse(
                        metricsArg -> replacement
                                .append(".metrics(")
                                .append(metricsArg)
                                .append(")"),
                        () -> replacement.append(".noMetrics()"));
        replacement.append(".executor(").append(spec.executorArg()).append(")");
        //        spec.tickerArg()
        //                .ifPresent(tickerArg ->
        //                        replacement.append(".ticker(").append(tickerArg).append(")"));
        replacement.append(".buildAsyncWithLoader(").append(spec.loaderArg()).append(")");

        // if we are wrapped in a "CacheStats.of(...).register(...)", then look for that and replace that whole tree;
        // otherwise, replace the "Caffeine.newBuilder()...build(...)" tree
        Optional<ExpressionTree> cacheStatsRegister = findCacheStatsRegister(state);
        fixBuilder.replace(cacheStatsRegister.orElse(buildCall), replacement.toString());

        // fix the declared type on the assigned cache (varSymbol points to the symbol being assigned to)
        // this will add the import to the fix builder as well
        changeDeclaredTypeTo(
                varSymbol,
                "com.palantir.cache.AsyncLoadingCache",
                // TODO(blaub): this shouldn't be necessary, since changeDeclaredTypeTo can keep existing type params
                // however, we still need it to make the palantir Cache builder happy with the compiler :(
                // Optional.of(spec.typeParams()),
                Optional.empty(),
                // TODO(blaub): i think we want `true` here; we run the risk of name clashes between
                // com.github.benmanes.caffeine.cache.AsyncLoadingCache and com.palantir.cache.AsyncLoadingCache
                // within the same compilation unit
                false,
                fixBuilder,
                state);

        return Optional.of(fixBuilder.build());
    }

    //    @Nullable
    //    private VarSymbol getVariableSymbol(ExpressionTree expr) {
    //        if (expr instanceof IdentifierTree) {
    //            return (VarSymbol) ASTHelpers.getSymbol(expr);
    //        } else if (expr instanceof MemberSelectTree) {
    //            // Handle this.myVar
    //            return (VarSymbol) ASTHelpers.getSymbol(expr);
    //        }
    //        return null;
    //    }

    private Optional<Symbol> getAssignedSymbol(VisitorState state) {
        // walk up the tree until we find an assignment or variable declaration
        TreePath path = state.getPath();

        while (path != null) {
            Tree leaf = path.getLeaf();

            // handle variable declaration with initialization
            if (leaf instanceof VariableTree variableTree) {
                return Optional.of(ASTHelpers.getSymbol(variableTree));
            }

            // handle assignment to existing variable
            // TODO(blaub): do we need to additionally handle IdentifierTree or MemberSelectTree? see getVariableSymbol
            if (leaf instanceof AssignmentTree assignmentTree) {
                return Optional.ofNullable(ASTHelpers.getSymbol(assignmentTree.getVariable()));
            }

            path = path.getParentPath();
        }

        return Optional.empty();
    }

    private boolean usesFluentBuilder(MethodInvocationTree tree, VisitorState state) {
        // return true if a caffeine cache is constructed using a fluent builder pattern with a single set of
        // chained calls
        // the pattern we're looking for is something like this:
        //     cache = Caffeine.newBuilder()
        //             .maximumSize(100)
        //             .expireAfterWrite(Duration.ofSeconds(10))
        //             .build(this::load);
        // and explicitly NOT something like this:
        //     Caffeine<K, V> builder = Caffeine.newBuilder();
        //     builder.maximumSize(100);
        //     builder.expireAfterWrite(Duration.ofSEconds(10));
        //     cache = builder.build(this::load);
        //
        // the latter is a bit more complicated to handle, so for now we just don't match on it
        List<ExpressionTree> receivers = ASTHelpers.streamReceivers(tree).toList();
        if (receivers.size() >= 2) {
            // matched "build(CacheLoader)" invocation is chained off of "Caffeine.newBuilder()", with potentially
            // many fluent builder calls in between
            return AllowedBuilderMethods.NEW_BUILDER.matches(receivers.get(receivers.size() - 2), state);
        }
        return false;
    }

    private boolean usesBannedBuilderMethods(MethodInvocationTree tree, VisitorState state) {
        return ASTHelpers.streamReceivers(tree)
                .filter(et -> et instanceof MethodInvocationTree)
                .anyMatch(et -> !AllowedBuilderMethods.ALL_ALLOWED.matches(et, state));
    }

    // locate a Tree for "CacheStats#of"
    // this is expected to be the receiver of "CacheStats.of(...).register(...)", where the argument to register()
    // is typically a lambda that will contain the Caffeine.build(...) method we are actually interested in
    private Optional<MethodInvocationTree> findCacheStatsOf(VisitorState state) {
        TreePath path = state.getPath();

        while (path != null) {
            Tree tree = path.getLeaf();

            if (tree instanceof ExpressionTree expressionTree) {
                // is this a CacheStats.register call?
                if (CACHE_STATS_REGISTER.matches(expressionTree, state)) {
                    ExpressionTree receiver = ASTHelpers.getReceiver(expressionTree);
                    // is it chained off of a CacheStats.of(...)?
                    if (receiver instanceof MethodInvocationTree receiverMit
                            && CACHE_STATS_OF.matches(receiverMit, state)) {
                        return Optional.of(receiverMit);
                    }
                }
            }

            path = path.getParentPath();
        }

        return Optional.empty();
    }

    // locate a Tree for "CacheStats#register"
    // this is expected to be the method invocation for "CacheStats.of(...).register(...)", where the argument to
    // register() is typically a lambda that will contain the Caffeine.build(...) method we are actually interested in
    private Optional<ExpressionTree> findCacheStatsRegister(VisitorState state) {
        TreePath path = state.getPath();

        while (path != null) {
            Tree tree = path.getLeaf();

            if (tree instanceof ExpressionTree expressionTree) {
                if (CACHE_STATS_REGISTER.matches(expressionTree, state)) {
                    return Optional.of(expressionTree);
                }
            }

            path = path.getParentPath();
        }

        return Optional.empty();
    }

    private Optional<String> getExpiryArgForExpireAfter(
            MethodInvocationTree expiryTree, VisitorState state, WriteOrAccess writeOrAccess) {
        List<? extends ExpressionTree> arguments = expiryTree.getArguments();
        if (arguments.size() == 1) {
            // single arg: Duration
            String durationArg = state.getSourceForNode(Iterables.getOnlyElement(arguments));
            if (durationArg != null) {
                return Optional.of(writeOrAccess.formatExpiry(durationArg));
            }
        } else if (arguments.size() == 2) {
            // two args: long, TimeUnit
            String duration = state.getSourceForNode(arguments.get(0));
            String timeUnit = state.getSourceForNode(arguments.get(1));
            if (duration != null && timeUnit != null) {
                return Optional.of(writeOrAccess.formatExpiry(duration, timeUnit));
            }
        }
        return Optional.empty();
    }

    // given a VarSymbol, change the declared type to the given class
    // newType is a string containing the fully-qualified class name for the new type
    // if the existing type is a parameterized type, the type parameters are retained unless newTypeParams is present
    // if newTypeParams is present, it is taken to be a string containing already-formatted type arguments for a
    // parameterized type
    // if explicitlyQualify is true, we use a fully-qualified name at the declaration site rather than adding an import
    private static void changeDeclaredTypeTo(
            VarSymbol varSymbol,
            String newType,
            Optional<String> newTypeParams,
            boolean explicitlyQualify,
            SuggestedFix.Builder fix,
            VisitorState state) {
        JavacProcessingEnvironment javacEnv = JavacProcessingEnvironment.instance(state.context);
        Tree declTree = Trees.instance(javacEnv).getTree(varSymbol);
        if (declTree instanceof VariableTree varDecl) {
            String actualNewType = explicitlyQualify ? newType : SuggestedFixes.qualifyType(state, fix, newType);
            if (newTypeParams.isPresent()) {
                actualNewType += newTypeParams.get();
            } else {
                // try to retain existing type parameters, if they exist
                List<String> retainedTypeParams = new ArrayList<>();
                if (varDecl.getType() instanceof ParameterizedTypeTree paramTypeTree) {
                    paramTypeTree.getTypeArguments().forEach(typeArgTree -> {
                        retainedTypeParams.add(state.getSourceForNode(typeArgTree));
                    });
                }
                if (!retainedTypeParams.isEmpty()) {
                    actualNewType += "<" + String.join(", ", retainedTypeParams) + ">";
                }
            }
            fix.replace(varDecl.getType(), actualNewType);
        }
    }

    // TODO(blaub): this logic is a bit wonky
    // if we have multiple caches in the same class that are refactored, we will end up with conflicting cache names
    // we could potentially try to preserve some state within this class (like an AtomicInteger) that gets used
    // as a suffix to uniquely identify caches, but that may not be fool-proof; i'm not sure how often errorprone
    // will invoke this BugChecker on a single compilation unit
    private Optional<String> inferNameArg(VarSymbol varSymbol, VisitorState state) {
        // use the fully-qualified name to the nearest enclosing class for the cache being constructed, and
        // convert to kebab-case to make it compatible with the caching library
        ClassTree enclosingClass = ASTHelpers.findEnclosingNode(state.getPath(), ClassTree.class);
        if (enclosingClass != null) {
            Symbol classSymbol = ASTHelpers.getSymbol(enclosingClass);
            if (classSymbol != null) {
                String varSymbolSuffix =
                        varSymbol != null ? "-" + varSymbol.getQualifiedName().toString() : "";
                String sanitizedCacheName =
                        toLowerKebabCase(classSymbol.getQualifiedName().toString() + varSymbolSuffix);
                return Optional.of("\"" + sanitizedCacheName + "\"");
            }
        }
        return Optional.empty();
    }

    private static String toLowerKebabCase(String input) {
        // Handle fully-qualified class names: com.foo.BarBaz -> com-foo-bar-baz
        return Ascii.toLowerCase(input.replace('.', '-').replaceAll("([a-z])([A-Z])", "$1-$2"));
    }

    private enum WriteOrAccess {
        WRITE("Write"),
        ACCESS("Access");

        private final String suffix;

        WriteOrAccess(String suffix) {
            this.suffix = suffix;
        }

        String formatExpiry(String durationArg) {
            return "Expiry.after" + suffix + "(" + durationArg + ")";
        }

        String formatExpiry(String durationArg, String timeUnitArg) {
            return "Expiry.after" + suffix + "(Duration.of(" + durationArg + ", " + timeUnitArg + ".toChronoUnit()))";
        }
    }

    // used to build up arguments to a new fluent builder chain
    @Value.Immutable
    interface CacheBuilderSpec {
        String typeParams();

        CacheName nameArg();

        @Value.Default
        default String maximumSizeArg() {
            return "Long.MAX_VALUE";
        }

        Optional<String> expiryArg();

        Optional<String> metricsArg();

        String executorArg();

        // Optional<String> tickerArg();

        String loaderArg();
    }

    record CacheName(String name, boolean isLegacy) {
        CacheName(String name) {
            this(name, requiresLegacyName(name));
        }

        // silly heuristic: is the chosen name arg compatible with com.palantir.cache.CacheBuilder#name(), or do we need
        // to use com.palantir.cache.CacheBuilder#legacyName()?
        // see:
        // https://github.com/palantir/cache/blob/develop/cache/src/main/java/com/palantir/cache/CacheBuilder.java#L40-L43
        private static final CharMatcher NAME_MATCHER = CharMatcher.inRange('a', 'z')
                .or(CharMatcher.inRange('0', '9'))
                .or(CharMatcher.is('-'))
                // special case: names may be extracted from source nodes in the AST, we allow quotes for string
                // literals
                .or(CharMatcher.is('"'))
                .precomputed();

        private static boolean requiresLegacyName(String input) {
            return !NAME_MATCHER.matchesAllOf(input);
        }
    }
}
