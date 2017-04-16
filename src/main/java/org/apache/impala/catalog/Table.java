// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.impala.catalog;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.hadoop.hive.common.StatsSetupConst;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.metastore.TableType;
import org.apache.hadoop.hive.metastore.api.ColumnStatisticsObj;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.log4j.Logger;

import org.apache.impala.analysis.TableName;
import org.apache.impala.common.AnalysisException;
import org.apache.impala.common.ImpalaRuntimeException;
import org.apache.impala.common.Pair;
import org.apache.impala.thrift.TAccessLevel;
import org.apache.impala.thrift.TCatalogObject;
import org.apache.impala.thrift.TCatalogObjectType;
import org.apache.impala.thrift.TColumn;
import org.apache.impala.thrift.TColumnDescriptor;
import org.apache.impala.thrift.TTable;
import org.apache.impala.thrift.TTableDescriptor;
import org.apache.impala.thrift.TTableStats;
import org.apache.impala.util.HdfsCachingUtil;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * Base class for table metadata.
 *
 * This includes the concept of clustering columns, which are columns by which the table
 * data is physically clustered. In other words, if two rows share the same values
 * for the clustering columns, those two rows are most likely colocated. Note that this
 * is more general than Hive's CLUSTER BY ... INTO BUCKETS clause (which partitions
 * a key range into a fixed number of buckets).
 */
public abstract class Table implements CatalogObject {
  private static final Logger LOG = Logger.getLogger(Table.class);

  private long catalogVersion_ = Catalog.INITIAL_CATALOG_VERSION;
  protected org.apache.hadoop.hive.metastore.api.Table msTable_;

  protected final Db db_;
  protected final String name_;
  protected final String owner_;
  protected TTableDescriptor tableDesc_;
  protected TAccessLevel accessLevel_ = TAccessLevel.READ_WRITE;

  // Number of clustering columns.
  protected int numClusteringCols_;

  // estimated number of rows in table; -1: unknown.
  protected long numRows_ = -1;

  // colsByPos[i] refers to the ith column in the table. The first numClusteringCols are
  // the clustering columns.
  protected final ArrayList<Column> colsByPos_ = Lists.newArrayList();

  // map from lowercase column name to Column object.
  private final Map<String, Column> colsByName_ = Maps.newHashMap();

  // Type of this table (array of struct) that mirrors the columns. Useful for analysis.
  protected final ArrayType type_ = new ArrayType(new StructType());

  // The lastDdlTime for this table; -1 if not set
  protected long lastDdlTime_;

  // Set of supported table types.
  protected static EnumSet<TableType> SUPPORTED_TABLE_TYPES = EnumSet.of(
      TableType.EXTERNAL_TABLE, TableType.MANAGED_TABLE, TableType.VIRTUAL_VIEW);

  protected Table(org.apache.hadoop.hive.metastore.api.Table msTable, Db db,
      String name, String owner) {
    msTable_ = msTable;
    db_ = db;
    name_ = name.toLowerCase();
    owner_ = owner;
    lastDdlTime_ = (msTable_ != null) ?
        CatalogServiceCatalog.getLastDdlTime(msTable_) : -1;
  }

  public abstract TTableDescriptor toThriftDescriptor(
      int tableId, Set<Long> referencedPartitions);
  public abstract TCatalogObjectType getCatalogObjectType();

  /**
   * Populate members of 'this' from metastore info. If 'reuseMetadata' is true, reuse
   * valid existing metadata.
   */
  public abstract void load(boolean reuseMetadata, IMetaStoreClient client,
      org.apache.hadoop.hive.metastore.api.Table msTbl) throws TableLoadingException;

  public void addColumn(Column col) {
    colsByPos_.add(col);
    colsByName_.put(col.getName().toLowerCase(), col);
    ((StructType) type_.getItemType()).addField(
        new StructField(col.getName(), col.getType(), col.getComment()));
  }

  public void clearColumns() {
    colsByPos_.clear();
    colsByName_.clear();
    ((StructType) type_.getItemType()).clearFields();
  }

  /**
   * Updates the lastDdlTime for this Table, if the new value is greater
   * than the existing value. Does nothing if the new objectValue is less than
   * or equal to the existing value.
   */
  public void updateLastDdlTime(long ddlTime) {
    // Ensure the lastDdlTime never goes backwards.
    if (ddlTime > lastDdlTime_) lastDdlTime_ = ddlTime;
  }

