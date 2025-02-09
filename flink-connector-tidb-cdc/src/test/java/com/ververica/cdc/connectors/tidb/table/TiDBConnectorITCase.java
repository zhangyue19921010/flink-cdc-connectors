/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ververica.cdc.connectors.tidb.table;

import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.apache.flink.table.planner.factories.TestValuesTableFactory;
import org.apache.flink.table.utils.LegacyRowResource;

import com.ververica.cdc.connectors.tidb.TiDBTestBase;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Integration tests for TiDB change stream event SQL source. */
public class TiDBConnectorITCase extends TiDBTestBase {

    private final StreamExecutionEnvironment env =
            StreamExecutionEnvironment.getExecutionEnvironment().setParallelism(1);
    private final StreamTableEnvironment tEnv =
            StreamTableEnvironment.create(
                    env,
                    EnvironmentSettings.newInstance().useBlinkPlanner().inStreamingMode().build());

    @ClassRule public static LegacyRowResource usesLegacyRows = LegacyRowResource.INSTANCE;

    @Before
    public void before() {
        TestValuesTableFactory.clearAllData();
        env.setParallelism(1);
    }

    @Test
    public void testConsumingAllEvents() throws Exception {
        initializeTidbTable("inventory");
        String sourceDDL =
                String.format(
                        "CREATE TABLE tidb_source ("
                                + " `id` INT NOT NULL,"
                                + " name STRING,"
                                + " description STRING,"
                                + " weight DECIMAL(20, 10),"
                                + " PRIMARY KEY (`id`) NOT ENFORCED"
                                + ") WITH ("
                                + " 'connector' = 'tidb-cdc',"
                                + " 'hostname' = '%s',"
                                + " 'tikv.grpc.timeout_in_ms' = '20000',"
                                + " 'pd-addresses' = '%s',"
                                + " 'username' = '%s',"
                                + " 'password' = '%s',"
                                + " 'database-name' = '%s',"
                                + " 'table-name' = '%s'"
                                + ")",
                        TIDB.getContainerIpAddress(),
                        PD.getContainerIpAddress() + ":" + PD.getMappedPort(PD_PORT_ORIGIN),
                        TIDB_USER,
                        TIDB_PASSWORD,
                        "inventory",
                        "products");

        String sinkDDL =
                "CREATE TABLE sink ("
                        + " `id` INT NOT NULL,"
                        + " name STRING,"
                        + " description STRING,"
                        + " weight DECIMAL(20, 10),"
                        + " PRIMARY KEY (`id`) NOT ENFORCED"
                        + ") WITH ("
                        + " 'connector' = 'values',"
                        + " 'sink-insert-only' = 'false',"
                        + " 'sink-expected-messages-num' = '20'"
                        + ")";
        tEnv.executeSql(sourceDDL);
        tEnv.executeSql(sinkDDL);
        // async submit job
        TableResult result = tEnv.executeSql("INSERT INTO sink SELECT * FROM tidb_source");

        // wait for snapshot finished and begin binlog
        waitForSinkSize("sink", 9);

        try (Connection connection = getJdbcConnection("inventory");
                Statement statement = connection.createStatement()) {

            statement.execute(
                    "UPDATE products SET description='18oz carpenter hammer' WHERE id=106;");
            statement.execute("UPDATE products SET weight='5.1' WHERE id=107;");
            statement.execute(
                    "INSERT INTO products VALUES (default,'jacket','water resistent white wind breaker',0.2);"); // 110
            statement.execute(
                    "INSERT INTO products VALUES (default,'scooter','Big 2-wheel scooter ',5.18);");
            statement.execute(
                    "UPDATE products SET description='new water resistent white wind breaker', weight='0.5' WHERE id=110;");
            statement.execute("UPDATE products SET weight='5.17' WHERE id=111;");
            statement.execute("DELETE FROM products WHERE id=111;");
        }

        waitForSinkSize("sink", 16);

        /*
         * <pre>
         * The final database table looks like this:
         *
         * > SELECT * FROM products;
         * +-----+--------------------+---------------------------------------------------------+--------+
         * | id  | name               | description                                             | weight |
         * +-----+--------------------+---------------------------------------------------------+--------+
         * | 101 | scooter            | Small 2-wheel scooter                                   |   3.14 |
         * | 102 | car battery        | 12V car battery                                         |    8.1 |
         * | 103 | 12-pack drill bits | 12-pack of drill bits with sizes ranging from #40 to #3 |    0.8 |
         * | 104 | hammer             | 12oz carpenter's hammer                                 |   0.75 |
         * | 105 | hammer             | 14oz carpenter's hammer                                 |  0.875 |
         * | 106 | hammer             | 18oz carpenter hammer                                   |      1 |
         * | 107 | rocks              | box of assorted rocks                                   |    5.1 |
         * | 108 | jacket             | water resistent black wind breaker                      |    0.1 |
         * | 109 | spare tire         | 24 inch spare tire                                      |   22.2 |
         * | 110 | jacket             | new water resistent white wind breaker                  |    0.5 |
         * +-----+--------------------+---------------------------------------------------------+--------+
         * </pre>
         */

        List<String> expected =
                Arrays.asList(
                        "+I(101,scooter,Small 2-wheel scooter,3.1400000000)",
                        "+I(102,car battery,12V car battery,8.1000000000)",
                        "+I(103,12-pack drill bits,12-pack of drill bits with sizes ranging from #40 to #3,0.8000000000)",
                        "+I(104,hammer,12oz carpenter's hammer,0.7500000000)",
                        "+I(105,hammer,14oz carpenter's hammer,0.8750000000)",
                        "+I(106,hammer,16oz carpenter's hammer,1.0000000000)",
                        "+I(107,rocks,box of assorted rocks,5.3000000000)",
                        "+I(108,jacket,water resistent black wind breaker,0.1000000000)",
                        "+I(109,spare tire,24 inch spare tire,22.2000000000)",
                        "+U(106,hammer,18oz carpenter hammer,1.0000000000)",
                        "+U(107,rocks,box of assorted rocks,5.1000000000)",
                        "+I(110,jacket,water resistent white wind breaker,0.2000000000)",
                        "+I(111,scooter,Big 2-wheel scooter ,5.1800000000)",
                        "+U(110,jacket,new water resistent white wind breaker,0.5000000000)",
                        "+U(111,scooter,Big 2-wheel scooter ,5.1700000000)",
                        "-D(111,scooter,Big 2-wheel scooter ,5.1700000000)");
        List<String> actual = TestValuesTableFactory.getRawResults("sink");
        assertEqualsInAnyOrder(expected, actual);
        result.getJobClient().get().cancel().get();
    }

