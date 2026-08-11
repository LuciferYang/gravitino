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

import org.apache.gravitino.rel.types.Types;
import org.apache.gravitino.spark.connector.hive.SparkHiveTypeConverter34;
import org.apache.spark.sql.types.DataTypes;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Spark 4 counterpart of {@code TestSparkHiveTypeConverter34} in the {@code spark-3.4} module. The
 * converter is compiled twice, once per Spark line, and only the 3.4 build was covered.
 */
public class TestSparkHiveTypeConverter40 {

  private final SparkTypeConverter sparkHiveTypeConverter = new SparkHiveTypeConverter34();

  @Test
  void testTimestampNTZIsRejectedForHive() {
    UnsupportedOperationException exception =
        Assertions.assertThrows(
            UnsupportedOperationException.class,
            () -> sparkHiveTypeConverter.toGravitinoType(DataTypes.TimestampNTZType));
    Assertions.assertTrue(exception.getMessage().contains("Hive does not support 'timestamp_ntz'"));
  }

  @Test
  void testTimestampLosesTimeZoneForHive() {
    Assertions.assertEquals(
        Types.TimestampType.withoutTimeZone(),
        sparkHiveTypeConverter.toGravitinoType(DataTypes.TimestampType));
    Assertions.assertEquals(
        DataTypes.TimestampType,
        sparkHiveTypeConverter.toSparkType(Types.TimestampType.withoutTimeZone()));
  }
}
