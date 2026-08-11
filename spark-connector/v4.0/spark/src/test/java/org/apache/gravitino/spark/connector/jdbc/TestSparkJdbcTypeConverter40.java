/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.spark.connector.jdbc;

import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.spark.connector.SparkTypeConverter;
import org.apache.spark.sql.types.DataTypes;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests the Spark 4 build of {@code SparkJdbcTypeConverter34}. Neither Spark line covered this
 * converter before, and its two overrides both exist to work around JDBC-specific behavior that a
 * type-hierarchy change could silently undo.
 */
public class TestSparkJdbcTypeConverter40 {

  private final SparkTypeConverter sparkJdbcTypeConverter = new SparkJdbcTypeConverter34();

  @Test
  void testVarcharBecomesStringForJdbc() {
    // Spark's JDBC dialects reject VarcharType, so the converter widens it to StringType.
    Assertions.assertEquals(
        DataTypes.StringType, sparkJdbcTypeConverter.toSparkType(Types.VarCharType.of(10)));
  }

  @Test
  void testTimestampWithoutTimeZoneStaysTimestampType() {
    // Must not become TimestampNTZType: the MySQL dialect only pushes TimestampType literals down
    // correctly, and NTZ literals produce invalid SQL.
    Assertions.assertEquals(
        DataTypes.TimestampType,
        sparkJdbcTypeConverter.toSparkType(Types.TimestampType.withoutTimeZone()));
    Assertions.assertEquals(
        DataTypes.TimestampType,
        sparkJdbcTypeConverter.toSparkType(Types.TimestampType.withTimeZone()));
  }

  @Test
  void testOtherTypesDelegateToTheBaseConverter() {
    Assertions.assertEquals(
        DataTypes.IntegerType, sparkJdbcTypeConverter.toSparkType(Types.IntegerType.get()));
    Assertions.assertEquals(
        Types.VarCharType.of(10),
        sparkJdbcTypeConverter.toGravitinoType(org.apache.spark.sql.types.VarcharType.apply(10)));
  }
}
