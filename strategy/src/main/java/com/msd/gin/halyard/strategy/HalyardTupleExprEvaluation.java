/*
 * Copyright 2016 Merck Sharp & Dohme Corp. a subsidiary of Merck & Co.,
 * Inc., Kenilworth, NJ, USA.
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
package com.msd.gin.halyard.strategy;

import com.msd.gin.halyard.strategy.HalyardTupleExprEvaluation.BindingSetPipe;
import com.msd.gin.halyard.strategy.collections.BigHashSet;
import com.msd.gin.halyard.strategy.collections.Sorter;
import java.io.IOException;
import java.io.Serializable;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.common.iteration.CloseableIteratorIteration;
import org.eclipse.rdf4j.common.iteration.LookAheadIteration;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.QueryResults;
import org.eclipse.rdf4j.query.algebra.AggregateOperator;
import org.eclipse.rdf4j.query.algebra.ArbitraryLengthPath;
import org.eclipse.rdf4j.query.algebra.BinaryTupleOperator;
import org.eclipse.rdf4j.query.algebra.BindingSetAssignment;
import org.eclipse.rdf4j.query.algebra.DescribeOperator;
import org.eclipse.rdf4j.query.algebra.Difference;
import org.eclipse.rdf4j.query.algebra.Distinct;
import org.eclipse.rdf4j.query.algebra.EmptySet;
import org.eclipse.rdf4j.query.algebra.Extension;
import org.eclipse.rdf4j.query.algebra.ExtensionElem;
import org.eclipse.rdf4j.query.algebra.Filter;
import org.eclipse.rdf4j.query.algebra.Group;
import org.eclipse.rdf4j.query.algebra.Intersection;
import org.eclipse.rdf4j.query.algebra.Join;
import org.eclipse.rdf4j.query.algebra.LeftJoin;
import org.eclipse.rdf4j.query.algebra.MultiProjection;
import org.eclipse.rdf4j.query.algebra.Order;
import org.eclipse.rdf4j.query.algebra.OrderElem;
import org.eclipse.rdf4j.query.algebra.Projection;
import org.eclipse.rdf4j.query.algebra.ProjectionElemList;
import org.eclipse.rdf4j.query.algebra.QueryModelNode;
import org.eclipse.rdf4j.query.algebra.QueryRoot;
import org.eclipse.rdf4j.query.algebra.Reduced;
import org.eclipse.rdf4j.query.algebra.Service;
import org.eclipse.rdf4j.query.algebra.SingletonSet;
import org.eclipse.rdf4j.query.algebra.Slice;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.SubQueryValueOperator;
import org.eclipse.rdf4j.query.algebra.TupleExpr;
import org.eclipse.rdf4j.query.algebra.UnaryTupleOperator;
import org.eclipse.rdf4j.query.algebra.Union;
import org.eclipse.rdf4j.query.algebra.ValueExpr;
import org.eclipse.rdf4j.query.algebra.Var;
import org.eclipse.rdf4j.query.algebra.ZeroLengthPath;
import org.eclipse.rdf4j.query.algebra.evaluation.EvaluationStrategy;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryBindingSet;
import org.eclipse.rdf4j.query.algebra.evaluation.TripleSource;
import org.eclipse.rdf4j.query.algebra.evaluation.ValueExprEvaluationException;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.ExternalSet;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.StrictEvaluationStrategy;
import org.eclipse.rdf4j.query.algebra.evaluation.iterator.DescribeIteration;
import org.eclipse.rdf4j.query.algebra.evaluation.iterator.GroupIterator;
import org.eclipse.rdf4j.query.algebra.evaluation.iterator.PathIteration;
import org.eclipse.rdf4j.query.algebra.evaluation.iterator.ProjectionIterator;
import org.eclipse.rdf4j.query.algebra.evaluation.iterator.ZeroLengthPathIteration;
import org.eclipse.rdf4j.query.algebra.evaluation.util.ValueComparator;
import org.eclipse.rdf4j.query.algebra.helpers.VarNameCollector;
import org.eclipse.rdf4j.query.impl.EmptyBindingSet;

/**
 *
 * @author Adam Sotona (MSD)
 */
final class HalyardTupleExprEvaluation {

    private static final int MAX_QUEUE_SIZE = 1000;

    static abstract class BindingSetPipe {

        protected final BindingSetPipe parent;

        protected BindingSetPipe(BindingSetPipe parent) {
            this.parent = parent;
        }

