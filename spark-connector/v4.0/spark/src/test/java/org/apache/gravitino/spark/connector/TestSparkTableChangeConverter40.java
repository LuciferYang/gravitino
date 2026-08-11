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

import org.apache.gravitino.rel.expressions.literals.Literals;
import org.apache.spark.sql.connector.catalog.TableChange;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Spark 4 counterpart of {@code TestSparkTableChangeConverter34} in the {@code spark-3.4} module.
 * Spark 4.0 kept {@code UpdateColumnDefaultValue}'s shape, so the same assertions apply; what is
 * new is that they now run against the Spark 4 build of the converter.
 */
public class TestSparkTableChangeConverter40 {

  private final SparkTableChangeConverter sparkTableChangeConverter =
      new SparkTableChangeConverter34(new SparkTypeConverter34());

  @Test
  void testUpdateColumnDefaultValue() {
    String[] fieldNames = new String[] {"col"};
    String defaultValue = "col_default_value";
    TableChange.UpdateColumnDefaultValue sparkChange =
        (TableChange.UpdateColumnDefaultValue)
            TableChange.updateColumnDefaultValue(fieldNames, defaultValue);

    org.apache.gravitino.rel.TableChange gravitinoChange =
        sparkTableChangeConverter.toGravitinoTableChange(sparkChange);

    Assertions.assertInstanceOf(
        org.apache.gravitino.rel.TableChange.UpdateColumnDefaultValue.class, gravitinoChange);
    org.apache.gravitino.rel.TableChange.UpdateColumnDefaultValue converted =
        (org.apache.gravitino.rel.TableChange.UpdateColumnDefaultValue) gravitinoChange;

    Assertions.assertArrayEquals(sparkChange.fieldNames(), converted.fieldName());
    Assertions.assertEquals(Literals.stringLiteral(defaultValue), converted.getNewDefaultValue());
  }
}