    @Test
    public void testDeleteColumn() throws Exception {
        initializeTidbTable("inventory");
        String sourceDDL =
                String.format(
                        "CREATE TABLE tidb_source ("
                                + " `id` INT NOT NULL,"
                                + " name STRING,"
                                + " description STRING,"
                                + " weight DECIMAL(20, 10),"
                                + " PRIMARY KEY (`id`) NOT ENFORCED"
                                + ") WITH ("
                                + " 'connector' = 'tidb-cdc',"
                                + " 'hostname' = '%s',"
                                + " 'tikv.grpc.timeout_in_ms' = '20000',"
                                + " 'pd-addresses' = '%s',"
                                + " 'username' = '%s',"
                                + " 'password' = '%s',"
                                + " 'database-name' = '%s',"
                                + " 'table-name' = '%s'"
                                + ")",
                        TIDB.getContainerIpAddress(),
                        PD.getContainerIpAddress() + ":" + PD.getMappedPort(PD_PORT_ORIGIN),
                        TIDB_USER,
                        TIDB_PASSWORD,
                        "inventory",
                        "products");

        String sinkDDL =
                "CREATE TABLE sink ("
                        + " `id` INT NOT NULL,"
                        + " name STRING,"
                        + " description STRING,"
                        + " weight DECIMAL(20, 10),"
                        + " PRIMARY KEY (`id`) NOT ENFORCED"
                        + ") WITH ("
                        + " 'connector' = 'values',"
                        + " 'sink-insert-only' = 'false',"
                        + " 'sink-expected-messages-num' = '20'"
                        + ")";
        tEnv.executeSql(sourceDDL);
        tEnv.executeSql(sinkDDL);
        // async submit job
        TableResult result = tEnv.executeSql("INSERT INTO sink SELECT * FROM tidb_source");

        // wait for snapshot finished and begin binlog
        waitForSinkSize("sink", 9);

        try (Connection connection = getJdbcConnection("inventory");
                Statement statement = connection.createStatement()) {

            statement.execute("ALTER TABLE products DROP COLUMN description");

            statement.execute("UPDATE products SET weight='5.1' WHERE id=107;");
            statement.execute("INSERT INTO products VALUES (default,'jacket',0.2);"); // 110
            statement.execute("INSERT INTO products VALUES (default,'scooter',5.18);"); // 111
            statement.execute("UPDATE products SET name='jacket2', weight='0.5' WHERE id=110;");
            statement.execute("UPDATE products SET weight='5.17' WHERE id=111;");
            statement.execute("DELETE FROM products WHERE id=111;");
        }

        waitForSinkSize("sink", 15);

        List<String> expected =
                Arrays.asList(
                        "+I(101,scooter,Small 2-wheel scooter,3.1400000000)",
                        "+I(102,car battery,12V car battery,8.1000000000)",
                        "+I(103,12-pack drill bits,12-pack of drill bits with sizes ranging from #40 to #3,0.8000000000)",
                        "+I(104,hammer,12oz carpenter's hammer,0.7500000000)",
                        "+I(105,hammer,14oz carpenter's hammer,0.8750000000)",
                        "+I(106,hammer,16oz carpenter's hammer,1.0000000000)",
                        "+I(107,rocks,box of assorted rocks,5.3000000000)",
                        "+I(108,jacket,water resistent black wind breaker,0.1000000000)",
                        "+I(109,spare tire,24 inch spare tire,22.2000000000)",
                        "+U(107,rocks,null,5.1000000000)",
                        "+I(110,jacket,null,0.2000000000)",
                        "+I(111,scooter,null,5.1800000000)",
                        "+U(110,jacket2,null,0.5000000000)",
                        "+U(111,scooter,null,5.1700000000)",
                        "-D(111,scooter,null,5.1700000000)");
        List<String> actual = TestValuesTableFactory.getRawResults("sink");
        assertEqualsInAnyOrder(expected, actual);
        result.getJobClient().get().cancel().get();
    }