        /**
         * Pushes BindingSet up to the pipe, pushing null indicates end of data. In case you need to interrupt the tree data flow (when for example just a Slice
         * of data is expected), it is necessary to indicate that no more data are expected down the tree (to stop feeding this pipe) by returning false and
         * also to indicate up the tree that this is the end of data (by pushing null into the parent pipe in the evaluation tree).
         *
         * @param bs BindingSet or null if there are no more data
         * @return boolean indicating if more data are expected from the caller
         * @throws InterruptedException
         * @throws QueryEvaluationException
         */
        public abstract boolean push(BindingSet bs) throws InterruptedException;

        protected void handleException(Exception e) {
            if (parent != null) {
                parent.handleException(e);
            }
        }

        protected boolean isClosed() {
            if (parent != null) {
                return parent.isClosed();
            } else {
                return false;
            }
        }
    }

    private final HalyardEvaluationStrategy parentStrategy;
    private final HalyardStatementPatternEvaluation statementEvaluation;
    private final long startTime, timeout;

    HalyardTupleExprEvaluation(HalyardEvaluationStrategy parentStrategy, TripleSource tripleSource, Dataset dataset, long timeout) {
        this.parentStrategy = parentStrategy;
        this.statementEvaluation = new HalyardStatementPatternEvaluation(dataset, tripleSource);
        this.startTime = System.currentTimeMillis();
        this.timeout = timeout;
    }

    CloseableIteration<BindingSet, QueryEvaluationException> evaluate(TupleExpr expr, BindingSet bindings) {
        BindingSetPipeIterator root = new BindingSetPipeIterator();
        evaluateTupleExpr(root.pipe, expr, bindings);
        return root;
    }

    private void evaluateTupleExpr(BindingSetPipe parent, TupleExpr expr, BindingSet bindings) {
        if (expr instanceof StatementPattern) {
            statementEvaluation.evaluateStatementPattern(parent, (StatementPattern) expr, bindings);
        } else if (expr instanceof UnaryTupleOperator) {
            evaluateUnaryTupleOperator(parent, (UnaryTupleOperator) expr, bindings);
        } else if (expr instanceof BinaryTupleOperator) {
            evaluateBinaryTupleOperator(parent, (BinaryTupleOperator) expr, bindings);
        } else if (expr instanceof SingletonSet) {
            evaluateSingletonSet(parent, (SingletonSet) expr, bindings);
        } else if (expr instanceof EmptySet) {
            evaluateEmptySet(parent, (EmptySet) expr, bindings);
        } else if (expr instanceof ExternalSet) {
            evaluateExternalSet(parent, (ExternalSet) expr, bindings);
        } else if (expr instanceof ZeroLengthPath) {
            evaluateZeroLengthPath(parent, (ZeroLengthPath) expr, bindings);
        } else if (expr instanceof ArbitraryLengthPath) {
            evaluateArbitraryLengthPath(parent, (ArbitraryLengthPath) expr, bindings);
        } else if (expr instanceof BindingSetAssignment) {
            evaluateBindingSetAssignment(parent, (BindingSetAssignment) expr, bindings);
        } else if (expr == null) {
            parent.handleException(new IllegalArgumentException("expr must not be null"));
        } else {
            parent.handleException(new QueryEvaluationException("Unsupported tuple expr type: " + expr.getClass()));
        }
    }

    private void evaluateUnaryTupleOperator(BindingSetPipe parent, UnaryTupleOperator expr, BindingSet bindings) {
        if (expr instanceof Projection) {
            evaluateProjection(parent, (Projection) expr, bindings);
        } else if (expr instanceof MultiProjection) {
            evaluateMultiProjection(parent, (MultiProjection) expr, bindings);
        } else if (expr instanceof Filter) {
            evaluateFilter(parent, (Filter) expr, bindings);
        } else if (expr instanceof Service) {
            evaluateService(parent, (Service) expr, bindings);
        } else if (expr instanceof Slice) {
            evaluateSlice(parent, (Slice) expr, bindings);
        } else if (expr instanceof Extension) {
            evaluateExtension(parent, (Extension) expr, bindings);
        } else if (expr instanceof Distinct) {
            evaluateDistinct(parent, (Distinct) expr, bindings);
        } else if (expr instanceof Reduced) {
            evaluateReduced(parent, (Reduced) expr, bindings);
        } else if (expr instanceof Group) {
            evaluateGroup(parent, (Group) expr, bindings);
        } else if (expr instanceof Order) {
            evaluateOrder(parent, (Order) expr, bindings);
        } else if (expr instanceof QueryRoot) {
            parentStrategy.sharedValueOfNow = null;
            evaluateTupleExpr(parent, ((QueryRoot) expr).getArg(), bindings);
        } else if (expr instanceof DescribeOperator) {
            evaluateDescribeOperator(parent, (DescribeOperator) expr, bindings);
        } else if (expr == null) {
            parent.handleException(new IllegalArgumentException("expr must not be null"));
        } else {
            parent.handleException(new QueryEvaluationException("Unknown unary tuple operator type: " + expr.getClass()));
        }
    }

