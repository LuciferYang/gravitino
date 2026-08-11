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
package org.apache.gravitino.spark.connector.integration.test.iceberg;

import org.apache.gravitino.spark.connector.integration.test.util.SparkMetadataColumnInfo;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructField;

public class SparkIcebergCatalogHiveBackendIT40 extends SparkIcebergCatalogHiveBackendIT {

  /**
   * Iceberg 1.11 reports different metadata columns from its Spark 4.0 module than from its 3.4/3.5
   * ones. The row-lineage columns ({@code _row_id}, {@code _last_updated_sequence_number}) only
   * show up for tables that support row lineage, meaning format version 3 and above, while the 3.x
   * modules report them unconditionally; these ITs create format version 2 tables, Iceberg's
   * default. {@code _spec_id} is also nullable here and non-nullable on 3.x.
   */
  @Override
  protected SparkMetadataColumnInfo[] getIcebergMetadataColumns() {
    return new SparkMetadataColumnInfo[] {
      new SparkMetadataColumnInfo("_spec_id", DataTypes.IntegerType, true),
      new SparkMetadataColumnInfo(
          "_partition",
          DataTypes.createStructType(
              new StructField[] {DataTypes.createStructField("name", DataTypes.StringType, true)}),
          true),
      new SparkMetadataColumnInfo("_file", DataTypes.StringType, false),
      new SparkMetadataColumnInfo("_pos", DataTypes.LongType, false),
      new SparkMetadataColumnInfo("_deleted", DataTypes.BooleanType, false)
    };
  }
}