  // Returns a list of all column names for this table which we expect to have column
  // stats in the HMS. This exists because, when we request the column stats from HMS,
  // including a column name that does not have stats causes the
  // getTableColumnStatistics() to return nothing. For Hdfs tables, partition columns do
  // not have column stats in the HMS, but HBase table clustering columns do have column
  // stats. This method allows each table type to volunteer the set of columns we should
  // ask the metastore for in loadAllColumnStats().
  protected List<String> getColumnNamesWithHmsStats() {
    List<String> ret = Lists.newArrayList();
    for (String name: colsByName_.keySet()) ret.add(name);
    return ret;
  }

  /**
   * Loads column statistics for all columns in this table from the Hive metastore. Any
   * errors are logged and ignored, since the absence of column stats is not critical to
   * the correctness of the system.
   */
  protected void loadAllColumnStats(IMetaStoreClient client) {
    if (LOG.isTraceEnabled()) LOG.trace("Loading column stats for table: " + name_);
    List<ColumnStatisticsObj> colStats;

    // We need to only query those columns which may have stats; asking HMS for other
    // columns causes loadAllColumnStats() to return nothing.
    List<String> colNames = getColumnNamesWithHmsStats();

    try {
      colStats = client.getTableColumnStatistics(db_.getName(), name_, colNames);
    } catch (Exception e) {
      LOG.warn("Could not load column statistics for: " + getFullName(), e);
      return;
    }

    for (ColumnStatisticsObj stats: colStats) {
      Column col = getColumn(stats.getColName());
      Preconditions.checkNotNull(col);
      if (!ColumnStats.isSupportedColType(col.getType())) {
        LOG.warn(String.format("Statistics for %s, column %s are not supported as " +
                "column has type %s", getFullName(), col.getName(), col.getType()));
        continue;
      }

      if (!col.updateStats(stats.getStatsData())) {
        LOG.warn(String.format("Failed to load column stats for %s, column %s. Stats " +
            "may be incompatible with column type %s. Consider regenerating statistics " +
            "for %s.", getFullName(), col.getName(), col.getType(), getFullName()));
        continue;
      }
    }
  }

  /**
   * Returns the value of the ROW_COUNT constant, or -1 if not found.
   */
  protected static long getRowCount(Map<String, String> parameters) {
    if (parameters == null) return -1;
    String numRowsStr = parameters.get(StatsSetupConst.ROW_COUNT);
    if (numRowsStr == null) return -1;
    try {
      return Long.valueOf(numRowsStr);
    } catch (NumberFormatException exc) {
      // ignore
    }
    return -1;
  }

  /**
   * Creates a table of the appropriate type based on the given hive.metastore.api.Table
   * object.
   */
  public static Table fromMetastoreTable(Db db,
      org.apache.hadoop.hive.metastore.api.Table msTbl) {
    // Create a table of appropriate type
    Table table = null;
    if (TableType.valueOf(msTbl.getTableType()) == TableType.VIRTUAL_VIEW) {
      table = new View(msTbl, db, msTbl.getTableName(), msTbl.getOwner());
    } else if (HBaseTable.isHBaseTable(msTbl)) {
      table = new HBaseTable(msTbl, db, msTbl.getTableName(), msTbl.getOwner());
    } else if (KuduTable.isKuduTable(msTbl)) {
      table = new KuduTable(msTbl, db, msTbl.getTableName(), msTbl.getOwner());
    } else if (DataSourceTable.isDataSourceTable(msTbl)) {
      // It's important to check if this is a DataSourceTable before HdfsTable because
      // DataSourceTables are still represented by HDFS tables in the metastore but
      // have a special table property to indicate that Impala should use an external
      // data source.
      table = new DataSourceTable(msTbl, db, msTbl.getTableName(), msTbl.getOwner());
    } else if (HdfsFileFormat.isHdfsInputFormatClass(msTbl.getSd().getInputFormat())) {
      table = new HdfsTable(msTbl, db, msTbl.getTableName(), msTbl.getOwner());
    }
    return table;
  }

  /**
   * Factory method that creates a new Table from its Thrift representation.
   * Determines the type of table to create based on the Thrift table provided.
   */
  public static Table fromThrift(Db parentDb, TTable thriftTable)
      throws TableLoadingException {
    Table newTable;
    if (!thriftTable.isSetLoad_status() && thriftTable.isSetMetastore_table())  {
      newTable = Table.fromMetastoreTable(parentDb, thriftTable.getMetastore_table());
    } else {
      newTable =
          IncompleteTable.createUninitializedTable(parentDb, thriftTable.getTbl_name());
    }
    newTable.loadFromThrift(thriftTable);
    newTable.validate();
    return newTable;
  }