    private void evaluateProjection(BindingSetPipe parent, final Projection projection, final BindingSet bindings) {
        evaluateTupleExpr(new BindingSetPipe(parent) {
            @Override
            public boolean push(BindingSet bs) throws InterruptedException {
                return parent.push(bs == null ? null : ProjectionIterator.project(projection.getProjectionElemList(), bs, bindings, true));
            }
        }, projection.getArg(), bindings);
    }

    private void evaluateMultiProjection(BindingSetPipe parent, final MultiProjection multiProjection, final BindingSet bindings) {
        final List<ProjectionElemList> projections = multiProjection.getProjections();
        final BindingSet prev[] = new BindingSet[projections.size()];
        evaluateTupleExpr(new BindingSetPipe(parent) {
            @Override
            public boolean push(BindingSet bs) throws InterruptedException {
                if (bs == null) {
                    return parent.push(null);
                }
                for (int i=0; i<prev.length; i++) {
                    BindingSet nb = ProjectionIterator.project(projections.get(i), bs, bindings);
                    //ignore duplicates
                    boolean push = false;
                    synchronized (prev) {
                        if (!nb.equals(prev[i])) {
                            prev[i] = nb;
                            push = true;
                        }
                    }
                    if (push) {
                        if (!parent.push(nb)) return false;
                    }
                }
                return true;
            }
        }, multiProjection.getArg(), bindings);
    }

    private void evaluateFilter(BindingSetPipe parent, final Filter filter, final BindingSet bindings) {
        final Set<String> scopeBindingNames = filter.getBindingNames();
        evaluateTupleExpr(new BindingSetPipe(parent) {
            @Override
            public boolean push(BindingSet bs) throws InterruptedException {
                if (bs == null) {
                    return parent.push(null);
                }
                try {
                    if (accept(bs)) {
                        return parent.push(bs);
                    } else {
                        return true;
                    }
                } catch (QueryEvaluationException e) {
                    parent.handleException(e);
                }
                return false;
            }
            private boolean accept(BindingSet bindings) throws QueryEvaluationException {
                try {
                    // Limit the bindings to the ones that are in scope for this filter
                    QueryBindingSet scopeBindings = new QueryBindingSet(bindings);
                    // FIXME J1 scopeBindingNames should include bindings from superquery if the filter
                    // is part of a subquery. This is a workaround: we should fix the settings of scopeBindingNames,
                    // rather than skipping the limiting of bindings.
                    if (!isPartOfSubQuery(filter)) {
                        scopeBindings.retainAll(scopeBindingNames);
                    }
                    return parentStrategy.isTrue(filter.getCondition(), scopeBindings);
                } catch (ValueExprEvaluationException e) {
                    // failed to evaluate condition
                    return false;
                }
            }
        }, filter.getArg(), bindings);
    }

    private void evaluateDescribeOperator(BindingSetPipe parent, DescribeOperator operator, BindingSet bindings) {
        HalyardStatementPatternEvaluation.enqueue(parent, new DescribeIteration(evaluate(operator.getArg(), bindings), parentStrategy, operator.getBindingNames(), bindings), operator);
    }

    private static class ComparableBindingSetWrapper implements Comparable<ComparableBindingSetWrapper>, Serializable {

        private static final long serialVersionUID = -7341340704807086829L;

        private static final ValueComparator VC = new ValueComparator();

        private final BindingSet bs;
        private final Value values[];
        private final boolean ascending[];
        private final long minorOrder;


        public ComparableBindingSetWrapper(EvaluationStrategy strategy, BindingSet bs, List<OrderElem> elements, long minorOrder) throws QueryEvaluationException {
            this.bs = bs;
            this.values = new Value[elements.size()];
            this.ascending = new boolean[elements.size()];
            for (int i = 0; i < values.length; i++) {
                OrderElem oe = elements.get(i);
                try {
                    values[i] = strategy.evaluate(oe.getExpr(), bs);
                } catch (ValueExprEvaluationException exc) {
                    values[i] = null;
                }
                ascending[i] = oe.isAscending();
            }
            this.minorOrder = minorOrder;
        }

        @Override
        public int compareTo(ComparableBindingSetWrapper o) {
            if (equals(o)) return 0;
            for (int i=0; i<values.length; i++) {
                int cmp = ascending[i] ? VC.compare(values[i], o.values[i]) : VC.compare(o.values[i], values[i]);
                if (cmp != 0) {
                    return cmp;
                }
            }
            return Long.compare(minorOrder, o.minorOrder);
        }

