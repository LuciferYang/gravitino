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
package org.apache.gravitino.authorization.chain.integration.test;

import static org.apache.gravitino.authorization.ranger.integration.test.RangerITEnv.currentFunName;
import static org.apache.gravitino.catalog.hive.HiveConstants.IMPERSONATION_ENABLE;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Configs;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.auth.AuthenticatorType;
import org.apache.gravitino.authorization.Privileges;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.authorization.SecurableObjects;
import org.apache.gravitino.authorization.common.ChainedAuthorizationProperties;
import org.apache.gravitino.authorization.ranger.integration.test.RangerBaseE2EIT;
import org.apache.gravitino.authorization.ranger.integration.test.RangerITEnv;
import org.apache.gravitino.catalog.hive.HiveConstants;
import org.apache.gravitino.exceptions.UserAlreadyExistsException;
import org.apache.gravitino.integration.test.container.HiveContainer;
import org.apache.gravitino.integration.test.container.RangerContainer;
import org.apache.gravitino.integration.test.util.BaseIT;
import org.apache.gravitino.integration.test.util.GravitinoITUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.kyuubi.plugin.spark.authz.AccessControlException;
import org.apache.spark.sql.SparkSession;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestChainedAuthorizationIT extends RangerBaseE2EIT {
  private static final Logger LOG = LoggerFactory.getLogger(TestChainedAuthorizationIT.class);
  private static String DEFAULT_FS;
  private FileSystem fileSystem;

  @BeforeAll
  public void startIntegrationTest() throws Exception {
    metalakeName = GravitinoITUtils.genRandomName("metalake").toLowerCase();
    // Enable Gravitino Authorization mode
    Map<String, String> configs = Maps.newHashMap();
    configs.put(Configs.ENABLE_AUTHORIZATION.getKey(), String.valueOf(true));
    configs.put(Configs.SERVICE_ADMINS.getKey(), RangerITEnv.HADOOP_USER_NAME);
    configs.put(Configs.AUTHENTICATORS.getKey(), AuthenticatorType.SIMPLE.name().toLowerCase());
    configs.put("SimpleAuthUserName", AuthConstants.ANONYMOUS_USER);
    registerCustomConfigs(configs);

    super.startIntegrationTest();
    RangerITEnv.init(RangerBaseE2EIT.metalakeName, false);
    RangerITEnv.startHiveRangerContainer();

    HIVE_METASTORE_URIS =
        String.format(
            "thrift://%s:%d",
            containerSuite.getHiveRangerContainer().getContainerIpAddress(),
            HiveContainer.HIVE_METASTORE_PORT);

    generateRangerSparkSecurityXML("authorization-chain");

    DEFAULT_FS =
        String.format(
            "hdfs://%s:%d/user/hive/warehouse",
            containerSuite.getHiveRangerContainer().getContainerIpAddress(),
            HiveContainer.HDFS_DEFAULTFS_PORT);
    BaseIT.runInEnv(
        "HADOOP_USER_NAME",
        AuthConstants.ANONYMOUS_USER,
        () -> {
          sparkSession =
              SparkSession.builder()
                  .master("local[1]")
                  .appName("Ranger Hive E2E integration test")
                  .config("hive.metastore.uris", HIVE_METASTORE_URIS)
                  .config("spark.sql.warehouse.dir", DEFAULT_FS)
                  .config("spark.sql.storeAssignmentPolicy", "LEGACY")
                  .config("mapreduce.input.fileinputformat.input.dir.recursive", "true")
                  .config(
                      "spark.sql.extensions",
                      "org.apache.kyuubi.plugin.spark.authz.ranger.RangerSparkExtension")
                  .enableHiveSupport()
                  .getOrCreate();
          sparkSession.sql(SQL_SHOW_DATABASES); // must be called to activate the Spark session
        });
    createMetalake();
    createCatalog();

    Configuration conf = new Configuration();
    conf.set("fs.defaultFS", DEFAULT_FS);
    fileSystem = FileSystem.get(conf);

    RangerITEnv.cleanup();
    try {
      metalake.addUser(System.getenv(HADOOP_USER_NAME));
    } catch (UserAlreadyExistsException e) {
      LOG.error("Failed to add user: {}", System.getenv(HADOOP_USER_NAME), e);
    }
  }

  @AfterAll
  public void stop() throws IOException {
    if (client != null) {
      Arrays.stream(catalog.asSchemas().listSchemas())
          .filter(schema -> !schema.equals("default"))
          .forEach(
              (schema -> {
                catalog.asSchemas().dropSchema(schema, false);
              }));
      Arrays.stream(metalake.listCatalogs())
          .forEach((catalogName -> metalake.dropCatalog(catalogName, true)));
      client.disableMetalake(metalakeName);
      client.dropMetalake(metalakeName);
    }
    if (fileSystem != null) {
      fileSystem.close();
    }
    try {
      closer.close();
    } catch (Exception e) {
      LOG.error("Failed to close CloseableGroup", e);
    }
    client = null;
    RangerITEnv.cleanup();
  }

  private String storageLocation(String dirName) {
    return DEFAULT_FS + "/" + dirName;
  }

  @Test
  public void testCreateSchemaInCatalog() throws IOException {
    // Choose a catalog
    useCatalog();

    // First, fail to create the schema
    Exception accessControlException =
        Assertions.assertThrows(Exception.class, () -> sparkSession.sql(SQL_CREATE_SCHEMA));
    Assertions.assertTrue(
        accessControlException
                .getMessage()
                .contains(
                    String.format(
                        "Permission denied: user [%s] does not have [create] privilege",
                        AuthConstants.ANONYMOUS_USER))
            || accessControlException
                .getMessage()
                .contains(
                    String.format(
                        "Permission denied: user=%s, access=WRITE", AuthConstants.ANONYMOUS_USER)));
    Path schemaPath = new Path(storageLocation(schemaName + ".db"));
    Assertions.assertFalse(fileSystem.exists(schemaPath));
    FileStatus fileStatus = fileSystem.getFileStatus(new Path(DEFAULT_FS));
    Assertions.assertEquals(System.getenv(HADOOP_USER_NAME), fileStatus.getOwner());

    // Second, grant the `CREATE_SCHEMA` role
    String roleName = currentFunName();
    SecurableObject securableObject =
        SecurableObjects.ofCatalog(
            catalogName, Lists.newArrayList(Privileges.CreateSchema.allow()));
    metalake.createRole(roleName, Collections.emptyMap(), Lists.newArrayList(securableObject));
    metalake.grantRolesToUser(Lists.newArrayList(roleName), AuthConstants.ANONYMOUS_USER);
    waitForUpdatingPolicies();

    // Third, succeed to create the schema
    sparkSession.sql(SQL_CREATE_SCHEMA);
    Assertions.assertTrue(fileSystem.exists(schemaPath));
    FileStatus fsSchema = fileSystem.getFileStatus(schemaPath);
    Assertions.assertEquals(AuthConstants.ANONYMOUS_USER, fsSchema.getOwner());

    // Fourth, fail to create the table
    Assertions.assertThrows(AccessControlException.class, () -> sparkSession.sql(SQL_CREATE_TABLE));

    // Clean up
    catalog.asSchemas().dropSchema(schemaName, false);
    metalake.deleteRole(roleName);
    waitForUpdatingPolicies();

    Exception accessControlException2 =
        Assertions.assertThrows(Exception.class, () -> sparkSession.sql(SQL_CREATE_SCHEMA));
    Assertions.assertTrue(
        accessControlException2
                .getMessage()
                .contains(
                    String.format(
                        "Permission denied: user [%s] does not have [create] privilege",
                        AuthConstants.ANONYMOUS_USER))
            || accessControlException2
                .getMessage()
                .contains(
                    String.format(
                        "Permission denied: user=%s, access=WRITE", AuthConstants.ANONYMOUS_USER)));
  }

  @Override
  public void createCatalog() {
    Map<String, String> catalogConf = new HashMap<>();
    catalogConf.put(HiveConstants.METASTORE_URIS, HIVE_METASTORE_URIS);
    catalogConf.put(IMPERSONATION_ENABLE, "true");
    catalogConf.put(Catalog.AUTHORIZATION_PROVIDER, "chain");
    catalogConf.put(ChainedAuthorizationProperties.CHAIN_PLUGINS_PROPERTIES_KEY, "hive1,hdfs1");
    catalogConf.put("authorization.chain.hive1.provider", "ranger");
    catalogConf.put("authorization.chain.hive1.ranger.auth.type", RangerContainer.authType);
    catalogConf.put("authorization.chain.hive1.ranger.admin.url", RangerITEnv.RANGER_ADMIN_URL);
    catalogConf.put("authorization.chain.hive1.ranger.username", RangerContainer.rangerUserName);
    catalogConf.put("authorization.chain.hive1.ranger.password", RangerContainer.rangerPassword);
    catalogConf.put("authorization.chain.hive1.ranger.service.type", "HadoopSQL");
    catalogConf.put(
        "authorization.chain.hive1.ranger.service.name", RangerITEnv.RANGER_HIVE_REPO_NAME);
    catalogConf.put("authorization.chain.hdfs1.provider", "ranger");
    catalogConf.put("authorization.chain.hdfs1.ranger.auth.type", RangerContainer.authType);
    catalogConf.put("authorization.chain.hdfs1.ranger.admin.url", RangerITEnv.RANGER_ADMIN_URL);
    catalogConf.put("authorization.chain.hdfs1.ranger.username", RangerContainer.rangerUserName);
    catalogConf.put("authorization.chain.hdfs1.ranger.password", RangerContainer.rangerPassword);
    catalogConf.put("authorization.chain.hdfs1.ranger.service.type", "HDFS");
    catalogConf.put(
        "authorization.chain.hdfs1.ranger.service.name", RangerITEnv.RANGER_HDFS_REPO_NAME);

    metalake.createCatalog(catalogName, Catalog.Type.RELATIONAL, "hive", "comment", catalogConf);
    catalog = metalake.loadCatalog(catalogName);
    LOG.info("Catalog created: {}", catalog);
  }

  @Test
  public void testCreateSchema() throws InterruptedException {
    // TODO
  }

  @Test
  void testCreateTable() throws InterruptedException {
    // TODO
  }

  @Test
  void testReadWriteTableWithMetalakeLevelRole() throws InterruptedException {
    // TODO
  }

  @Test
  void testReadWriteTableWithTableLevelRole() throws InterruptedException {
    // TODO
  }

  @Test
  void testReadOnlyTable() throws InterruptedException {
    // TODO
  }

  @Test
  void testWriteOnlyTable() throws InterruptedException {
    // TODO
  }

  @Test
  void testCreateAllPrivilegesRole() throws InterruptedException {
    // TODO
  }

  @Test
  void testDeleteAndRecreateRole() throws InterruptedException {
    // TODO
  }

  @Test
  void testDeleteAndRecreateMetadataObject() throws InterruptedException {
    // TODO
  }

  @Test
  void testRenameMetadataObject() throws InterruptedException {
    // TODO
  }

  @Test
  void testRenameMetadataObjectPrivilege() throws InterruptedException {
    // TODO
  }

  @Test
  void testChangeOwner() throws InterruptedException {
    // TODO
  }

  @Test
  void testAllowUseSchemaPrivilege() throws InterruptedException {
    // TODO
  }

  @Test
  void testDenyPrivileges() throws InterruptedException {
    // TODO
  }

  @Test
  void testGrantPrivilegesForMetalake() throws InterruptedException {
    // TODO
  }

  @Override
  protected void checkTableAllPrivilegesExceptForCreating() {
    // TODO
  }

  @Override
  protected void checkUpdateSQLWithReadWritePrivileges() {
    // TODO
  }

  @Override
  protected void checkUpdateSQLWithReadPrivileges() {
    // TODO
  }

  @Override
  protected void checkUpdateSQLWithWritePrivileges() {
    // TODO
  }

  @Override
  protected void checkDeleteSQLWithReadWritePrivileges() {
    // TODO
  }

  @Override
  protected void checkDeleteSQLWithReadPrivileges() {
    // TODO
  }

  @Override
  protected void checkDeleteSQLWithWritePrivileges() {
    // TODO
  }

  @Override
  protected void useCatalog() {
    // TODO
  }

  @Override
  protected void checkWithoutPrivileges() {
    // TODO
  }

  @Override
  protected void testAlterTable() {
    // TODO
  }
}
