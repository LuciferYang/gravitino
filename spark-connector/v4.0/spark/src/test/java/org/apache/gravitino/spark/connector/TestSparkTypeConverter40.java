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
package org.apache.gravitino.spark.connector;

import org.apache.gravitino.rel.types.Type;
import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.spark.connector.utils.SparkPartitionUtils;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow;
import org.apache.spark.sql.types.CharType;
import org.apache.spark.sql.types.DataType;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.VarcharType;
import org.apache.spark.unsafe.types.UTF8String;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Type-conversion tests for the Spark 4 build of the sources shared with {@code spark-common}.
 *
 * <p>{@code spark-common}'s own suites compile only against Spark 3.3, and {@code spark4-common}
 * reuses its {@code src/main/java} without a test source set, so nothing else covers those classes
 * as built for Spark 4. The gap hid a real bug: Spark 4.0 made {@link VarcharType} and {@link
 * CharType} extend {@code StringType}, so an {@code instanceof StringType} check placed first
 * swallowed both and dropped the length.
 */
public class TestSparkTypeConverter40 {

  private final SparkTypeConverter sparkTypeConverter = new SparkTypeConverter34();

  @Test
  void testVarcharAndCharKeepLengthOnSpark4() {
    Assertions.assertEquals(
        Types.VarCharType.of(10), sparkTypeConverter.toGravitinoType(VarcharType.apply(10)));
    Assertions.assertEquals(
        Types.FixedCharType.of(5), sparkTypeConverter.toGravitinoType(CharType.apply(5)));
    Assertions.assertEquals(
        Types.StringType.get(), sparkTypeConverter.toGravitinoType(DataTypes.StringType));
  }

  @Test
  void testVarcharAndCharRoundTripOnSpark4() {
    assertRoundTrip(Types.VarCharType.of(10), VarcharType.apply(10));
    assertRoundTrip(Types.FixedCharType.of(5), CharType.apply(5));
    assertRoundTrip(Types.StringType.get(), DataTypes.StringType);
  }

  @Test
  void testPartitionLiteralKeepsVarcharAndCharTypeOnSpark4() {
    InternalRow row =
        new GenericInternalRow(
            new Object[] {UTF8String.fromString("a"), UTF8String.fromString("b")});

    Assertions.assertEquals(
        Types.VarCharType.of(10),
        SparkPartitionUtils.toGravitinoLiteral(row, 0, VarcharType.apply(10)).dataType());
    Assertions.assertEquals(
        Types.FixedCharType.of(5),
        SparkPartitionUtils.toGravitinoLiteral(row, 1, CharType.apply(5)).dataType());
  }

  @Test
  void testTimestampNTZOnSpark4() {
    assertRoundTrip(Types.TimestampType.withoutTimeZone(), DataTypes.TimestampNTZType);
    assertRoundTrip(Types.TimestampType.withTimeZone(), DataTypes.TimestampType);
  }

  private void assertRoundTrip(Type gravitinoType, DataType sparkType) {
    Assertions.assertEquals(sparkType, sparkTypeConverter.toSparkType(gravitinoType));
    Assertions.assertEquals(gravitinoType, sparkTypeConverter.toGravitinoType(sparkType));
  }
}