        @Override
        public int hashCode() {
            return bs.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            return obj instanceof ComparableBindingSetWrapper && bs.equals(((ComparableBindingSetWrapper)obj).bs);
        }

    }

    private void evaluateOrder(final BindingSetPipe parent, final Order order, BindingSet bindings) {
//        try {
            final Sorter<ComparableBindingSetWrapper> sorter = new Sorter<>(getLimit(order), isReducedOrDistinct(order));
            final AtomicLong minorOrder = new AtomicLong();
            evaluateTupleExpr(new BindingSetPipe(parent) {

                @Override
                protected void handleException(Exception e) {
                    sorter.close();
                    super.handleException(e);
                }

                @Override
                public boolean push(BindingSet bs) throws InterruptedException {
                    if (bs != null) try {
                        ComparableBindingSetWrapper cbsw = new ComparableBindingSetWrapper(parentStrategy, bs, order.getElements(), minorOrder.getAndIncrement());
                        synchronized (sorter) {
                            sorter.add(cbsw);
                        }
                        return true;
                    } catch (QueryEvaluationException | IOException e) {
                        handleException(e);
                        return false;
                    }
                    try {
                        for (Map.Entry<ComparableBindingSetWrapper, Long> me : sorter) {
                            for (long i = me.getValue(); i > 0; i--) {
                                if (!parent.push(me.getKey().bs)) {
                                    return false;
                                }
                            }
                        }
                        return parent.push(null);
                    } finally {
                        sorter.close();
                    }
                }
            }, order.getArg(), bindings);
//        } catch (IOException e) {
//            throw new QueryEvaluationException(e);
//        }
    }

    private void evaluateGroup(BindingSetPipe parent, Group group, BindingSet bindings) {
        //temporary solution using copy of the original iterator
        //re-writing this to push model is a bit more complex task
        try {
            HalyardStatementPatternEvaluation.enqueue(parent, new GroupIterator(parentStrategy, group, bindings), group);
        } catch (QueryEvaluationException e) {
            parent.handleException(e);
        }
    }

    private void evaluateReduced(BindingSetPipe parent, Reduced reduced, BindingSet bindings) {
        evaluateTupleExpr(new BindingSetPipe(parent) {
            private BindingSet previous = null;

            @Override
            public boolean push(BindingSet bs) throws InterruptedException {
                synchronized (this) {
                    if (bs != null && bs.equals(previous)) {
                        previous = bs;
                        return true;
                    }
                }
                return parent.push(bs);
            }
        }, reduced.getArg(), bindings);
    }

    private void evaluateDistinct(BindingSetPipe parent, final Distinct distinct, BindingSet bindings) {
        evaluateTupleExpr(new BindingSetPipe(parent) {
            private final BigHashSet<BindingSet> set = new BigHashSet<>();
            @Override
            protected void handleException(Exception e) {
                set.close();
                super.handleException(e);
            }
            @Override
            public boolean push(BindingSet bs) throws InterruptedException {
                synchronized (set) {
                    if (bs == null) {
                        set.close();
                    } else try {
                        if (!set.add(bs)) {
                            return true;
                        }
                    } catch (IOException e) {
                        handleException(e);
                        return false;
                    }
                }
                return parent.push(bs);
            }
        }, distinct.getArg(), bindings);
    }

    private void evaluateExtension(BindingSetPipe parent, final Extension extension, BindingSet bindings) {
        evaluateTupleExpr(new BindingSetPipe(parent) {
            @Override
            public boolean push(BindingSet bs) throws InterruptedException {
                if (bs == null) {
                    return parent.push(null);
                }
                QueryBindingSet targetBindings = new QueryBindingSet(bs);
                for (ExtensionElem extElem : extension.getElements()) {
                    ValueExpr expr = extElem.getExpr();
                    if (!(expr instanceof AggregateOperator)) {
                        try {
                            // we evaluate each extension element over the targetbindings, so that bindings from
                            // a previous extension element in this same extension can be used by other extension elements.
                            // e.g. if a projection contains (?a + ?b as ?c) (?c * 2 as ?d)
                            Value targetValue = parentStrategy.evaluate(extElem.getExpr(), targetBindings);
                            if (targetValue != null) {
                                // Potentially overwrites bindings from super
                                targetBindings.setBinding(extElem.getName(), targetValue);
                            }
                        } catch (ValueExprEvaluationException e) {
                            // silently ignore type errors in extension arguments. They should not cause the
                            // query to fail but just result in no additional binding.
                        } catch (QueryEvaluationException e) {
                            parent.handleException(e);
                        }
                    }
                }
                return parent.push(targetBindings);
            }
        }, extension.getArg(), bindings);
    }

