/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.lance.common.ops.gravitino;

import static org.apache.gravitino.lance.common.utils.LanceConstants.LANCE_LOCATION;
import static org.apache.gravitino.lance.common.utils.LanceConstants.LANCE_TABLE_DECLARED;

import java.io.IOException;
import java.util.Map;
import org.apache.arrow.memory.BufferAllocator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.lance.Dataset;

public class TestGravitinoLanceTableOperations {

  // Mirrors the real lance-core message for a dataset whose files do not exist yet.
  private static final String DATASET_ABSENT_MESSAGE =
      "Dataset at path /tmp/table was not found: Not found: /tmp/table/_versions";

  @Test
  public void testDeclaredTableStaysDeclaredOnlyWhenDatasetIsAbsent() {
    TestableGravitinoLanceTableOperations operations =
        new TestableGravitinoLanceTableOperations(
            new IllegalArgumentException(DATASET_ABSENT_MESSAGE));

    boolean isOnlyDeclared =
        operations.isOnlyDeclared(
            "table", Map.of(LANCE_LOCATION, "/tmp/table", LANCE_TABLE_DECLARED, "true"), Map.of());

    Assertions.assertTrue(isOnlyDeclared);
    Assertions.assertTrue(operations.openDatasetCalled);
  }

  @Test
  public void testDeclaredTableReportedMaterializedOnTransientError() {
    TestableGravitinoLanceTableOperations operations =
        new TestableGravitinoLanceTableOperations(
            new RuntimeException("LanceError(IO): Generic S3 error: throttled (503 SlowDown)"));

    boolean isOnlyDeclared =
        operations.isOnlyDeclared(
            "table", Map.of(LANCE_LOCATION, "/tmp/table", LANCE_TABLE_DECLARED, "true"), Map.of());

    // A transient failure must not be reported as declared-only, to avoid a destructive overwrite.
    Assertions.assertFalse(isOnlyDeclared);
    Assertions.assertTrue(operations.openDatasetCalled);
  }

  @Test
  public void testDeclaredTableReportedMaterializedWhenOpenThrowsCheckedIoException() {
    // Object stores surface failures as a (native, undeclared) checked IOException; it must be
    // caught and classified, not propagated out of describeTable.
    TestableGravitinoLanceTableOperations operations =
        new TestableGravitinoLanceTableOperations(
            new IOException("LanceError(IO): Generic S3 error: connection timed out"));

    boolean isOnlyDeclared =
        operations.isOnlyDeclared(
            "table", Map.of(LANCE_LOCATION, "/tmp/table", LANCE_TABLE_DECLARED, "true"), Map.of());

    Assertions.assertFalse(isOnlyDeclared);
    Assertions.assertTrue(operations.openDatasetCalled);
  }

  @Test
  public void testStorageLevelNotFoundIsTreatedAsMaterialized() {
    // A storage-level "bucket not found" is ambiguous (genuine absence vs. misconfiguration), so
    // it is conservatively treated as materialized; only Lance's dataset-level "was not found"
    // signal flips a table to declared-only.
    TestableGravitinoLanceTableOperations operations =
        new TestableGravitinoLanceTableOperations(
            new IOException("LanceError(IO): Generic S3 error: Bucket 'b' not found"));

    boolean isOnlyDeclared =
        operations.isOnlyDeclared(
            "table",
            Map.of(LANCE_LOCATION, "s3://b/t.lance", LANCE_TABLE_DECLARED, "true"),
            Map.of());

    Assertions.assertFalse(isOnlyDeclared);
    Assertions.assertTrue(operations.openDatasetCalled);
  }

  @Test
  public void testDeclaredTableIsNotDeclaredOnlyWhenDatasetCanBeOpened() {
    TestableGravitinoLanceTableOperations operations =
        new TestableGravitinoLanceTableOperations(null);
    Map<String, String> storageOptions = Map.of("region", "us-west-2");

    boolean isOnlyDeclared =
        operations.isOnlyDeclared(
            "table",
            Map.of(LANCE_LOCATION, "/tmp/table", LANCE_TABLE_DECLARED, "true"),
            storageOptions);

    Assertions.assertFalse(isOnlyDeclared);
    Assertions.assertEquals("/tmp/table", operations.openedLocation);
    Assertions.assertEquals(storageOptions, operations.openedStorageOptions);
  }

  @Test
  public void testNonDeclaredTableDoesNotProbeDataset() {
    TestableGravitinoLanceTableOperations operations =
        new TestableGravitinoLanceTableOperations(null);

    boolean isOnlyDeclared =
        operations.isOnlyDeclared("table", Map.of(LANCE_LOCATION, "/tmp/table"), Map.of());

    Assertions.assertFalse(isOnlyDeclared);
    Assertions.assertFalse(operations.openDatasetCalled);
  }

  @Test
  public void testDeclaredTableWithoutLocationIsDeclaredOnlyWithoutProbing() {
    TestableGravitinoLanceTableOperations operations =
        new TestableGravitinoLanceTableOperations(null);

    boolean isOnlyDeclared =
        operations.isOnlyDeclared("table", Map.of(LANCE_TABLE_DECLARED, "true"), Map.of());

    Assertions.assertTrue(isOnlyDeclared);
    Assertions.assertFalse(operations.openDatasetCalled);
  }

  @Test
  public void testDeclaredTableWithEmptyLocationIsDeclaredOnlyWithoutProbing() {
    TestableGravitinoLanceTableOperations operations =
        new TestableGravitinoLanceTableOperations(null);

    boolean isOnlyDeclared =
        operations.isOnlyDeclared(
            "table", Map.of(LANCE_TABLE_DECLARED, "true", LANCE_LOCATION, ""), Map.of());

    Assertions.assertTrue(isOnlyDeclared);
    Assertions.assertFalse(operations.openDatasetCalled);
  }

  private static class TestableGravitinoLanceTableOperations extends GravitinoLanceTableOperations {
    private final Exception openDatasetException;
    private boolean openDatasetCalled;
    private String openedLocation;
    private Map<String, String> openedStorageOptions;

    TestableGravitinoLanceTableOperations(Exception openDatasetException) {
      super(null);
      this.openDatasetException = openDatasetException;
    }

    @Override
    Dataset openDataset(
        BufferAllocator allocator, String location, Map<String, String> storageOptions) {
      openDatasetCalled = true;
      openedLocation = location;
      openedStorageOptions = storageOptions;
      if (openDatasetException != null) {
        // Reproduce lance, whose native open can throw an undeclared checked exception.
        sneakyThrow(openDatasetException);
      }
      return null;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> void sneakyThrow(Throwable t) throws T {
      throw (T) t;
    }
  }
}