    @Test
    public void testAddColumn() throws Exception {
        initializeTidbTable("inventory");
        String sourceDDL =
                String.format(
                        "CREATE TABLE tidb_source ("
                                + " `id` INT NOT NULL,"
                                + " name STRING,"
                                + " description STRING,"
                                + " weight DECIMAL(20, 10),"
                                + " PRIMARY KEY (`id`) NOT ENFORCED"
                                + ") WITH ("
                                + " 'connector' = 'tidb-cdc',"
                                + " 'hostname' = '%s',"
                                + " 'tikv.grpc.timeout_in_ms' = '20000',"
                                + " 'pd-addresses' = '%s',"
                                + " 'username' = '%s',"
                                + " 'password' = '%s',"
                                + " 'database-name' = '%s',"
                                + " 'table-name' = '%s'"
                                + ")",
                        TIDB.getContainerIpAddress(),
                        PD.getContainerIpAddress() + ":" + PD.getMappedPort(PD_PORT_ORIGIN),
                        TIDB_USER,
                        TIDB_PASSWORD,
                        "inventory",
                        "products");

        String sinkDDL =
                "CREATE TABLE sink ("
                        + " `id` INT NOT NULL,"
                        + " name STRING,"
                        + " description STRING,"
                        + " weight DECIMAL(20, 10),"
                        + " PRIMARY KEY (`id`) NOT ENFORCED"
                        + ") WITH ("
                        + " 'connector' = 'values',"
                        + " 'sink-insert-only' = 'false',"
                        + " 'sink-expected-messages-num' = '20'"
                        + ")";
        tEnv.executeSql(sourceDDL);
        tEnv.executeSql(sinkDDL);
        // async submit job
        TableResult result = tEnv.executeSql("INSERT INTO sink SELECT * FROM tidb_source");

        // wait for snapshot finished and begin binlog
        waitForSinkSize("sink", 9);

        try (Connection connection = getJdbcConnection("inventory");
                Statement statement = connection.createStatement()) {

            statement.execute("ALTER TABLE products ADD COLUMN serialnum INTEGER");

            statement.execute(
                    "UPDATE products SET description='18oz carpenter hammer' WHERE id=106;");
            statement.execute("UPDATE products SET weight='5.1' WHERE id=107;");
            statement.execute(
                    "INSERT INTO products VALUES (default,'jacket','water resistent white wind breaker',0.2,null);"); // 110
            statement.execute(
                    "INSERT INTO products VALUES (default,'scooter','Big 2-wheel scooter ',5.18,1);");
            statement.execute(
                    "UPDATE products SET description='new water resistent white wind breaker', weight='0.5' WHERE id=110;");
            statement.execute("UPDATE products SET weight='5.17' WHERE id=111;");
            statement.execute("DELETE FROM products WHERE id=111;");
        }

        waitForSinkSize("sink", 16);

        List<String> expected =
                Arrays.asList(
                        "+I(101,scooter,Small 2-wheel scooter,3.1400000000)",
                        "+I(102,car battery,12V car battery,8.1000000000)",
                        "+I(103,12-pack drill bits,12-pack of drill bits with sizes ranging from #40 to #3,0.8000000000)",
                        "+I(104,hammer,12oz carpenter's hammer,0.7500000000)",
                        "+I(105,hammer,14oz carpenter's hammer,0.8750000000)",
                        "+I(106,hammer,16oz carpenter's hammer,1.0000000000)",
                        "+I(107,rocks,box of assorted rocks,5.3000000000)",
                        "+I(108,jacket,water resistent black wind breaker,0.1000000000)",
                        "+I(109,spare tire,24 inch spare tire,22.2000000000)",
                        "+U(106,hammer,18oz carpenter hammer,1.0000000000)",
                        "+U(107,rocks,box of assorted rocks,5.1000000000)",
                        "+I(110,jacket,water resistent white wind breaker,0.2000000000)",
                        "+I(111,scooter,Big 2-wheel scooter ,5.1800000000)",
                        "+U(110,jacket,new water resistent white wind breaker,0.5000000000)",
                        "+U(111,scooter,Big 2-wheel scooter ,5.1700000000)",
                        "-D(111,scooter,Big 2-wheel scooter ,5.1700000000)");
        List<String> actual = TestValuesTableFactory.getRawResults("sink");
        assertEqualsInAnyOrder(expected, actual);
        result.getJobClient().get().cancel().get();
    }