    private void evaluateSlice(BindingSetPipe parent, Slice slice, BindingSet bindings) {
        final long offset = slice.hasOffset() ? slice.getOffset() : 0;
        final long limit = slice.hasLimit() ? offset + slice.getLimit() : Long.MAX_VALUE;
        evaluateTupleExpr(new BindingSetPipe(parent) {
            private final AtomicLong ll = new AtomicLong(0);
            @Override
            public boolean push(BindingSet bs) throws InterruptedException {
                if (bs == null) return parent.push(null);
                long l = ll.incrementAndGet();
                if (l <= offset) {
                    return true;
                } else if (l <= limit) {
                    return parent.push(bs);
                } else {
                    return parent.push(null);
                }
            }
        }, slice.getArg(), bindings);
    }

    private void evaluateService(BindingSetPipe parent, Service service, BindingSet bindings) {
        parent.handleException(new UnsupportedOperationException("Service are not supported yet"));
    }

    private void evaluateBinaryTupleOperator(BindingSetPipe parent, BinaryTupleOperator expr, BindingSet bindings) {
        if (expr instanceof Join) {
            evaluateJoin(parent, (Join) expr, bindings);
        } else if (expr instanceof LeftJoin) {
            evaluateLeftJoin(parent, (LeftJoin) expr, bindings);
        } else if (expr instanceof Union) {
            evaluateUnion(parent, (Union) expr, bindings);
        } else if (expr instanceof Intersection) {
            evaluateIntersection(parent, (Intersection) expr, bindings);
        } else if (expr instanceof Difference) {
            evaluateDifference(parent, (Difference) expr, bindings);
        } else if (expr == null) {
            parent.handleException(new IllegalArgumentException("expr must not be null"));
        } else {
            parent.handleException(new QueryEvaluationException("Unsupported binary tuple operator type: " + expr.getClass()));
        }
    }

    private void evaluateJoin(BindingSetPipe topPipe, final Join join, final BindingSet bindings) {
        final AtomicLong joinsInProgress = new AtomicLong(1);
        BindingSetPipe rightPipe = new BindingSetPipe(topPipe) {
            @Override
            public boolean push(BindingSet bs) throws InterruptedException {
                if (bs == null) {
                    if (joinsInProgress.decrementAndGet() == 0) {
                        parent.push(null);
                    }
                    return false;
                } else {
                    return parent.push(bs);
                }
            }
        };
        evaluateTupleExpr(new BindingSetPipe(rightPipe) {
            @Override
            public boolean push(BindingSet bs) throws InterruptedException {
                if (bs == null) {
                    return parent.push(null);
                } else {
                    joinsInProgress.incrementAndGet();
                    evaluateTupleExpr(parent, join.getRightArg(), bs);
                    return true;
                }
            }
        }, join.getLeftArg(), bindings);
    }

