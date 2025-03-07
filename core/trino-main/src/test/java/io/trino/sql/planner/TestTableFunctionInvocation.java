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
package io.trino.sql.planner;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.airlift.slice.Slices;
import io.trino.connector.MockConnectorFactory;
import io.trino.connector.MockConnectorPlugin;
import io.trino.connector.TestingTableFunctions.DescriptorArgumentFunction;
import io.trino.connector.TestingTableFunctions.DifferentArgumentTypesFunction;
import io.trino.connector.TestingTableFunctions.PassThroughFunction;
import io.trino.connector.TestingTableFunctions.TestingTableFunctionPushdownHandle;
import io.trino.connector.TestingTableFunctions.TwoScalarArgumentsFunction;
import io.trino.connector.TestingTableFunctions.TwoTableArgumentsFunction;
import io.trino.spi.connector.TableFunctionApplicationResult;
import io.trino.spi.function.table.Descriptor;
import io.trino.spi.function.table.Descriptor.Field;
import io.trino.sql.ir.Cast;
import io.trino.sql.ir.Constant;
import io.trino.sql.ir.Reference;
import io.trino.sql.planner.assertions.BasePlanTest;
import io.trino.sql.planner.assertions.RowNumberSymbolMatcher;
import io.trino.sql.planner.plan.TableFunctionProcessorNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static io.trino.spi.connector.SortOrder.ASC_NULLS_LAST;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.SmallintType.SMALLINT;
import static io.trino.spi.type.VarcharType.createVarcharType;
import static io.trino.sql.ir.Booleans.TRUE;
import static io.trino.sql.planner.LogicalPlanner.Stage.CREATED;
import static io.trino.sql.planner.assertions.PlanMatchPattern.anyTree;
import static io.trino.sql.planner.assertions.PlanMatchPattern.expression;
import static io.trino.sql.planner.assertions.PlanMatchPattern.node;
import static io.trino.sql.planner.assertions.PlanMatchPattern.output;
import static io.trino.sql.planner.assertions.PlanMatchPattern.project;
import static io.trino.sql.planner.assertions.PlanMatchPattern.rowNumber;
import static io.trino.sql.planner.assertions.PlanMatchPattern.specification;
import static io.trino.sql.planner.assertions.PlanMatchPattern.strictOutput;
import static io.trino.sql.planner.assertions.PlanMatchPattern.strictProject;
import static io.trino.sql.planner.assertions.PlanMatchPattern.tableFunction;
import static io.trino.sql.planner.assertions.PlanMatchPattern.tableFunctionProcessor;
import static io.trino.sql.planner.assertions.PlanMatchPattern.values;
import static io.trino.sql.planner.assertions.TableFunctionMatcher.DescriptorArgumentValue.descriptorArgument;
import static io.trino.sql.planner.assertions.TableFunctionMatcher.DescriptorArgumentValue.nullDescriptor;
import static io.trino.sql.planner.assertions.TableFunctionMatcher.TableArgumentValue.Builder.tableArgument;

