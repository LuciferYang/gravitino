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
package org.apache.gravitino.spark.connector.utils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import org.apache.gravitino.rel.SupportsPartitions;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.partitions.Partition;
import org.apache.spark.sql.catalyst.InternalRow;
import org.apache.spark.sql.catalyst.analysis.PartitionsAlreadyExistException;
import org.apache.spark.sql.catalyst.expressions.GenericInternalRow;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.apache.spark.unsafe.types.UTF8String;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests the Spark 4 flavor of {@code HiveGravitinoOperationOperator}, which lives in {@code
 * spark-common/src/main/spark4}. Spark 4.0 removed {@code PartitionAlreadyExistsException} and its
 * single-String constructor, so this flavor has to build the plural {@code
 * PartitionsAlreadyExistException} from the table name, ident and schema instead of forwarding
 * Gravitino's message. The Hive ITs cannot pin that translation down: Spark's own {@code
 * partitionExists} pre-check raises the same {@code PARTITIONS_ALREADY_EXIST} wording before {@code
 * createPartition} is ever reached.
 */
public class TestHiveGravitinoOperationOperator40 {

  private static final StructType PARTITION_SCHEMA =
      new StructType().add("dt", DataTypes.StringType);

  @Test
  void testCreatePartitionTranslatesGravitinoAlreadyExists() {
    HiveGravitinoOperationOperator operator = operatorThatFailsWith(alreadyExists());

    PartitionsAlreadyExistException exception =
        Assertions.assertThrows(
            PartitionsAlreadyExistException.class,
            () ->
                operator.createPartition(partitionRow(), Collections.emptyMap(), PARTITION_SCHEMA));

    // The name comes from the Gravitino table; the value is what Spark renders into its
    // PARTITIONS_ALREADY_EXIST message.
    Assertions.assertTrue(exception.getMessage().contains("hive_table"));
    Assertions.assertTrue(exception.getMessage().contains("dt"));
  }

  @Test
  void testCreatePartitionPropagatesOtherFailures() {
    IllegalStateException cause = new IllegalStateException("metastore is down");
    HiveGravitinoOperationOperator operator = operatorThatFailsWith(cause);

    IllegalStateException thrown =
        Assertions.assertThrows(
            IllegalStateException.class,
            () ->
                operator.createPartition(partitionRow(), Collections.emptyMap(), PARTITION_SCHEMA));
    Assertions.assertSame(cause, thrown);
  }

  private static InternalRow partitionRow() {
    return new GenericInternalRow(new Object[] {UTF8String.fromString("20260101")});
  }

  private static org.apache.gravitino.exceptions.PartitionAlreadyExistsException alreadyExists() {
    return new org.apache.gravitino.exceptions.PartitionAlreadyExistsException(
        "Hive partition dt=20260101 already exists in Hive Metastore");
  }

  private static HiveGravitinoOperationOperator operatorThatFailsWith(RuntimeException failure) {
    SupportsPartitions partitions = mock(SupportsPartitions.class);
    doThrow(failure).when(partitions).addPartition(any(Partition.class));

    Table table = mock(Table.class);
    when(table.name()).thenReturn("hive_table");
    when(table.supportPartitions()).thenReturn(partitions);

    return new HiveGravitinoOperationOperator(table);
  }
}