    private void evaluateLeftJoin(BindingSetPipe parentPipe, final LeftJoin leftJoin, final BindingSet bindings) {
        // Check whether optional join is "well designed" as defined in section
        // 4.2 of "Semantics and Complexity of SPARQL", 2006, Jorge Pérez et al.
        VarNameCollector optionalVarCollector = new VarNameCollector();
        leftJoin.getRightArg().visit(optionalVarCollector);
        if (leftJoin.hasCondition()) {
            leftJoin.getCondition().visit(optionalVarCollector);
        }
        final Set<String> problemVars = optionalVarCollector.getVarNames();
        problemVars.removeAll(leftJoin.getLeftArg().getBindingNames());
        problemVars.retainAll(bindings.getBindingNames());
        final AtomicLong joinsInProgress = new AtomicLong(1);
        final Set<String> scopeBindingNames = leftJoin.getBindingNames();
        final BindingSetPipe topPipe = problemVars.isEmpty() ? parentPipe : new BindingSetPipe(parentPipe) {
            //Handle badly designed left join
            @Override
            public boolean push(BindingSet bs) throws InterruptedException {
                if (bs == null) {
                    return parent.push(null);
                }
		if (QueryResults.bindingSetsCompatible(bindings, bs)) {
                    // Make sure the provided problemVars are part of the returned results
                    // (necessary in case of e.g. LeftJoin and Union arguments)
                    QueryBindingSet extendedResult = null;
                    for (String problemVar : problemVars) {
                            if (!bs.hasBinding(problemVar)) {
                                    if (extendedResult == null) {
                                            extendedResult = new QueryBindingSet(bs);
                                    }
                                    extendedResult.addBinding(problemVar, bindings.getValue(problemVar));
                            }
                    }
                    if (extendedResult != null) {
                            bs = extendedResult;
                    }
                    return parent.push(bs);
		}
                return true;
            }
        };
        evaluateTupleExpr(new BindingSetPipe(topPipe) {
            @Override
            public boolean push(final BindingSet leftBindings) throws InterruptedException {
                if (leftBindings == null) {
                    if (joinsInProgress.decrementAndGet() == 0) {
                        parent.push(null);
                    }
                } else {
                    joinsInProgress.incrementAndGet();
                    evaluateTupleExpr(new BindingSetPipe(topPipe) {
                        private boolean failed = true;
                        @Override
                        public boolean push(BindingSet rightBindings) throws InterruptedException {
                            if (rightBindings == null) {
                                if (failed) {
                                    // Join failed, return left arg's bindings
                                    parent.push(leftBindings);
                                }
                                if (joinsInProgress.decrementAndGet() == 0) {
                                    parent.push(null);
                                }
                                return false;
                            } else try {
                                if (leftJoin.getCondition() == null) {
                                    failed = false;
                                    return parent.push(rightBindings);
                                } else {
                                    // Limit the bindings to the ones that are in scope for
                                    // this filter
                                    QueryBindingSet scopeBindings = new QueryBindingSet(rightBindings);
                                    scopeBindings.retainAll(scopeBindingNames);
                                    if (parentStrategy.isTrue(leftJoin.getCondition(), scopeBindings)) {
                                        failed = false;
                                        return parent.push(rightBindings);
                                    }
                                }
                            } catch (ValueExprEvaluationException ignore) {
                            } catch (QueryEvaluationException e) {
                                parent.handleException(e);
                            }
                            return true;
                        }
                    }, leftJoin.getRightArg(), leftBindings);
                }
                return true;
            }
        }, leftJoin.getLeftArg(), problemVars.isEmpty() ? bindings : getFilteredBindings(bindings, problemVars));
    }

    private static QueryBindingSet getFilteredBindings(BindingSet bindings, Set<String> problemVars) {
            QueryBindingSet filteredBindings = new QueryBindingSet(bindings);
            filteredBindings.removeAll(problemVars);
            return filteredBindings;
    }

    private void evaluateUnion(BindingSetPipe parent, Union union, BindingSet bindings) {
        BindingSetPipe pipe = new BindingSetPipe(parent) {
            AtomicInteger args = new AtomicInteger(2);
            @Override
            public boolean push(BindingSet bs) throws InterruptedException {
                if (bs == null) {
                    if (args.decrementAndGet() == 0) {
                        return parent.push(null);
                    } else {
                        return false;
                    }
                } else {
                    return parent.push(bs);
                }
            }
        };
        evaluateTupleExpr(pipe, union.getLeftArg(), bindings);
        evaluateTupleExpr(pipe, union.getRightArg(), bindings);
    }

    private void evaluateIntersection(final BindingSetPipe topPipe, final Intersection intersection, final BindingSet bindings) {
        evaluateTupleExpr(new BindingSetPipe(topPipe) {
            private final BigHashSet<BindingSet> secondSet = new BigHashSet<>();
            @Override
            protected void handleException(Exception e) {
                secondSet.close();
                super.handleException(e);
            }
            @Override
            public boolean push(BindingSet bs) throws InterruptedException {
                if (bs != null) try {
                    secondSet.add(bs);
                    return true;
                } catch (IOException e) {
                    handleException(e);
                    return false;
                } else {
                    evaluateTupleExpr(new BindingSetPipe(parent) {
                        @Override
                        public boolean push(BindingSet bs) throws InterruptedException {
                            try {
                                if (bs == null) {
                                    secondSet.close();
                                    return parent.push(null);
                                }
                                return secondSet.contains(bs) ? parent.push(bs) : true;
                            } catch (IOException e) {
                                super.handleException(e);
                                return false;
                            }
                        }
                    }, intersection.getLeftArg(), bindings);
                    return false;
                }
            }
        }, intersection.getRightArg(), bindings);
    }

