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

package org.apache.impala.analysis;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.avro.SchemaParseException;
import org.apache.hadoop.hive.metastore.api.hive_metastoreConstants;
import org.apache.hadoop.hive.serde2.avro.AvroSerdeUtils;
import org.apache.impala.catalog.HdfsTable;
import org.apache.impala.catalog.Table;
import org.apache.impala.common.AnalysisException;
import org.apache.impala.thrift.TAlterTableParams;
import org.apache.impala.thrift.TAlterTableSetTblPropertiesParams;
import org.apache.impala.thrift.TAlterTableType;
import org.apache.impala.thrift.TTablePropertyType;
import org.apache.impala.util.AvroSchemaParser;
import org.apache.impala.util.AvroSchemaUtils;
import org.apache.impala.util.MetaStoreUtil;

import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;

/**
* Represents an ALTER TABLE SET [PARTITION ('k1'='a', 'k2'='b'...)]
* TBLPROPERTIES|SERDEPROPERTIES ('p1'='v1', ...) statement.
*/
public class AlterTableSetTblProperties extends AlterTableSetStmt {
  private final TTablePropertyType targetProperty_;
  private final HashMap<String, String> tblProperties_;

  public AlterTableSetTblProperties(TableName tableName, PartitionSet partitionSet,
      TTablePropertyType targetProperty, HashMap<String, String> tblProperties) {
    super(tableName, partitionSet);
    Preconditions.checkNotNull(tblProperties);
    Preconditions.checkNotNull(targetProperty);
    targetProperty_ = targetProperty;
    tblProperties_ = tblProperties;
    CreateTableStmt.unescapeProperties(tblProperties_);
  }

  public HashMap<String, String> getTblProperties() { return tblProperties_; }

  @Override
  public TAlterTableParams toThrift() {
   TAlterTableParams params = super.toThrift();
   params.setAlter_type(TAlterTableType.SET_TBL_PROPERTIES);
   TAlterTableSetTblPropertiesParams tblPropertyParams =
       new TAlterTableSetTblPropertiesParams();
   tblPropertyParams.setTarget(targetProperty_);
   tblPropertyParams.setProperties(tblProperties_);
   if (partitionSet_ != null) {
     tblPropertyParams.setPartition_set(partitionSet_.toThrift());
   }
   params.setSet_tbl_properties_params(tblPropertyParams);
   return params;
  }

  @Override
  public void analyze(Analyzer analyzer) throws AnalysisException {
    super.analyze(analyzer);

    MetaStoreUtil.checkShortPropertyMap("Property", tblProperties_);

    if (tblProperties_.containsKey(hive_metastoreConstants.META_TABLE_STORAGE)) {
      throw new AnalysisException(String.format("Changing the '%s' table property is " +
          "not supported to protect against metadata corruption.",
          hive_metastoreConstants.META_TABLE_STORAGE));
    }

    // Check avro schema when it is set in avro.schema.url or avro.schema.literal to
    // avoid potential metadata corruption (see IMPALA-2042).
    // If both properties are set then only check avro.schema.literal and ignore
    // avro.schema.url.
    if (tblProperties_.containsKey(
            AvroSerdeUtils.AvroTableProperties.SCHEMA_LITERAL.getPropName()) ||
        tblProperties_.containsKey(
            AvroSerdeUtils.AvroTableProperties.SCHEMA_URL.getPropName())) {
      analyzeAvroSchema(analyzer);
    }

    // Analyze 'skip.header.line.format' property.
    analyzeSkipHeaderLineCount(getTargetTable(), tblProperties_);
  }

  /**
   * Check that Avro schema provided in avro.schema.url or avro.schema.literal is valid
   * Json and contains only supported Impala types. If both properties are set, then
   * avro.schema.url is ignored.
   */
  private void analyzeAvroSchema(Analyzer analyzer)
      throws AnalysisException {
    List<Map<String, String>> schemaSearchLocations = Lists.newArrayList();
    schemaSearchLocations.add(tblProperties_);

    String avroSchema = AvroSchemaUtils.getAvroSchema(schemaSearchLocations);
    avroSchema = Strings.nullToEmpty(avroSchema);
    if (avroSchema.isEmpty()) {
      throw new AnalysisException("Avro schema is null or empty: " +
          table_.getFullName());
    }

    // Check if the schema is valid and is supported by Impala
    try {
      AvroSchemaParser.parse(avroSchema);
    } catch (SchemaParseException e) {
      throw new AnalysisException(String.format(
          "Error parsing Avro schema for table '%s': %s", table_.getFullName(),
          e.getMessage()));
    }
  }

  /**
   * Analyze the 'skip.header.line.count' property to make sure it is set to a valid
   * value. It is looked up in 'tblProperties', which must not be null.
   */
  public static void analyzeSkipHeaderLineCount(Map<String, String> tblProperties)
      throws AnalysisException {
    analyzeSkipHeaderLineCount(null, tblProperties);
  }

  /**
   * Analyze the 'skip.header.line.count' property to make sure it is set to a valid
   * value. It is looked up in 'tblProperties', which must not be null. If 'table' is not
   * null, then the method ensures that 'skip.header.line.count' is supported for its
   * table type. If it is null, then this check is omitted.
   */
  public static void analyzeSkipHeaderLineCount(Table table,
      Map<String, String> tblProperties) throws AnalysisException {
    if (tblProperties.containsKey(HdfsTable.TBL_PROP_SKIP_HEADER_LINE_COUNT)) {
      if (table != null && !(table instanceof HdfsTable)) {
        throw new AnalysisException(String.format("Table property " +
            "'skip.header.line.count' is only supported for HDFS tables."));
      }
      StringBuilder error = new StringBuilder();
      HdfsTable.parseSkipHeaderLineCount(tblProperties, error);
      if (error.length() > 0) throw new AnalysisException(error.toString());
    }
  }
}