  public boolean isClusteringColumn(Column c) {
    return c.getPosition() < numClusteringCols_;
  }

  protected void loadFromThrift(TTable thriftTable) throws TableLoadingException {
    List<TColumn> columns = new ArrayList<TColumn>();
    columns.addAll(thriftTable.getClustering_columns());
    columns.addAll(thriftTable.getColumns());

    colsByPos_.clear();
    colsByPos_.ensureCapacity(columns.size());
    try {
      for (int i = 0; i < columns.size(); ++i) {
        Column col = Column.fromThrift(columns.get(i));
        colsByPos_.add(col.getPosition(), col);
        colsByName_.put(col.getName().toLowerCase(), col);
        ((StructType) type_.getItemType()).addField(
            new StructField(col.getName(), col.getType(), col.getComment()));
      }
    } catch (ImpalaRuntimeException e) {
      throw new TableLoadingException(String.format("Error loading schema for " +
          "table '%s'", getName()), e);
    }

    numClusteringCols_ = thriftTable.getClustering_columns().size();

    // Estimated number of rows
    numRows_ = thriftTable.isSetTable_stats() ?
        thriftTable.getTable_stats().getNum_rows() : -1;

    // Default to READ_WRITE access if the field is not set.
    accessLevel_ = thriftTable.isSetAccess_level() ? thriftTable.getAccess_level() :
        TAccessLevel.READ_WRITE;
  }

  /**
   * Checks preconditions for this table to function as expected. Currently only checks
   * that all entries in colsByName_ use lower case keys.
   */
  public void validate() throws TableLoadingException {
    for (String colName: colsByName_.keySet()) {
      if (!colName.equals(colName.toLowerCase())) {
        throw new TableLoadingException(
            "Expected lower case column name but found: " + colName);
      }
    }
  }

  public TTable toThrift() {
    TTable table = new TTable(db_.getName(), name_);
    table.setAccess_level(accessLevel_);

    // Populate both regular columns and clustering columns (if there are any).
    table.setColumns(new ArrayList<TColumn>());
    table.setClustering_columns(new ArrayList<TColumn>());
    for (int i = 0; i < colsByPos_.size(); ++i) {
      TColumn colDesc = colsByPos_.get(i).toThrift();
      // Clustering columns come first.
      if (i < numClusteringCols_) {
        table.addToClustering_columns(colDesc);
      } else {
        table.addToColumns(colDesc);
      }
    }

    table.setMetastore_table(getMetaStoreTable());
    if (numRows_ != -1) {
      table.setTable_stats(new TTableStats());
      table.getTable_stats().setNum_rows(numRows_);
    }
    return table;
  }

  public TCatalogObject toTCatalogObject() {
    TCatalogObject catalogObject = new TCatalogObject();
    catalogObject.setType(getCatalogObjectType());
    catalogObject.setCatalog_version(getCatalogVersion());
    catalogObject.setTable(toThrift());
    return catalogObject;
  }

  /**
   * Gets the ColumnType from the given FieldSchema by using Impala's SqlParser.
   * Throws a TableLoadingException if the FieldSchema could not be parsed.
   * The type can either be:
   *   - Supported by Impala, in which case the type is returned.
   *   - A type Impala understands but is not yet implemented (e.g. date), the type is
   *     returned but type.IsSupported() returns false.
   *   - A supported type that exceeds an Impala limit, e.g., on the nesting depth.
   *   - A type Impala can't understand at all, and a TableLoadingException is thrown.
   */
   protected Type parseColumnType(FieldSchema fs) throws TableLoadingException {
     Type type = Type.parseColumnType(fs.getType());
     if (type == null) {
       throw new TableLoadingException(String.format(
           "Unsupported type '%s' in column '%s' of table '%s'",
           fs.getType(), fs.getName(), getName()));
     }
     if (type.exceedsMaxNestingDepth()) {
       throw new TableLoadingException(String.format(
           "Type exceeds the maximum nesting depth of %s:\n%s",
           Type.MAX_NESTING_DEPTH, type.toSql()));
     }
     return type;
   }

  public Db getDb() { return db_; }
  public String getName() { return name_; }
  public String getFullName() { return (db_ != null ? db_.getName() + "." : "") + name_; }
  public TableName getTableName() {
    return new TableName(db_ != null ? db_.getName() : null, name_);
  }

  public String getOwner() { return owner_; }
  public ArrayList<Column> getColumns() { return colsByPos_; }

