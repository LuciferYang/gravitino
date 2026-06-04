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

import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.lance.Dataset;

public class TestGravitinoLanceTableOperations {

  @Test
  public void testDeclaredTableStaysDeclaredOnlyWhenDatasetCannotBeOpened() {
    TestableGravitinoLanceTableOperations operations =
        new TestableGravitinoLanceTableOperations(new RuntimeException("dataset not found"));

    boolean isOnlyDeclared =
        operations.isOnlyDeclared(
            "table", Map.of(LANCE_LOCATION, "/tmp/table", LANCE_TABLE_DECLARED, "true"), Map.of());

    Assertions.assertTrue(isOnlyDeclared);
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

  private static class TestableGravitinoLanceTableOperations extends GravitinoLanceTableOperations {
    private final RuntimeException openDatasetException;
    private boolean openDatasetCalled;
    private String openedLocation;
    private Map<String, String> openedStorageOptions;

    TestableGravitinoLanceTableOperations(RuntimeException openDatasetException) {
      super(null);
      this.openDatasetException = openDatasetException;
    }

    @Override
    Dataset openDataset(String location, Map<String, String> storageOptions) {
      openDatasetCalled = true;
      openedLocation = location;
      openedStorageOptions = storageOptions;
      if (openDatasetException != null) {
        throw openDatasetException;
      }
      return null;
    }
  }
}
