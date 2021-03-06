/*
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
package com.facebook.presto.pinot.query;

import com.facebook.airlift.log.Logger;
import com.facebook.presto.pinot.PinotColumnHandle;
import com.facebook.presto.pinot.PinotConfig;
import com.facebook.presto.pinot.PinotException;
import com.facebook.presto.pinot.PinotPushdownUtils.AggregationColumnNode;
import com.facebook.presto.pinot.PinotPushdownUtils.AggregationFunctionColumnNode;
import com.facebook.presto.pinot.PinotPushdownUtils.GroupByColumnNode;
import com.facebook.presto.pinot.PinotSessionProperties;
import com.facebook.presto.pinot.PinotTableHandle;
import com.facebook.presto.pinot.query.PinotQueryGeneratorContext.Selection;
import com.facebook.presto.spi.ConnectorSession;
import com.facebook.presto.spi.PrestoException;
import com.facebook.presto.spi.function.FunctionMetadataManager;
import com.facebook.presto.spi.function.StandardFunctionResolution;
import com.facebook.presto.spi.plan.AggregationNode;
import com.facebook.presto.spi.plan.FilterNode;
import com.facebook.presto.spi.plan.LimitNode;
import com.facebook.presto.spi.plan.PlanNode;
import com.facebook.presto.spi.plan.PlanVisitor;
import com.facebook.presto.spi.plan.ProjectNode;
import com.facebook.presto.spi.plan.TableScanNode;
import com.facebook.presto.spi.plan.TopNNode;
import com.facebook.presto.spi.relation.CallExpression;
import com.facebook.presto.spi.relation.ConstantExpression;
import com.facebook.presto.spi.relation.RowExpression;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.facebook.presto.spi.type.BigintType;
import com.facebook.presto.spi.type.TypeManager;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableMap;

import javax.inject.Inject;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.facebook.presto.pinot.PinotErrorCode.PINOT_UNSUPPORTED_EXPRESSION;
import static com.facebook.presto.pinot.PinotPushdownUtils.checkSupported;
import static com.facebook.presto.pinot.PinotPushdownUtils.computeAggregationNodes;
import static com.facebook.presto.pinot.PinotPushdownUtils.getLiteralAsString;
import static com.facebook.presto.pinot.PinotPushdownUtils.getOrderingScheme;
import static com.facebook.presto.pinot.query.PinotQueryGeneratorContext.Origin.DERIVED;
import static com.facebook.presto.pinot.query.PinotQueryGeneratorContext.Origin.LITERAL;
import static com.facebook.presto.pinot.query.PinotQueryGeneratorContext.Origin.TABLE_COLUMN;
import static com.facebook.presto.spi.StandardErrorCode.INVALID_FUNCTION_ARGUMENT;
import static com.google.common.base.MoreObjects.toStringHelper;
import static java.lang.String.format;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;

public class PinotQueryGenerator
{
    private static final Logger log = Logger.get(PinotQueryGenerator.class);
    private static final Map<String, String> UNARY_AGGREGATION_MAP = ImmutableMap.of(
            "min", "min",
            "max", "max",
            "avg", "avg",
            "sum", "sum",
            "approx_distinct", "DISTINCTCOUNTHLL");

    private final PinotConfig pinotConfig;
    private final TypeManager typeManager;
    private final FunctionMetadataManager functionMetadataManager;
    private final StandardFunctionResolution standardFunctionResolution;
    private final PinotFilterExpressionConverter pinotFilterExpressionConverter;
    private final PinotProjectExpressionConverter pinotProjectExpressionConverter;

    @Inject
    public PinotQueryGenerator(
            PinotConfig pinotConfig,
            TypeManager typeManager,
            FunctionMetadataManager functionMetadataManager,
            StandardFunctionResolution standardFunctionResolution)
    {
        this.pinotConfig = requireNonNull(pinotConfig, "pinot config is null");
        this.typeManager = requireNonNull(typeManager, "type manager is null");
        this.functionMetadataManager = requireNonNull(functionMetadataManager, "function metadata manager is null");
        this.standardFunctionResolution = requireNonNull(standardFunctionResolution, "standardFunctionResolution is null");
        this.pinotFilterExpressionConverter = new PinotFilterExpressionConverter(this.typeManager, this.functionMetadataManager, standardFunctionResolution);
        this.pinotProjectExpressionConverter = new PinotProjectExpressionConverter(typeManager, standardFunctionResolution);
    }

    public static class PinotQueryGeneratorResult
    {
        private final GeneratedPql generatedPql;
        private final PinotQueryGeneratorContext context;

        public PinotQueryGeneratorResult(
                GeneratedPql generatedPql,
                PinotQueryGeneratorContext context)
        {
            this.generatedPql = requireNonNull(generatedPql, "generatedPql is null");
            this.context = requireNonNull(context, "context is null");
        }

        public GeneratedPql getGeneratedPql()
        {
            return generatedPql;
        }

        public PinotQueryGeneratorContext getContext()
        {
            return context;
        }
    }

    public Optional<PinotQueryGeneratorResult> generate(PlanNode plan, ConnectorSession session)
    {
        try {
            boolean preferBrokerQueries = PinotSessionProperties.isPreferBrokerQueries(session);
            PinotQueryGeneratorContext context = requireNonNull(plan.accept(new PinotQueryPlanVisitor(session, preferBrokerQueries), new PinotQueryGeneratorContext()), "Resulting context is null");
            boolean isQueryShort = context.isQueryShort(PinotSessionProperties.getNonAggregateLimitForBrokerQueries(session));
            return Optional.of(new PinotQueryGeneratorResult(context.toQuery(pinotConfig, preferBrokerQueries, isQueryShort), context));
        }
        catch (PinotException e) {
            log.debug(e, "Possibly benign error when pushing plan into scan node %s", plan);
            return Optional.empty();
        }
    }

    public static class GeneratedPql
    {
        final String table;
        final String pql;
        final List<Integer> expectedColumnIndices;
        final int groupByClauses;
        final boolean haveFilter;
        final boolean isQueryShort;

        @JsonCreator
        public GeneratedPql(
                @JsonProperty("table") String table,
                @JsonProperty("pql") String pql,
                @JsonProperty("expectedColumnIndices") List<Integer> expectedColumnIndices,
                @JsonProperty("groupByClauses") int groupByClauses,
                @JsonProperty("haveFilter") boolean haveFilter,
                @JsonProperty("isQueryShort") boolean isQueryShort)
        {
            this.table = table;
            this.pql = pql;
            this.expectedColumnIndices = expectedColumnIndices;
            this.groupByClauses = groupByClauses;
            this.haveFilter = haveFilter;
            this.isQueryShort = isQueryShort;
        }

        @JsonProperty("pql")
        public String getPql()
        {
            return pql;
        }

        @JsonProperty("expectedColumnIndices")
        public List<Integer> getExpectedColumnIndices()
        {
            return expectedColumnIndices;
        }

        @JsonProperty("groupByClauses")
        public int getGroupByClauses()
        {
            return groupByClauses;
        }

        @JsonProperty("table")
        public String getTable()
        {
            return table;
        }

        @JsonProperty("haveFilter")
        public boolean isHaveFilter()
        {
            return haveFilter;
        }

        @JsonProperty("isQueryShort")
        public boolean isQueryShort()
        {
            return isQueryShort;
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("pql", pql)
                    .add("table", table)
                    .add("expectedColumnIndices", expectedColumnIndices)
                    .add("groupByClauses", groupByClauses)
                    .add("haveFilter", haveFilter)
                    .add("isQueryShort", isQueryShort)
                    .toString();
        }
    }

    class PinotQueryPlanVisitor
            extends PlanVisitor<PinotQueryGeneratorContext, PinotQueryGeneratorContext>
    {
        private final ConnectorSession session;
        private final boolean preferBrokerQueries;

        protected PinotQueryPlanVisitor(ConnectorSession session, boolean preferBrokerQueries)
        {
            this.session = session;
            this.preferBrokerQueries = preferBrokerQueries;
        }

        @Override
        public PinotQueryGeneratorContext visitPlan(PlanNode node, PinotQueryGeneratorContext context)
        {
            throw new PinotException(PINOT_UNSUPPORTED_EXPRESSION, Optional.empty(), "Don't know how to handle plan node of type " + node);
        }

        protected VariableReferenceExpression getVariableReference(RowExpression expression)
        {
            if (expression instanceof VariableReferenceExpression) {
                return ((VariableReferenceExpression) expression);
            }
            throw new PinotException(PINOT_UNSUPPORTED_EXPRESSION, Optional.empty(), "Expected a variable reference but got " + expression);
        }

        @Override
        public PinotQueryGeneratorContext visitFilter(FilterNode node, PinotQueryGeneratorContext context)
        {
            context = node.getSource().accept(this, context);
            requireNonNull(context, "context is null");
            LinkedHashMap<VariableReferenceExpression, Selection> selections = context.getSelections();
            String filter = node.getPredicate().accept(pinotFilterExpressionConverter, selections::get).getDefinition();
            return context.withFilter(filter).withOutputColumns(node.getOutputVariables());
        }

        @Override
        public PinotQueryGeneratorContext visitProject(ProjectNode node, PinotQueryGeneratorContext contextIn)
        {
            PinotQueryGeneratorContext context = node.getSource().accept(this, contextIn);
            requireNonNull(context, "context is null");
            LinkedHashMap<VariableReferenceExpression, Selection> newSelections = new LinkedHashMap<>();

            node.getOutputVariables().forEach(variable -> {
                RowExpression expression = node.getAssignments().get(variable);
                PinotExpression pinotExpression = expression.accept(
                        contextIn.getVariablesInAggregation().contains(variable) ?
                                new PinotAggregationProjectConverter(typeManager, functionMetadataManager, standardFunctionResolution, session) : pinotProjectExpressionConverter,
                        context.getSelections());
                newSelections.put(
                        variable,
                        new Selection(pinotExpression.getDefinition(), pinotExpression.getOrigin()));
            });
            return context.withProject(newSelections);
        }

        @Override
        public PinotQueryGeneratorContext visitTableScan(TableScanNode node, PinotQueryGeneratorContext contextIn)
        {
            PinotTableHandle tableHandle = (PinotTableHandle) node.getTable().getConnectorHandle();
            checkSupported(!tableHandle.getPql().isPresent(), "Expect to see no existing pql");
            checkSupported(!tableHandle.getIsQueryShort().isPresent(), "Expect to see no existing pql");
            LinkedHashMap<VariableReferenceExpression, Selection> selections = new LinkedHashMap<>();
            node.getOutputVariables().forEach(outputColumn -> {
                PinotColumnHandle pinotColumn = (PinotColumnHandle) (node.getAssignments().get(outputColumn));
                checkSupported(pinotColumn.getType().equals(PinotColumnHandle.PinotColumnType.REGULAR), "Unexpected pinot column handle that is not regular: %s", pinotColumn);
                selections.put(outputColumn, new Selection(pinotColumn.getColumnName(), TABLE_COLUMN));
            });
            return new PinotQueryGeneratorContext(selections, tableHandle.getTableName());
        }

        private String handleAggregationFunction(CallExpression aggregation, Map<VariableReferenceExpression, Selection> inputSelections)
        {
            String prestoAggregation = aggregation.getDisplayName().toLowerCase(ENGLISH);
            List<RowExpression> parameters = aggregation.getArguments();
            switch (prestoAggregation) {
                case "count":
                    if (parameters.size() <= 1) {
                        return format("count(%s)", parameters.isEmpty() ? "*" : inputSelections.get(getVariableReference(parameters.get(0))));
                    }
                    break;
                case "approx_percentile":
                    return handleApproxPercentile(aggregation, inputSelections);
                default:
                    if (UNARY_AGGREGATION_MAP.containsKey(prestoAggregation) && aggregation.getArguments().size() == 1) {
                        return format("%s(%s)", UNARY_AGGREGATION_MAP.get(prestoAggregation), inputSelections.get(getVariableReference(parameters.get(0))));
                    }
            }

            throw new PinotException(PINOT_UNSUPPORTED_EXPRESSION, Optional.empty(), format("aggregation function '%s' not supported yet", aggregation));
        }

        private String handleApproxPercentile(CallExpression aggregation, Map<VariableReferenceExpression, Selection> inputSelections)
        {
            List<RowExpression> inputs = aggregation.getArguments();
            if (inputs.size() != 2) {
                throw new PinotException(PINOT_UNSUPPORTED_EXPRESSION, Optional.empty(), "Cannot handle approx_percentile function " + aggregation);
            }

            String fractionString;
            RowExpression fractionInput = inputs.get(1);

            if (fractionInput instanceof ConstantExpression) {
                fractionString = getLiteralAsString((ConstantExpression) fractionInput);
            }
            else if (fractionInput instanceof VariableReferenceExpression) {
                Selection fraction = inputSelections.get(fractionInput);
                if (fraction.getOrigin() != LITERAL) {
                    throw new PinotException(PINOT_UNSUPPORTED_EXPRESSION, Optional.empty(),
                            "Cannot handle approx_percentile percentage argument be a non literal " + aggregation);
                }
                fractionString = fraction.getDefinition();
            }
            else {
                throw new PinotException(PINOT_UNSUPPORTED_EXPRESSION, Optional.empty(), "Expected the fraction to be a constant or a variable " + fractionInput);
            }

            int percentile = getValidPercentile(fractionString);
            if (percentile < 0) {
                throw new PinotException(PINOT_UNSUPPORTED_EXPRESSION, Optional.empty(),
                        format("Cannot handle approx_percentile parsed as %d from input %s (function %s)", percentile, fractionString, aggregation));
            }
            return format("PERCENTILEEST%d(%s)", percentile, inputSelections.get(getVariableReference(inputs.get(0))));
        }

        private int getValidPercentile(String fraction)
        {
            try {
                double percent = Double.parseDouble(fraction);
                if (percent < 0 || percent > 1) {
                    throw new PrestoException(INVALID_FUNCTION_ARGUMENT, "Percentile must be between 0 and 1");
                }
                percent = percent * 100.0;
                if (percent == Math.floor(percent)) {
                    return (int) percent;
                }
            }
            catch (NumberFormatException ne) {
                // Skip
            }
            return -1;
        }

        @Override
        public PinotQueryGeneratorContext visitAggregation(AggregationNode node, PinotQueryGeneratorContext contextIn)
        {
            List<AggregationColumnNode> aggregationColumnNodes = computeAggregationNodes(node);

            // Make two passes over the aggregatinColumnNodes: In the first pass identify all the variables that will be used
            // Then pass that context to the source
            // And finally, in the second pass actually generate the PQL

            // 1st pass
            Set<VariableReferenceExpression> variablesInAggregation = new HashSet<>();
            for (AggregationColumnNode expression : aggregationColumnNodes) {
                switch (expression.getExpressionType()) {
                    case GROUP_BY: {
                        GroupByColumnNode groupByColumn = (GroupByColumnNode) expression;
                        VariableReferenceExpression groupByInputColumn = getVariableReference(groupByColumn.getInputColumn());
                        variablesInAggregation.add(groupByInputColumn);
                        break;
                    }
                    case AGGREGATE: {
                        AggregationFunctionColumnNode aggregationNode = (AggregationFunctionColumnNode) expression;
                        variablesInAggregation.addAll(
                                aggregationNode
                                        .getCallExpression()
                                        .getArguments()
                                        .stream()
                                        .filter(argument -> argument instanceof VariableReferenceExpression)
                                        .map(argument -> (VariableReferenceExpression) argument)
                                        .collect(Collectors.toList()));
                        break;
                    }
                    default:
                        throw new PinotException(PINOT_UNSUPPORTED_EXPRESSION, Optional.empty(), "unknown aggregation expression: " + expression.getExpressionType());
                }
            }

            // now visit the child project node
            PinotQueryGeneratorContext context = node.getSource().accept(this, contextIn.withVariablesInAggregation(variablesInAggregation));
            requireNonNull(context, "context is null");
            checkSupported(!node.getStep().isOutputPartial(), "partial aggregations are not supported in Pinot pushdown framework");
            checkSupported(preferBrokerQueries, "Cannot push aggregation in segment mode");

            // 2nd pass
            LinkedHashMap<VariableReferenceExpression, Selection> newSelections = new LinkedHashMap<>();
            LinkedHashSet<VariableReferenceExpression> groupByColumns = new LinkedHashSet<>();
            Set<VariableReferenceExpression> hiddenColumnSet = new HashSet<>(context.getHiddenColumnSet());
            int aggregations = 0;
            boolean groupByExists = false;

            for (AggregationColumnNode expression : aggregationColumnNodes) {
                switch (expression.getExpressionType()) {
                    case GROUP_BY: {
                        GroupByColumnNode groupByColumn = (GroupByColumnNode) expression;
                        VariableReferenceExpression groupByInputColumn = getVariableReference(groupByColumn.getInputColumn());
                        VariableReferenceExpression outputColumn = getVariableReference(groupByColumn.getOutputColumn());
                        Selection pinotColumn = requireNonNull(context.getSelections().get(groupByInputColumn), "Group By column " + groupByInputColumn + " doesn't exist in input " + context.getSelections());

                        newSelections.put(outputColumn, new Selection(pinotColumn.getDefinition(), pinotColumn.getOrigin()));
                        groupByColumns.add(outputColumn);
                        groupByExists = true;
                        break;
                    }
                    case AGGREGATE: {
                        AggregationFunctionColumnNode aggregationNode = (AggregationFunctionColumnNode) expression;
                        String pinotAggFunction = handleAggregationFunction(aggregationNode.getCallExpression(), context.getSelections());
                        newSelections.put(getVariableReference(aggregationNode.getOutputColumn()), new Selection(pinotAggFunction, DERIVED));
                        aggregations++;
                        break;
                    }
                    default:
                        throw new PinotException(PINOT_UNSUPPORTED_EXPRESSION, Optional.empty(), "unknown aggregation expression: " + expression.getExpressionType());
                }
            }

            // Handling non-aggregated group by
            if (groupByExists && aggregations == 0) {
                VariableReferenceExpression hidden = new VariableReferenceExpression(UUID.randomUUID().toString(), BigintType.BIGINT);
                newSelections.put(hidden, new Selection("count(*)", DERIVED));
                hiddenColumnSet.add(hidden);
                aggregations++;
            }
            return context.withAggregation(newSelections, groupByColumns, aggregations, hiddenColumnSet);
        }

        @Override
        public PinotQueryGeneratorContext visitLimit(LimitNode node, PinotQueryGeneratorContext context)
        {
            checkSupported(!node.isPartial(), String.format("pinot query generator cannot handle partial limit"));
            checkSupported(preferBrokerQueries, "Cannot push limit in segment mode");
            context = node.getSource().accept(this, context);
            requireNonNull(context, "context is null");
            return context.withLimit(node.getCount()).withOutputColumns(node.getOutputVariables());
        }

        @Override
        public PinotQueryGeneratorContext visitTopN(TopNNode node, PinotQueryGeneratorContext context)
        {
            context = node.getSource().accept(this, context);
            requireNonNull(context, "context is null");
            checkSupported(preferBrokerQueries, "Cannot push topn in segment mode");
            checkSupported(node.getStep().equals(TopNNode.Step.SINGLE), "Can only push single logical topn in");
            return context.withTopN(getOrderingScheme(node), node.getCount()).withOutputColumns(node.getOutputVariables());
        }
    }
}