    private void evaluateDifference(final BindingSetPipe topPipe, final Difference difference, final BindingSet bindings) {
        evaluateTupleExpr(new BindingSetPipe(topPipe) {
            private final BigHashSet<BindingSet> excludeSet = new BigHashSet<>();
            @Override
            protected void handleException(Exception e) {
                excludeSet.close();
                super.handleException(e);
            }
            @Override
            public boolean push(BindingSet bs) throws InterruptedException {
                if (bs != null) try {
                    excludeSet.add(bs);
                    return true;
                } catch (IOException e) {
                    handleException(e);
                    return false;
                } else {
                    evaluateTupleExpr(new BindingSetPipe(topPipe) {
                        @Override
                        public boolean push(BindingSet bs) throws InterruptedException {
                            if (bs == null) {
                                excludeSet.close();
                                return parent.push(null);
                            }
                            for (BindingSet excluded : excludeSet) {
                                // build set of shared variable names
                                Set<String> sharedBindingNames = new HashSet<>(excluded.getBindingNames());
                                sharedBindingNames.retainAll(bs.getBindingNames());
                                // two bindingsets that share no variables are compatible by
                                // definition, however, the formal
                                // definition of SPARQL MINUS indicates that such disjoint sets should
                                // be filtered out.
                                // See http://www.w3.org/TR/sparql11-query/#sparqlAlgebra
                                if (!sharedBindingNames.isEmpty()) {
                                    if (QueryResults.bindingSetsCompatible(excluded, bs)) {
                                        // at least one compatible bindingset has been found in the
                                        // exclude set, therefore the object is compatible, therefore it
                                        // should not be accepted.
                                        return true;
                                    }
                                }
                            }
                            return parent.push(bs);
                        }
                    }, difference.getLeftArg(), bindings);
                }
                return false;
            }

        }, difference.getRightArg(), bindings);
    }

    private void evaluateSingletonSet(BindingSetPipe parent, SingletonSet singletonSet, BindingSet bindings) {
        try {
            if (parent.push(bindings)) {
                parent.push(null);
            }
        } catch (InterruptedException e) {
            parent.handleException(e);
        }
    }

    private void evaluateEmptySet(BindingSetPipe parent, EmptySet emptySet, BindingSet bindings) {
        try {
            parent.push(null);
        } catch (InterruptedException e) {
            parent.handleException(e);
        }
    }

    private void evaluateExternalSet(BindingSetPipe parent, ExternalSet externalSet, BindingSet bindings) {
        try {
            HalyardStatementPatternEvaluation.enqueue(parent, externalSet.evaluate(bindings), externalSet);
        } catch (QueryEvaluationException e) {
            parent.handleException(e);
        }
    }

    private void evaluateZeroLengthPath(BindingSetPipe parent, ZeroLengthPath zlp, BindingSet bindings) {
        final Var subjectVar = zlp.getSubjectVar();
        final Var objVar = zlp.getObjectVar();
        final Var contextVar = zlp.getContextVar();
        Value subj = subjectVar.getValue() == null ? bindings.getValue(subjectVar.getName()) : subjectVar.getValue();
        Value obj = objVar.getValue() == null ? bindings.getValue(objVar.getName()) : objVar.getValue();
        if (subj != null && obj != null) {
            if (!subj.equals(obj)) {
                try {
                    parent.push(null);
                } catch (InterruptedException e) {
                    parent.handleException(e);
                }
                return;
            }
        }
        //temporary solution using copy of the original iterator
        //re-writing this to push model is a bit more complex task
        HalyardStatementPatternEvaluation.enqueue(parent, new ZeroLengthPathIteration(parentStrategy, subjectVar, objVar, subj, obj, contextVar, bindings), zlp);
    }

    private void evaluateArbitraryLengthPath(BindingSetPipe parent, ArbitraryLengthPath alp, BindingSet bindings) {
        final StatementPattern.Scope scope = alp.getScope();
        final Var subjectVar = alp.getSubjectVar();
        final TupleExpr pathExpression = alp.getPathExpression();
        final Var objVar = alp.getObjectVar();
        final Var contextVar = alp.getContextVar();
        final long minLength = alp.getMinLength();
        //temporary solution using copy of the original iterator
        //re-writing this to push model is a bit more complex task
        try {
            HalyardStatementPatternEvaluation.enqueue(parent, new PathIteration(new StrictEvaluationStrategy(null, null) {
                @Override
                public CloseableIteration<BindingSet, QueryEvaluationException> evaluate(ZeroLengthPath zlp, BindingSet bindings) throws QueryEvaluationException {
                    return parentStrategy.evaluate(zlp, bindings);
                }

                @Override
                public CloseableIteration<BindingSet, QueryEvaluationException> evaluate(TupleExpr expr, BindingSet bindings) throws QueryEvaluationException {
                    return parentStrategy.evaluate(expr, bindings);
                }

            }, scope, subjectVar, pathExpression, objVar, contextVar, minLength, bindings), alp);
        } catch (QueryEvaluationException e) {
            parent.handleException(e);
        }
    }

