/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.gravitino.lance.common.ops.gravitino;

import static org.apache.gravitino.lance.common.ops.gravitino.LanceDataTypeConverter.CONVERTER;
import static org.apache.gravitino.lance.common.utils.LanceConstants.LANCE_CREATION_MODE;
import static org.apache.gravitino.lance.common.utils.LanceConstants.LANCE_LOCATION;
import static org.apache.gravitino.lance.common.utils.LanceConstants.LANCE_TABLE_DECLARED;
import static org.apache.gravitino.lance.common.utils.LanceConstants.LANCE_TABLE_FORMAT;
import static org.apache.gravitino.lance.common.utils.LanceConstants.LANCE_TABLE_VERSION;
import static org.apache.gravitino.rel.Column.DEFAULT_VALUE_NOT_SET;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.lance.common.ops.LanceTableOperations;
import org.apache.gravitino.lance.common.ops.gravitino.GravitinoLanceTableAlterHandler.AlterColumnsGravitinoLance;
import org.apache.gravitino.lance.common.ops.gravitino.GravitinoLanceTableAlterHandler.DropColumns;
import org.apache.gravitino.lance.common.utils.ArrowUtils;
import org.apache.gravitino.lance.common.utils.LancePropertiesUtils;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.TableChange;
import org.lance.Dataset;
import org.lance.ReadOptions;
import org.lance.namespace.errors.TableNotFoundException;
import org.lance.namespace.model.AlterTableAlterColumnsRequest;
import org.lance.namespace.model.AlterTableDropColumnsRequest;
import org.lance.namespace.model.CreateTableResponse;
import org.lance.namespace.model.DeclareTableResponse;
import org.lance.namespace.model.DeregisterTableResponse;
import org.lance.namespace.model.DescribeTableResponse;
import org.lance.namespace.model.DropTableResponse;
import org.lance.namespace.model.JsonArrowSchema;
import org.lance.namespace.model.RegisterTableResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GravitinoLanceTableOperations implements LanceTableOperations {

  public static final Logger LOG = LoggerFactory.getLogger(GravitinoLanceTableOperations.class);

  private static final Map<Class<?>, GravitinoLanceTableAlterHandler<?, ?>> ALTER_HANDLERS =
      Map.of(
          AlterTableDropColumnsRequest.class, new DropColumns(),
          AlterTableAlterColumnsRequest.class, new AlterColumnsGravitinoLance());

  private final GravitinoLanceNamespaceWrapper namespaceWrapper;

  private enum CreateMode {
    CREATE,
    EXIST_OK,
    OVERWRITE
  }

  private enum RegisterMode {
    CREATE,
    REGISTER,
    OVERWRITE;

    String toCreationMode() {
      if (this == REGISTER) {
        return CREATE.name();
      }
      return name();
    }
  }

  public GravitinoLanceTableOperations(GravitinoLanceNamespaceWrapper namespaceWrapper) {
    this.namespaceWrapper = namespaceWrapper;
  }

  @Override
  public DescribeTableResponse describeTable(
      String tableId, String delimiter, Optional<Long> version, boolean checkDeclared) {
    if (!version.isEmpty()) {
      throw new UnsupportedOperationException(
          "Describing specific table version is not supported. It should be null to indicate the"
              + " latest version.");
    }

    ObjectIdentifier nsId = ObjectIdentifier.of(tableId, Pattern.quote(delimiter));
    Preconditions.checkArgument(
        nsId.levels() == 3, "Expected at 3-level namespace but got: %s", nsId.levels());

    String catalogName = nsId.levelAtListPos(0);
    Catalog catalog = namespaceWrapper.loadAndValidateLakehouseCatalog(catalogName);
    NameIdentifier tableIdentifier =
        NameIdentifier.of(nsId.levelAtListPos(1), nsId.levelAtListPos(2));

    Table table;
    try {
      table = catalog.asTableCatalog().loadTable(tableIdentifier);
    } catch (NoSuchTableException e) {
      throw new TableNotFoundException(
          "Table not found: " + tableId, CommonUtil.formatCurrentStackTrace(), tableId);
    }
    Map<String, String> tableProperties = table.properties();
    Map<String, String> storageOptions =
        LancePropertiesUtils.resolveLanceStorageOptions(catalog.properties(), tableProperties);

    DescribeTableResponse response = new DescribeTableResponse();
    response.setMetadata(tableProperties);
    response.setProperties(tableProperties);
    response.setLocation(tableProperties.get(LANCE_LOCATION));
    response.setSchema(toJsonArrowSchema(table.columns()));
    response.setVersion(
        Optional.ofNullable(tableProperties.get(LANCE_TABLE_VERSION))
            .map(Long::valueOf)
            .orElse(null));
    response.setStorageOptions(storageOptions);
    response.setManagedVersioning(false);
    if (checkDeclared) {
      response.setIsOnlyDeclared(isOnlyDeclared(table.name(), tableProperties, storageOptions));
    }
    return response;
  }

  @Override
  public CreateTableResponse createTable(
      String tableId,
      String mode,
      String delimiter,
      String tableLocation,
      Map<String, String> tableProperties,
      byte[] arrowStreamBody) {
    ObjectIdentifier nsId = ObjectIdentifier.of(tableId, Pattern.quote(delimiter));
    Preconditions.checkArgument(
        nsId.levels() == 3, "Expected at 3-level namespace but got: %s", nsId.levels());

    // Parser column information.
    List<Column> columns = Lists.newArrayList();
    if (arrowStreamBody != null) {
      org.apache.arrow.vector.types.pojo.Schema schema =
          ArrowUtils.parseArrowIpcStream(arrowStreamBody);
      columns = extractColumns(schema);
    }

    String catalogName = nsId.levelAtListPos(0);
    Catalog catalog = namespaceWrapper.loadAndValidateLakehouseCatalog(catalogName);

    NameIdentifier tableIdentifier =
        NameIdentifier.of(nsId.levelAtListPos(1), nsId.levelAtListPos(2));

    Map<String, String> createTableProperties = Maps.newHashMap(tableProperties);
    if (tableLocation != null) {
      createTableProperties.put(LANCE_LOCATION, tableLocation);
    }
    // The format is defined in GenericLakehouseCatalog
    createTableProperties.put(Table.PROPERTY_TABLE_FORMAT, LANCE_TABLE_FORMAT);
    createTableProperties.put(Table.PROPERTY_EXTERNAL, "true");

    // Pass creation mode as property to delegate handling to LanceTableOperations
    createTableProperties.put(LANCE_CREATION_MODE, normalizeCreateMode(mode, tableId));

    // Single call - mode is handled server-side
    Table t =
        catalog
            .asTableCatalog()
            .createTable(
                tableIdentifier, columns.toArray(new Column[0]), null, createTableProperties);
    Map<String, String> properties = t.properties();
    Map<String, String> effectiveStorageOptions =
        LancePropertiesUtils.resolveLanceStorageOptions(catalog.properties(), properties);

    CreateTableResponse response = new CreateTableResponse();
    response.setStorageOptions(effectiveStorageOptions);
    response.setVersion(
        Optional.ofNullable(properties.get(LANCE_TABLE_VERSION)).map(Long::valueOf).orElse(null));
    response.setLocation(properties.get(LANCE_LOCATION));
    response.setProperties(properties);
    return response;
  }

  @Override
  public DeclareTableResponse declareTable(
      String tableId, String delimiter, String tableLocation, Map<String, String> tableProperties) {
    ImmutableMap<String, String> props =
        ImmutableMap.<String, String>builder()
            .putAll(tableProperties)
            .put(LANCE_TABLE_DECLARED, "true")
            .put(Table.PROPERTY_EXTERNAL, "true")
            .build();

    CreateTableResponse response =
        createTable(tableId, "create", delimiter, tableLocation, props, null);
    DeclareTableResponse declareTableResponse = new DeclareTableResponse();
    declareTableResponse.setLocation(response.getLocation());
    declareTableResponse.setStorageOptions(response.getStorageOptions());
    declareTableResponse.setProperties(response.getProperties());
    declareTableResponse.setManagedVersioning(false);
    return declareTableResponse;
  }

  @Override
  public RegisterTableResponse registerTable(
      String tableId, String mode, String delimiter, Map<String, String> tableProperties) {
    ObjectIdentifier nsId = ObjectIdentifier.of(tableId, Pattern.quote(delimiter));
    Preconditions.checkArgument(
        nsId.levels() == 3, "Expected at 3-level namespace but got: %s", nsId.levels());

    String catalogName = nsId.levelAtListPos(0);
    Catalog catalog = namespaceWrapper.loadAndValidateLakehouseCatalog(catalogName);
    NameIdentifier tableIdentifier =
        NameIdentifier.of(nsId.levelAtListPos(1), nsId.levelAtListPos(2));

    Map<String, String> copiedTableProperties = Maps.newHashMap(tableProperties);
    copiedTableProperties.put(Table.PROPERTY_TABLE_FORMAT, LANCE_TABLE_FORMAT);
    copiedTableProperties.put(Table.PROPERTY_EXTERNAL, "true");

    // Pass creation mode as property to delegate handling to LanceTableOperations
    copiedTableProperties.put(LANCE_CREATION_MODE, normalizeRegisterMode(mode, tableId));

    // Single call - mode is handled server-side
    Table t =
        catalog
            .asTableCatalog()
            .createTable(tableIdentifier, new Column[] {}, null, copiedTableProperties);

    RegisterTableResponse response = new RegisterTableResponse();
    response.setProperties(t.properties());
    response.setLocation(t.properties().get(LANCE_LOCATION));
    return response;
  }

  @Override
  public DeregisterTableResponse deregisterTable(String tableId, String delimiter) {
    ObjectIdentifier nsId = ObjectIdentifier.of(tableId, Pattern.quote(delimiter));
    Preconditions.checkArgument(
        nsId.levels() == 3, "Expected at 3-level namespace but got: %s", nsId.levels());

    String catalogName = nsId.levelAtListPos(0);
    Catalog catalog = namespaceWrapper.loadAndValidateLakehouseCatalog(catalogName);

    NameIdentifier tableIdentifier =
        NameIdentifier.of(nsId.levelAtListPos(1), nsId.levelAtListPos(2));
    Table t;
    try {
      t = catalog.asTableCatalog().loadTable(tableIdentifier);
    } catch (NoSuchTableException e) {
      throw new TableNotFoundException(
          "Table not found: " + tableId, CommonUtil.formatCurrentStackTrace(), tableId);
    }
    Map<String, String> properties = t.properties();
    // TODO Support real deregister API.
    boolean result = catalog.asTableCatalog().dropTable(tableIdentifier);
    if (!result) {
      throw new TableNotFoundException(
          "Table not found: " + tableId, CommonUtil.formatCurrentStackTrace(), tableId);
    }

    DeregisterTableResponse response = new DeregisterTableResponse();
    response.setProperties(properties);
    response.setLocation(properties.get(LANCE_LOCATION));
    response.setId(nsId.listStyleId());
    return response;
  }

  @Override
  public boolean tableExists(String tableId, String delimiter) {
    ObjectIdentifier nsId = ObjectIdentifier.of(tableId, Pattern.quote(delimiter));
    Preconditions.checkArgument(
        nsId.levels() == 3, "Expected at 3-level namespace but got: %s", nsId.levels());

    String catalogName = nsId.levelAtListPos(0);
    Catalog catalog = namespaceWrapper.loadAndValidateLakehouseCatalog(catalogName);

    NameIdentifier tableIdentifier =
        NameIdentifier.of(nsId.levelAtListPos(1), nsId.levelAtListPos(2));

    return catalog.asTableCatalog().tableExists(tableIdentifier);
  }

  @Override
  public DropTableResponse dropTable(String tableId, String delimiter) {
    ObjectIdentifier nsId = ObjectIdentifier.of(tableId, Pattern.quote(delimiter));
    Preconditions.checkArgument(
        nsId.levels() == 3, "Expected at 3-level namespace but got: %s", nsId.levels());

    String catalogName = nsId.levelAtListPos(0);
    Catalog catalog = namespaceWrapper.loadAndValidateLakehouseCatalog(catalogName);

    NameIdentifier tableIdentifier =
        NameIdentifier.of(nsId.levelAtListPos(1), nsId.levelAtListPos(2));

    Table table;
    try {
      table = catalog.asTableCatalog().loadTable(tableIdentifier);
    } catch (NoSuchTableException e) {
      throw new TableNotFoundException(
          "Table not found: " + tableId, CommonUtil.formatCurrentStackTrace(), tableId);
    }

    boolean deleted = catalog.asTableCatalog().purgeTable(tableIdentifier);
    if (!deleted) {
      throw new TableNotFoundException(
          "Table not found: " + tableId, CommonUtil.formatCurrentStackTrace(), tableId);
    }

    DropTableResponse response = new DropTableResponse();
    response.setId(nsId.listStyleId());
    response.setLocation(table.properties().get(LANCE_LOCATION));
    response.setProperties(table.properties());

    return response;
  }

  @Override
  public Object alterTable(String tableId, String delimiter, Object request) {
    ObjectIdentifier nsId = ObjectIdentifier.of(tableId, Pattern.quote(delimiter));
    Preconditions.checkArgument(
        nsId.levels() == 3, "Expected at 3-level namespace but got: %s", nsId.levels());

    String catalogName = nsId.levelAtListPos(0);
    Catalog catalog = namespaceWrapper.loadAndValidateLakehouseCatalog(catalogName);
    NameIdentifier tableIdentifier =
        NameIdentifier.of(nsId.levelAtListPos(1), nsId.levelAtListPos(2));

    GravitinoLanceTableAlterHandler<Object, Object> handler = getHandler(request.getClass());
    if (handler == null) {
      throw new IllegalArgumentException(
          "Unsupported alter table request type: " + request.getClass().getName());
    }
    TableChange[] changes = handler.buildGravitinoTableChange(request);

    Table table = catalog.asTableCatalog().alterTable(tableIdentifier, changes);

    return handler.handle(table, request);
  }

  @SuppressWarnings("unchecked")
  private static <REQUEST, RESPONSE> GravitinoLanceTableAlterHandler<REQUEST, RESPONSE> getHandler(
      Class<?> requestClass) {
    return (GravitinoLanceTableAlterHandler<REQUEST, RESPONSE>) ALTER_HANDLERS.get(requestClass);
  }

  private List<Column> extractColumns(org.apache.arrow.vector.types.pojo.Schema arrowSchema) {
    List<Column> columns = new ArrayList<>();

    for (org.apache.arrow.vector.types.pojo.Field field : arrowSchema.getFields()) {
      columns.add(
          Column.of(
              field.getName(),
              CONVERTER.toGravitino(field),
              null,
              field.isNullable(),
              false,
              DEFAULT_VALUE_NOT_SET));
    }
    return columns;
  }

  private JsonArrowSchema toJsonArrowSchema(Column[] columns) {
    List<Field> fields =
        Arrays.stream(columns)
            .map(col -> CONVERTER.toArrowField(col.name(), col.dataType(), col.nullable()))
            .collect(Collectors.toList());

    return JsonArrowSchemaConverter.convertToJsonArrowSchema(
        new org.apache.arrow.vector.types.pojo.Schema(fields));
  }

  boolean isOnlyDeclared(
      String tableName, Map<String, String> tableProperties, Map<String, String> storageOptions) {
    if (!Boolean.parseBoolean(tableProperties.getOrDefault(LANCE_TABLE_DECLARED, "false"))) {
      return false;
    }

    String location = tableProperties.get(LANCE_LOCATION);
    if (location == null || location.isEmpty()) {
      return true;
    }

    // Own the allocator here so it is always closed, whether opening the dataset succeeds or
    // throws. Lance does not close a caller-supplied allocator on Dataset.close().
    //
    // The probe is advisory, so it must never break describeTable: catch every failure. Lance
    // exposes no typed "dataset absent" error, and opening an object store can even surface an
    // (undeclared) checked IOException, so we classify by message. Only a clear "dataset absent"
    // failure keeps the table declared-only; any other failure (transient I/O, throttling,
    // credentials) is reported as materialized, because wrongly reporting a materialized table as
    // declared-only could let a client overwrite it.
    try (BufferAllocator allocator = new RootAllocator();
        Dataset ignored = openDataset(allocator, location, storageOptions)) {
      return false;
    } catch (Exception e) {
      if (indicatesDatasetAbsent(e)) {
        LOG.debug(
            "Treat Lance table {} as declared-only because its dataset is absent at location {}",
            tableName,
            location,
            e);
        return true;
      }
      LOG.warn(
          "Could not determine whether Lance table {} is materialized at location {}; reporting it "
              + "as materialized to avoid a destructive declared-only result",
          tableName,
          location,
          e);
      return false;
    }
  }

  Dataset openDataset(
      BufferAllocator allocator, String location, Map<String, String> storageOptions) {
    return Dataset.open()
        .allocator(allocator)
        .uri(location)
        .readOptions(new ReadOptions.Builder().setStorageOptions(storageOptions).build())
        .build();
  }

  /**
   * Returns whether the failure clearly indicates the Lance dataset itself does not exist (so the
   * table is still only declared). Lance exposes no typed exception for this, so it is detected
   * from its dataset-level message ("Dataset at path ... was not found"), which is emitted the same
   * way for local and object-store locations once the storage is reachable. Any other failure --
   * transient I/O, throttling, credentials, or even a storage-level "bucket not found" -- is
   * deliberately treated as "not absent" so a materialized table is never reported as declared-only
   * (which could let a client overwrite it). Recognizing those broader cases would require looser
   * matching that risks misclassifying transient errors, so this stays conservative.
   */
  private static boolean indicatesDatasetAbsent(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      String message = cause.getMessage();
      if (message != null && message.toLowerCase(Locale.ROOT).contains("was not found")) {
        return true;
      }
    }
    return false;
  }

  private static String normalizeCreateMode(String mode, String tableId) {
    if (mode == null) {
      return CreateMode.CREATE.name();
    }
    return CommonUtil.parseEnumToken(CreateMode.class, mode, "Unknown create table mode: ", tableId)
        .name();
  }

  private static String normalizeRegisterMode(String mode, String tableId) {
    if (mode == null) {
      return RegisterMode.CREATE.toCreationMode();
    }
    return CommonUtil.parseEnumToken(
            RegisterMode.class, mode, "Unknown register table mode: ", tableId)
        .toCreationMode();
  }
}