    @Test
    public void testMetadataColumns() throws Exception {
        initializeTidbTable("inventory");

        String sourceDDL =
                String.format(
                        "CREATE TABLE tidb_source ("
                                + " db_name STRING METADATA FROM 'database_name' VIRTUAL,"
                                + " table_name STRING METADATA VIRTUAL,"
                                + " `id` INT NOT NULL,"
                                + " name STRING,"
                                + " description STRING,"
                                + " weight DECIMAL(20, 10),"
                                + " PRIMARY KEY (`id`) NOT ENFORCED"
                                + ") WITH ("
                                + " 'connector' = 'tidb-cdc',"
                                + " 'hostname' = '%s',"
                                + " 'tikv.grpc.timeout_in_ms' = '20000',"
                                + " 'pd-addresses' = '%s',"
                                + " 'username' = '%s',"
                                + " 'password' = '%s',"
                                + " 'database-name' = '%s',"
                                + " 'table-name' = '%s'"
                                + ")",
                        TIDB.getContainerIpAddress(),
                        PD.getContainerIpAddress() + ":" + PD.getMappedPort(PD_PORT_ORIGIN),
                        TIDB_USER,
                        TIDB_PASSWORD,
                        "inventory",
                        "products");

        String sinkDDL =
                "CREATE TABLE sink ("
                        + " database_name STRING,"
                        + " table_name STRING,"
                        + " `id` DECIMAL(20, 0) NOT NULL,"
                        + " name STRING,"
                        + " description STRING,"
                        + " weight DECIMAL(20, 10),"
                        + " primary key (database_name, table_name, id) not enforced"
                        + ") WITH ("
                        + " 'connector' = 'values',"
                        + " 'sink-insert-only' = 'false'"
                        + ")";
        tEnv.executeSql(sourceDDL);
        tEnv.executeSql(sinkDDL);

        // async submit job
        TableResult result = tEnv.executeSql("INSERT INTO sink SELECT * FROM tidb_source");

        // wait for snapshot finished and begin binlog
        waitForSinkSize("sink", 9);

        try (Connection connection = getJdbcConnection("inventory");
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "UPDATE products SET description='18oz carpenter hammer' WHERE id=106;");
        }

