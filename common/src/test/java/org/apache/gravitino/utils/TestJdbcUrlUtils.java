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

package org.apache.gravitino.utils;

import java.util.Collections;
import org.apache.gravitino.exceptions.GravitinoRuntimeException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestJdbcUrlUtils {

  @Test
  public void whenMalformedUrlGiven_ShouldThrowGravitinoRuntimeException() {
    GravitinoRuntimeException gre =
        Assertions.assertThrows(
            GravitinoRuntimeException.class,
            () ->
                JdbcUrlUtils.validateJdbcConfig(
                    "testDriver", "malformed%ZZurl", Collections.singletonMap("test", "test")));
    Assertions.assertEquals("Unable to decode JDBC URL", gre.getMessage());
  }

  @Test
  public void testValidateJdbcConfigWhenDriverClassNameIsNull() {
    Assertions.assertThrowsExactly(
        IllegalArgumentException.class,
        () ->
            JdbcUrlUtils.validateJdbcConfig(
                null,
                "jdbc:mysql://localhost:0000/test",
                Collections.singletonMap("test", "test")));
  }

  @Test
  public void testValidateJdbcConfigForMySQL() {
    Assertions.assertDoesNotThrow(
        () ->
            JdbcUrlUtils.validateJdbcConfig(
                "testDriver",
                "jdbc:mysql://localhost:0000/test",
                Collections.singletonMap("test", "test")));
  }

  @Test
  public void whenUnsafeParameterGivenForMySQL_ShouldThrowGravitinoRuntimeException() {

    GravitinoRuntimeException gre =
        Assertions.assertThrows(
            GravitinoRuntimeException.class,
            () ->
                JdbcUrlUtils.validateJdbcConfig(
                    "testDriver",
                    "jdbc:mysql://localhost:0000/test?allowloadlocalinfile=test",
                    Collections.singletonMap("test", "test")));
    Assertions.assertEquals(
        "Unsafe MySQL parameter 'allowloadlocalinfile' detected in JDBC URL", gre.getMessage());
  }

  @Test
  public void whenConfigPropertiesMapContainsUnsafeParam_ShouldThrowGravitinoRuntimeException() {
    GravitinoRuntimeException gre =
        Assertions.assertThrows(
            GravitinoRuntimeException.class,
            () ->
                JdbcUrlUtils.validateJdbcConfig(
                    "testDriver",
                    "jdbc:mysql://localhost:0000/test",
                    Collections.singletonMap("maxAllowedPacket", "true")));
    Assertions.assertEquals(
        "Unsafe MySQL parameter 'maxAllowedPacket' detected in JDBC URL", gre.getMessage());
  }

  @Test
  public void testValidateJdbcConfigForMariaDB() {
    Assertions.assertDoesNotThrow(
        () ->
            JdbcUrlUtils.validateJdbcConfig(
                "testDriver",
                "jdbc:mariadb://localhost:0000/test",
                Collections.singletonMap("test", "test")));
  }

  @Test
  public void whenUnsafeParameterGivenForMariaDB_ShouldThrowGravitintoRuntimeException() {
    GravitinoRuntimeException gre =
        Assertions.assertThrows(
            GravitinoRuntimeException.class,
            () ->
                JdbcUrlUtils.validateJdbcConfig(
                    "testDriver",
                    "jdbc:mariaDB://localhost:0000/test?allowloadlocalinfile=test",
                    Collections.singletonMap("test", "test")));
    Assertions.assertEquals(
        "Unsafe MariaDB parameter 'allowloadlocalinfile' detected in JDBC URL", gre.getMessage());
  }

  @Test
  public void testValidateJdbcConfigForPostgreSQL() {
    Assertions.assertDoesNotThrow(
        () ->
            JdbcUrlUtils.validateJdbcConfig(
                "testDriver",
                "jdbc:postgresql://localhost:0000/test",
                Collections.singletonMap("test", "test")));
  }

  @Test
  public void whenUnsafeParameterGivenForPostgreSQL_ShouldThrowGravitinoRuntimeException() {
    GravitinoRuntimeException gre =
        Assertions.assertThrows(
            GravitinoRuntimeException.class,
            () ->
                JdbcUrlUtils.validateJdbcConfig(
                    "testDriver",
                    "jdbc:postgresql://localhost:0000/test?socketFactory=test",
                    Collections.singletonMap("test", "test")));
    Assertions.assertEquals(
        "Unsafe PostgreSQL parameter 'socketFactory' detected in JDBC URL", gre.getMessage());
  }

  @Test
  public void whenUnsafeParamSuppliedAsConfigKey_ShouldThrowGravitinoRuntimeException() {
    // The unsafe parameter is supplied only as a config-map key, with an innocuous value and a URL
    // that never mentions it. Config keys are applied to the connection as driver properties, so
    // the guard must inspect keys, not just the URL and values.
    GravitinoRuntimeException gre =
        Assertions.assertThrows(
            GravitinoRuntimeException.class,
            () ->
                JdbcUrlUtils.validateJdbcConfig(
                    "testDriver",
                    "jdbc:mysql://localhost:0000/test",
                    Collections.singletonMap("autoDeserialize", "true")));
    Assertions.assertEquals(
        "Unsafe MySQL parameter 'autoDeserialize' detected in JDBC URL", gre.getMessage());
  }

  @Test
  public void whenUnsafeParamConfigKeyHasDifferentCase_ShouldThrowGravitinoRuntimeException() {
    // Keys are matched case-insensitively, so re-casing an unsafe parameter (here SocketFactory
    // for socketFactory) cannot slip it past the guard.
    GravitinoRuntimeException gre =
        Assertions.assertThrows(
            GravitinoRuntimeException.class,
            () ->
                JdbcUrlUtils.validateJdbcConfig(
                    "testDriver",
                    "jdbc:postgresql://localhost:0000/test",
                    Collections.singletonMap("SocketFactory", "evil.Factory")));
    Assertions.assertEquals(
        "Unsafe PostgreSQL parameter 'socketFactory' detected in JDBC URL", gre.getMessage());
  }

  @Test
  public void whenUnsafeParamConfigKeyForMariaDB_ShouldThrowGravitinoRuntimeException() {
    // MariaDB routes through the same MySQL parameter list, so its key path must be guarded too.
    GravitinoRuntimeException gre =
        Assertions.assertThrows(
            GravitinoRuntimeException.class,
            () ->
                JdbcUrlUtils.validateJdbcConfig(
                    "testDriver",
                    "jdbc:mariadb://localhost:0000/test",
                    Collections.singletonMap("autoDeserialize", "true")));
    Assertions.assertEquals(
        "Unsafe MariaDB parameter 'autoDeserialize' detected in JDBC URL", gre.getMessage());
  }

  @Test
  public void whenUnsafeParamAppearsOnlyAsConfigValue_ShouldBeAccepted() {
    // The guard inspects only the URL and config keys, never values, so an unsafe parameter
    // name appearing only as a config value (not a key) is accepted.
    Assertions.assertDoesNotThrow(
        () ->
            JdbcUrlUtils.validateJdbcConfig(
                "testDriver",
                "jdbc:mysql://localhost:0000/test",
                Collections.singletonMap("benignKey", "autoDeserialize")));
  }

  @Test
  public void whenConfigMapHasNullKey_ShouldNotThrow() {
    // A null key must be skipped, not dereferenced; no unsafe parameter is present here.
    Assertions.assertDoesNotThrow(
        () ->
            JdbcUrlUtils.validateJdbcConfig(
                "testDriver",
                "jdbc:mysql://localhost:0000/test",
                Collections.singletonMap(null, "true")));
  }

  @Test
  void testValidateNullArguments() {
    Assertions.assertThrowsExactly(
        IllegalArgumentException.class,
        () ->
            JdbcUrlUtils.validateJdbcConfig(
                null,
                "jdbc:postgresql://localhost:0000/test",
                Collections.singletonMap("test", "test")));

    Assertions.assertThrowsExactly(
        IllegalArgumentException.class,
        () ->
            JdbcUrlUtils.validateJdbcConfig(
                "testDriver", null, Collections.singletonMap("test", "test")));

    Assertions.assertThrowsExactly(
        IllegalArgumentException.class, () -> JdbcUrlUtils.validateJdbcConfig(null, null, null));

    Assertions.assertThrowsExactly(
        IllegalArgumentException.class,
        () -> JdbcUrlUtils.validateJdbcConfig("", "jdbc:postgresql://localhost:0000/test", null));

    Assertions.assertThrowsExactly(
        IllegalArgumentException.class,
        () -> JdbcUrlUtils.validateJdbcConfig("testDriver", "", null));
  }
}