    private void evaluateBindingSetAssignment(BindingSetPipe parent, BindingSetAssignment bsa, BindingSet bindings) {
        final Iterator<BindingSet> iter = bsa.getBindingSets().iterator();
        if (bindings.size() == 0) { // empty binding set
            HalyardStatementPatternEvaluation.enqueue(parent, new CloseableIteratorIteration<>(iter), bsa);
        } else {
            final QueryBindingSet b = new QueryBindingSet(bindings);
            HalyardStatementPatternEvaluation.enqueue(parent, new LookAheadIteration<BindingSet, QueryEvaluationException>() {
                @Override
                protected BindingSet getNextElement() throws QueryEvaluationException {
                    QueryBindingSet result = null;
                    while (result == null && iter.hasNext()) {
                        final BindingSet assignedBindings = iter.next();
                        for (String name : assignedBindings.getBindingNames()) {
                            final Value assignedValue = assignedBindings.getValue(name);
                            if (assignedValue != null) { // can be null if set to UNDEF
                                // check that the binding assignment does not overwrite
                                // existing bindings.
                                Value bValue = b.getValue(name);
                                if (bValue == null || assignedValue.equals(bValue)) {
                                    if (result == null) {
                                        result = new QueryBindingSet(b);
                                    }
                                    if (bValue == null) {
                                        // we are not overwriting an existing binding.
                                        result.addBinding(name, assignedValue);
                                    }
                                } else {
                                    // if values are not equal there is no compatible
                                    // merge and we should return no next element.
                                    result = null;
                                    break;
                                }
                            }
                        }
                    }
                    return result;
                }
            }, bsa);
        }
    }

    /**
     * Returns the limit of the current variable bindings before any further projection.
     */
    private static long getLimit(QueryModelNode node) {
        long offset = 0;
        if (node instanceof Slice) {
            Slice slice = (Slice) node;
            if (slice.hasOffset() && slice.hasLimit()) {
                return slice.getOffset() + slice.getLimit();
            } else if (slice.hasLimit()) {
                return slice.getLimit();
            } else if (slice.hasOffset()) {
                offset = slice.getOffset();
            }
        }
        QueryModelNode parent = node.getParentNode();
        if (parent instanceof Distinct || parent instanceof Reduced || parent instanceof Slice) {
            long limit = getLimit(parent);
            if (offset > 0L && limit < Long.MAX_VALUE) {
                return offset + limit;
            } else {
                return limit;
            }
        }
        return Long.MAX_VALUE;
    }

    private static boolean isReducedOrDistinct(QueryModelNode node) {
        QueryModelNode parent = node.getParentNode();
        if (parent instanceof Slice) {
            return isReducedOrDistinct(parent);
        }
        return parent instanceof Distinct || parent instanceof Reduced;
    }

    private boolean isPartOfSubQuery(QueryModelNode node) {
        if (node instanceof SubQueryValueOperator) {
            return true;
        }
        QueryModelNode parent = node.getParentNode();
        if (parent == null) {
            return false;
        } else {
            return isPartOfSubQuery(parent);
        }
    }

    private static final BindingSet NULL = new EmptyBindingSet();

    private final class BindingSetPipeIterator extends LookAheadIteration<BindingSet, QueryEvaluationException> {

        private final LinkedBlockingQueue<BindingSet> queue = new LinkedBlockingQueue<>(MAX_QUEUE_SIZE);
        private Exception exception = null;

        private final BindingSetPipe pipe = new BindingSetPipe(null) {
            @Override
            public boolean push(BindingSet bs) throws InterruptedException {
                if (isClosed()) return false;
                queue.put(bs == null ? NULL : bs);
                return bs != null;
            }

            @Override
            protected void handleException(Exception e) {
                if (exception != null) e.addSuppressed(exception);
                exception = e;
                try {
                    close();
                } catch (QueryEvaluationException ex) {
                    exception.addSuppressed(ex);
                }
            }

            @Override
            protected boolean isClosed() {
                return BindingSetPipeIterator.this.isClosed();
            }
        };

        @Override
        protected BindingSet getNextElement() throws QueryEvaluationException {
            try {
                while (true) {
                    BindingSet bs = queue.poll(1, TimeUnit.SECONDS);
                    if (exception != null) throw new QueryEvaluationException(exception);
                    if (timeout > 0 && System.currentTimeMillis() - startTime > 1000l * timeout) throw new QueryEvaluationException("Query evaluation exceeded specified timeout " + timeout + "s");
                    if (bs != null) {
                        return bs == NULL ? null : bs;
                    }
                }
            } catch (InterruptedException ex) {
                throw new QueryEvaluationException(ex);
            }
        }

        @Override
        protected void handleClose() throws QueryEvaluationException {
            super.handleClose();
            queue.clear();
            queue.add(NULL);
        }
    }
}