  /**
   * Returns a list of the column names ordered by position.
   */
  public List<String> getColumnNames() {
    List<String> colNames = Lists.<String>newArrayList();
    for (Column col: colsByPos_) {
      colNames.add(col.getName());
    }
    return colNames;
  }

  /**
   * Returns a list of thrift column descriptors ordered by position.
   */
  public List<TColumnDescriptor> getTColumnDescriptors() {
    List<TColumnDescriptor> colDescs = Lists.<TColumnDescriptor>newArrayList();
    for (Column col: colsByPos_) {
      colDescs.add(new TColumnDescriptor(col.getName(), col.getType().toThrift()));
    }
    return colDescs;
  }

  /**
   * Subclasses should override this if they provide a storage handler class. Currently
   * only HBase tables need to provide a storage handler.
   */
  public String getStorageHandlerClassName() { return null; }

  /**
   * Returns the list of all columns, but with partition columns at the end of
   * the list rather than the beginning. This is equivalent to the order in
   * which Hive enumerates columns.
   */
  public ArrayList<Column> getColumnsInHiveOrder() {
    ArrayList<Column> columns = Lists.newArrayList(getNonClusteringColumns());
    columns.addAll(getClusteringColumns());
    return columns;
  }

  /**
   * Returns a struct type with the columns in the same order as getColumnsInHiveOrder().
   */
  public StructType getHiveColumnsAsStruct() {
    ArrayList<StructField> fields = Lists.newArrayListWithCapacity(colsByPos_.size());
    for (Column col: getColumnsInHiveOrder()) {
      fields.add(new StructField(col.getName(), col.getType(), col.getComment()));
    }
    return new StructType(fields);
  }

  /**
   * Returns the list of all partition columns.
   */
  public List<Column> getClusteringColumns() {
    return colsByPos_.subList(0, numClusteringCols_);
  }

  /**
   * Returns the list of all columns excluding any partition columns.
   */
  public List<Column> getNonClusteringColumns() {
    return colsByPos_.subList(numClusteringCols_, colsByPos_.size());
  }

  /**
   * Case-insensitive lookup.
   */
  public Column getColumn(String name) { return colsByName_.get(name.toLowerCase()); }

  /**
   * Returns the metastore.api.Table object this Table was created from. Returns null
   * if the derived Table object was not created from a metastore Table (ex. InlineViews).
   */
  public org.apache.hadoop.hive.metastore.api.Table getMetaStoreTable() {
    return msTable_;
  }

  public void setMetaStoreTable(org.apache.hadoop.hive.metastore.api.Table msTbl) {
    msTable_ = msTbl;
  }

  public int getNumClusteringCols() { return numClusteringCols_; }
  public long getNumRows() { return numRows_; }
  public ArrayType getType() { return type_; }

  @Override
  public long getCatalogVersion() { return catalogVersion_; }

  @Override
  public void setCatalogVersion(long catalogVersion) {
    catalogVersion_ = catalogVersion;
  }

  @Override
  public boolean isLoaded() { return true; }

  public static boolean isExternalTable(
      org.apache.hadoop.hive.metastore.api.Table msTbl) {
    return msTbl.getTableType().equalsIgnoreCase(TableType.EXTERNAL_TABLE.toString());
  }

  /**
   * If the table is cached, it returns a <cache pool name, replication factor> pair
   * and adds the table cached directive ID to 'cacheDirIds'. Otherwise, it
   * returns a <null, 0> pair.
   */
  public Pair<String, Short> getTableCacheInfo(List<Long> cacheDirIds) {
    String cachePoolName = null;
    Short cacheReplication = 0;
    Long cacheDirId = HdfsCachingUtil.getCacheDirectiveId(msTable_.getParameters());
    if (cacheDirId != null) {
      try {
        cachePoolName = HdfsCachingUtil.getCachePool(cacheDirId);
        cacheReplication = HdfsCachingUtil.getCacheReplication(cacheDirId);
        Preconditions.checkNotNull(cacheReplication);
        if (numClusteringCols_ == 0) cacheDirIds.add(cacheDirId);
      } catch (ImpalaRuntimeException e) {
        // Catch the error so that the actual update to the catalog can progress,
        // this resets caching for the table though
        LOG.error(
            String.format("Cache directive %d was not found, uncache the table %s " +
                "to remove this message.", cacheDirId, getFullName()));
        cacheDirId = null;
      }
    }
    return new Pair<String, Short>(cachePoolName, cacheReplication);
  }
}
