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
package io.trino.plugin.tpcds.statistics;

import io.trino.Session;
import io.trino.plugin.tpcds.TpcdsConnectorFactory;
import io.trino.testing.LocalQueryRunner;
import io.trino.testing.statistics.StatisticsAssertion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static io.trino.SystemSessionProperties.COLLECT_PLAN_STATISTICS_FOR_ALL_QUERIES;
import static io.trino.testing.TestingSession.testSessionBuilder;
import static io.trino.testing.statistics.MetricComparisonStrategies.absoluteError;
import static io.trino.testing.statistics.MetricComparisonStrategies.defaultTolerance;
import static io.trino.testing.statistics.MetricComparisonStrategies.noError;
import static io.trino.testing.statistics.MetricComparisonStrategies.relativeError;
import static io.trino.testing.statistics.Metrics.OUTPUT_ROW_COUNT;
import static io.trino.testing.statistics.Metrics.distinctValuesCount;
import static java.util.Collections.emptyMap;
import static org.junit.jupiter.api.TestInstance.Lifecycle.PER_CLASS;

@TestInstance(PER_CLASS)
public class TestTpcdsLocalStats
{
    private StatisticsAssertion statisticsAssertion;

    @BeforeAll
    public void setUp()
    {
        Session defaultSession = testSessionBuilder()
                .setCatalog("tpcds")
                .setSchema("sf1")
                // Stats for non-EXPLAIN queries are not collected by default
                .setSystemProperty(COLLECT_PLAN_STATISTICS_FOR_ALL_QUERIES, "true")
                .build();

        LocalQueryRunner queryRunner = LocalQueryRunner.create(defaultSession);
        queryRunner.createCatalog("tpcds", new TpcdsConnectorFactory(), emptyMap());
        statisticsAssertion = new StatisticsAssertion(queryRunner);
    }

    @AfterAll
    public void tearDown()
    {
        statisticsAssertion.close();
        statisticsAssertion = null;
    }

    @Test
    public void testTableScanStats()
    {
        statisticsAssertion.check("SELECT * FROM item",
                checks -> checks
                        .estimate(OUTPUT_ROW_COUNT, defaultTolerance())
                        .verifyExactColumnStatistics("i_item_sk")
                        .verifyCharacterColumnStatistics("i_item_id", noError())
                        .verifyColumnStatistics("i_brand_id", absoluteError(0.01)) // nulls fraction estimated as 0
                        .verifyCharacterColumnStatistics("i_color", absoluteError(0.01))); // nulls fraction estimated as 0
    }

    @Test
    public void testCharComparison()
    {
        // cd_marital_status is char(1)
        statisticsAssertion.check("SELECT * FROM customer_demographics WHERE cd_marital_status = 'D'",
                checks -> checks
                        .estimate(OUTPUT_ROW_COUNT, defaultTolerance())
                        .estimate(distinctValuesCount("cd_marital_status"), noError()));
        statisticsAssertion.check("SELECT * FROM customer_demographics WHERE 'D' = cd_marital_status",
                checks -> checks
                        .estimate(OUTPUT_ROW_COUNT, defaultTolerance())
                        .estimate(distinctValuesCount("cd_marital_status"), noError()));

        // i_category is char(50)
        statisticsAssertion.check("SELECT * FROM item WHERE i_category = 'Women                                             '",
                checks -> checks.estimate(OUTPUT_ROW_COUNT, defaultTolerance()));
        statisticsAssertion.check("SELECT * FROM item WHERE 'Women                                             ' = i_category",
                checks -> checks.estimate(OUTPUT_ROW_COUNT, defaultTolerance()));
        statisticsAssertion.check("SELECT * FROM item WHERE i_category = cast('Women' as char(50))",
                checks -> checks.estimate(OUTPUT_ROW_COUNT, defaultTolerance()));
    }

    @Test
    public void testDecimalComparison()
    {
        // ca_gmt_offset is decimal(5,2)
        statisticsAssertion.check("SELECT * FROM customer_address WHERE ca_gmt_offset = -7",
                checks -> checks
                        .estimate(OUTPUT_ROW_COUNT, relativeError(.6)) // distribution is non-uniform, so we need higher tolerance
                        .estimate(distinctValuesCount("ca_gmt_offset"), noError()));
        statisticsAssertion.check("SELECT * FROM customer_address WHERE -7 = ca_gmt_offset",
                checks -> checks
                        .estimate(OUTPUT_ROW_COUNT, relativeError(.6)) // distribution is non-uniform, so we need higher tolerance
                        .estimate(distinctValuesCount("ca_gmt_offset"), noError()));
        statisticsAssertion.check("SELECT * FROM customer_address WHERE ca_gmt_offset = (decimal '-7.0')",
                checks -> checks
                        .estimate(OUTPUT_ROW_COUNT, relativeError(.6)) // distribution is non-uniform, so we need higher tolerance
                        .estimate(distinctValuesCount("ca_gmt_offset"), noError()));
        statisticsAssertion.check("SELECT * FROM customer_address WHERE ca_gmt_offset = -7.0",
                checks -> checks
                        .estimate(OUTPUT_ROW_COUNT, relativeError(.6))); // distribution is non-uniform, so we need higher tolerance

        // p_cost is decimal(15,2)
        statisticsAssertion.check("SELECT * FROM promotion WHERE p_cost < 1", // p_cost is always 1000.00, so no rows should be left
                checks -> checks.estimate(OUTPUT_ROW_COUNT, noError()));
        statisticsAssertion.check("SELECT * FROM promotion WHERE 1 > p_cost", // p_cost is always 1000.00, so no rows should be left
                checks -> checks.estimate(OUTPUT_ROW_COUNT, noError()));
        statisticsAssertion.check("SELECT * FROM promotion WHERE p_cost < 2000.0", // p_cost is always 1000.00, so all rows should be left
                checks -> checks.estimate(OUTPUT_ROW_COUNT, noError()));
    }

    @Test
    public void testIn()
    {
        statisticsAssertion.check("SELECT * FROM item WHERE i_category IN ('Women                                             ')",
                checks -> checks.estimate(OUTPUT_ROW_COUNT, defaultTolerance()));
        statisticsAssertion.check("SELECT * FROM ship_mode WHERE sm_carrier IN ('DHL                 ', 'BARIAN              ')",
                checks -> checks.estimate(OUTPUT_ROW_COUNT, defaultTolerance()));
    }
}