        waitForSinkSize("sink", 10);

        List<String> expected =
                Arrays.asList(
                        "+I(inventory,products,101,scooter,Small 2-wheel scooter,3.1400000000)",
                        "+I(inventory,products,102,car battery,12V car battery,8.1000000000)",
                        "+I(inventory,products,103,12-pack drill bits,12-pack of drill bits with sizes ranging from #40 to #3,0.8000000000)",
                        "+I(inventory,products,104,hammer,12oz carpenter's hammer,0.7500000000)",
                        "+I(inventory,products,105,hammer,14oz carpenter's hammer,0.8750000000)",
                        "+I(inventory,products,106,hammer,16oz carpenter's hammer,1.0000000000)",
                        "+I(inventory,products,107,rocks,box of assorted rocks,5.3000000000)",
                        "+I(inventory,products,108,jacket,water resistent black wind breaker,0.1000000000)",
                        "+I(inventory,products,109,spare tire,24 inch spare tire,22.2000000000)",
                        "+U(inventory,products,106,hammer,18oz carpenter hammer,1.0000000000)");
        List<String> actual = TestValuesTableFactory.getRawResults("sink");
        assertEqualsInAnyOrder(expected, actual);
        result.getJobClient().get().cancel().get();
    }

    private static void waitForSinkSize(String sinkName, int expectedSize)
            throws InterruptedException {
        while (sinkSize(sinkName) < expectedSize) {
            Thread.sleep(100);
        }
    }

    private static int sinkSize(String sinkName) {
        synchronized (TestValuesTableFactory.class) {
            try {
                return TestValuesTableFactory.getRawResults(sinkName).size();
            } catch (IllegalArgumentException e) {
                // job is not started yet
                return 0;
            }
        }
    }

    public static void assertEqualsInAnyOrder(List<String> expected, List<String> actual) {
        assertTrue(expected != null && actual != null);
        assertEqualsInOrder(
                expected.stream().sorted().collect(Collectors.toList()),
                actual.stream().sorted().collect(Collectors.toList()));
    }

    public static void assertEqualsInOrder(List<String> expected, List<String> actual) {
        assertTrue(expected != null && actual != null);
        assertEquals(expected.size(), actual.size());
        assertArrayEquals(expected.toArray(new String[0]), actual.toArray(new String[0]));
    }
}