public class TestTableFunctionInvocation
        extends BasePlanTest
{
    private static final String TESTING_CATALOG = "mock";

    @BeforeAll
    public final void setup()
    {
        getPlanTester().installPlugin(new MockConnectorPlugin(MockConnectorFactory.builder()
                .withTableFunctions(ImmutableSet.of(
                        new DifferentArgumentTypesFunction(),
                        new TwoScalarArgumentsFunction(),
                        new DescriptorArgumentFunction(),
                        new TwoTableArgumentsFunction(),
                        new PassThroughFunction()))
                .withApplyTableFunction((session, handle) -> {
                    if (handle instanceof TestingTableFunctionPushdownHandle functionHandle) {
                        return Optional.of(new TableFunctionApplicationResult<>(functionHandle.getTableHandle(), functionHandle.getTableHandle().getColumns().orElseThrow()));
                    }
                    throw new IllegalStateException("Unsupported table function handle: " + handle.getClass().getSimpleName());
                })
                .build()));
        getPlanTester().createCatalog(TESTING_CATALOG, "mock", ImmutableMap.of());
    }

    @Test
    public void testTableFunctionInitialPlan()
    {
        assertPlan(
                """
                SELECT * FROM TABLE(mock.system.different_arguments_function(
                   INPUT_1 => TABLE(SELECT 'a') t1(c1) PARTITION BY c1 ORDER BY c1,
                   INPUT_3 => TABLE(SELECT 'b') t3(c3) PARTITION BY c3,
                   INPUT_2 => TABLE(VALUES 1) t2(c2),
                   ID => BIGINT '2001',
                   LAYOUT => DESCRIPTOR (x boolean, y bigint)
                   COPARTITION (t1, t3))) t
                """,
                CREATED,
                anyTree(tableFunction(builder -> builder
                                .name("different_arguments_function")
                                .addTableArgument(
                                        "INPUT_1",
                                        tableArgument(0)
                                                .specification(specification(ImmutableList.of("c1"), ImmutableList.of("c1"), ImmutableMap.of("c1", ASC_NULLS_LAST)))
                                                .passThroughColumns()
                                                .passThroughSymbols(ImmutableSet.of("c1")))
                                .addTableArgument(
                                        "INPUT_3",
                                        tableArgument(2)
                                                .specification(specification(ImmutableList.of("c3"), ImmutableList.of(), ImmutableMap.of()))
                                                .pruneWhenEmpty()
                                                .passThroughSymbols(ImmutableSet.of("c3")))
                                .addTableArgument(
                                        "INPUT_2",
                                        tableArgument(1)
                                                .rowSemantics()
                                                .passThroughColumns()
                                                .passThroughSymbols(ImmutableSet.of("c2")))
                                .addScalarArgument("ID", 2001L)
                                .addDescriptorArgument(
                                        "LAYOUT",
                                        descriptorArgument(new Descriptor(ImmutableList.of(
                                                new Field("X", Optional.of(BOOLEAN)),
                                                new Field("Y", Optional.of(BIGINT))))))
                                .addCopartitioning(ImmutableList.of("INPUT_1", "INPUT_3"))
                                .properOutputs(ImmutableList.of("OUTPUT")),
                        anyTree(project(ImmutableMap.of("c1", expression(new Constant(createVarcharType(1), Slices.utf8Slice("a")))), values(1))),
                        anyTree(values(ImmutableList.of("c2"), ImmutableList.of(ImmutableList.of(new Constant(INTEGER, 1L))))),
                        anyTree(project(ImmutableMap.of("c3", expression(new Constant(createVarcharType(1), Slices.utf8Slice("b")))), values(1))))));
    }

    @Test
    public void testTableFunctionInitialPlanWithCoercionForCopartitioning()
    {
        assertPlan(
                """
                SELECT * FROM TABLE(mock.system.two_table_arguments_function(
                   INPUT1 => TABLE(VALUES SMALLINT '1') t1(c1) PARTITION BY c1,
                   INPUT2 => TABLE(VALUES INTEGER '2') t2(c2) PARTITION BY c2
                   COPARTITION (t1, t2))) t
                """,
                CREATED,
                anyTree(tableFunction(builder -> builder
                                .name("two_table_arguments_function")
                                .addTableArgument(
                                        "INPUT1",
                                        tableArgument(0)
                                                .specification(specification(ImmutableList.of("c1_coerced"), ImmutableList.of(), ImmutableMap.of()))
                                                .passThroughSymbols(ImmutableSet.of("c1")))
                                .addTableArgument(
                                        "INPUT2",
                                        tableArgument(1)
                                                .specification(specification(ImmutableList.of("c2"), ImmutableList.of(), ImmutableMap.of()))
                                                .passThroughSymbols(ImmutableSet.of("c2")))
                                .addCopartitioning(ImmutableList.of("INPUT1", "INPUT2"))
                                .properOutputs(ImmutableList.of("COLUMN")),
                        project(ImmutableMap.of("c1_coerced", expression(new Cast(new Reference(SMALLINT, "c1"), INTEGER))),
                                anyTree(values(ImmutableList.of("c1"), ImmutableList.of(ImmutableList.of(new Constant(SMALLINT, 1L)))))),
                        anyTree(values(ImmutableList.of("c2"), ImmutableList.of(ImmutableList.of(new Constant(INTEGER, 2L))))))));
    }

    @Test
    public void testNullScalarArgument()
    {
        // the argument NUMBER has null default value
        assertPlan(
                " SELECT * FROM TABLE(mock.system.two_arguments_function(TEXT => null))",
                CREATED,
                anyTree(tableFunction(builder -> builder
                        .name("two_arguments_function")
                        .addScalarArgument("TEXT", null)
                        .addScalarArgument("NUMBER", null)
                        .properOutputs(ImmutableList.of("OUTPUT")))));
    }

    @Test
    public void testNullDescriptorArgument()
    {
        assertPlan(
                " SELECT * FROM TABLE(mock.system.descriptor_argument_function(SCHEMA => CAST(null AS DESCRIPTOR)))",
                CREATED,
                anyTree(tableFunction(builder -> builder
                        .name("descriptor_argument_function")
                        .addDescriptorArgument("SCHEMA", nullDescriptor())
                        .properOutputs(ImmutableList.of("OUTPUT")))));

        // the argument SCHEMA has null default value
        assertPlan(
                " SELECT * FROM TABLE(mock.system.descriptor_argument_function())",
                CREATED,
                anyTree(tableFunction(builder -> builder
                        .name("descriptor_argument_function")
                        .addDescriptorArgument("SCHEMA", nullDescriptor())
                        .properOutputs(ImmutableList.of("OUTPUT")))));
    }

    @Test
    public void testPruneTableFunctionColumns()
    {
        // all table function outputs are referenced with SELECT *, no pruning
        assertPlan("SELECT * FROM TABLE(mock.system.pass_through_function(input => TABLE(SELECT 1, true) t(a, b)))",
                strictOutput(
                        ImmutableList.of("x", "a", "b"),
                        tableFunctionProcessor(
                                builder -> builder
                                        .name("pass_through_function")
                                        .properOutputs(ImmutableList.of("x"))
                                        .passThroughSymbols(ImmutableList.of(ImmutableList.of("a", "b")))
                                        .requiredSymbols(ImmutableList.of(ImmutableList.of("a")))
                                        .specification(specification(ImmutableList.of(), ImmutableList.of(), ImmutableMap.of())),
                                values(ImmutableList.of("a", "b"), ImmutableList.of(ImmutableList.of(new Constant(INTEGER, 1L), TRUE))))));

        // no table function outputs are referenced. All pass-through symbols are pruned from the TableFunctionProcessorNode. The unused symbol "b" is pruned from the source values node.
        assertPlan("SELECT 'constant' c FROM TABLE(mock.system.pass_through_function(input => TABLE(SELECT 1, true) t(a, b)))",
                strictOutput(
                        ImmutableList.of("c"),
                        strictProject(
                                ImmutableMap.of("c", expression(new Constant(createVarcharType(8), Slices.utf8Slice("constant")))),
                                tableFunctionProcessor(
                                        builder -> builder
                                                .name("pass_through_function")
                                                .properOutputs(ImmutableList.of("x"))
                                                .passThroughSymbols(ImmutableList.of(ImmutableList.of()))
                                                .requiredSymbols(ImmutableList.of(ImmutableList.of("a")))
                                                .specification(specification(ImmutableList.of(), ImmutableList.of(), ImmutableMap.of())),
                                        values(ImmutableList.of("a"), ImmutableList.of(ImmutableList.of(new Constant(INTEGER, 1L))))))));
    }

    @Test
    public void testRemoveRedundantTableFunction()
    {
        assertPlan("SELECT * FROM TABLE(mock.system.pass_through_function(input => TABLE(SELECT 1, true WHERE false) t(a, b) PRUNE WHEN EMPTY))",
                output(values(ImmutableList.of("x", "a", "b"))));

        assertPlan(
                """
                SELECT *
                FROM TABLE(mock.system.two_table_arguments_function(
                                input1 => TABLE(SELECT 1, true WHERE false) t1(a, b) PRUNE WHEN EMPTY,
                                input2 => TABLE(SELECT 2, false) t2(c, d) KEEP WHEN EMPTY))
                """,
                output(values(ImmutableList.of("column"))));

        assertPlan(
                """
                SELECT *
                FROM TABLE(mock.system.two_table_arguments_function(
                                input1 => TABLE(SELECT 1, true WHERE false) t1(a, b) PRUNE WHEN EMPTY,
                                input2 => TABLE(SELECT 2, false WHERE false) t2(c, d) PRUNE WHEN EMPTY))
                """,
                output(values(ImmutableList.of("column"))));

        assertPlan(
                """
                SELECT *
                FROM TABLE(mock.system.two_table_arguments_function(
                                input1 => TABLE(SELECT 1, true WHERE false) t1(a, b) PRUNE WHEN EMPTY,
                                input2 => TABLE(SELECT 2, false WHERE false) t2(c, d) KEEP WHEN EMPTY))
                """,
                output(values(ImmutableList.of("column"))));

        assertPlan(
                """
                SELECT *
                FROM TABLE(mock.system.two_table_arguments_function(
                                input1 => TABLE(SELECT 1, true WHERE false) t1(a, b) KEEP WHEN EMPTY,
                                input2 => TABLE(SELECT 2, false WHERE false) t2(c, d) KEEP WHEN EMPTY))
                """,
                output(
                        node(TableFunctionProcessorNode.class,
                                values(ImmutableList.of("a", "marker_1", "c", "marker_2", "row_number")))));

        assertPlan(
                """
                SELECT *
                FROM TABLE(mock.system.two_table_arguments_function(
                                input1 => TABLE(SELECT 1, true WHERE false) t1(a, b) KEEP WHEN EMPTY,
                                input2 => TABLE(SELECT 2, false) t2(c, d) PRUNE WHEN EMPTY))
                """,
                output(
                        node(TableFunctionProcessorNode.class,
                                project(
                                        project(
                                                rowNumber(
                                                        builder -> builder.partitionBy(ImmutableList.of()),
                                                        values(ImmutableList.of("c"), ImmutableList.of(ImmutableList.of(new Constant(INTEGER, 2L)))))
                                                        .withAlias("input_2_row_number", new RowNumberSymbolMatcher()))))));
    }
}
