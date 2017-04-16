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


package org.apache.impala.planner;

import org.apache.impala.analysis.DescriptorTable;
import org.apache.impala.catalog.Table;
import org.apache.impala.common.PrintUtils;
import org.apache.impala.thrift.TDataSink;
import org.apache.impala.thrift.TDataSinkType;
import org.apache.impala.thrift.TExplainLevel;
import org.apache.impala.thrift.TTableSink;
import org.apache.impala.thrift.TTableSinkType;

/**
 * Class used to represent a Sink that will transport
 * data from a plan fragment into an HBase table using HTable.
 */
public class HBaseTableSink extends TableSink {
  public HBaseTableSink(Table targetTable) {
    super(targetTable, Op.INSERT);
  }

  @Override
  public String getExplainString(String prefix, String detailPrefix,
      TExplainLevel explainLevel) {
    StringBuilder output = new StringBuilder();
    output.append(prefix + "WRITE TO HBASE table=" + targetTable_.getFullName() + "\n");
    if (explainLevel.ordinal() >= TExplainLevel.EXTENDED.ordinal()) {
      output.append(PrintUtils.printHosts(detailPrefix, fragment_.getNumNodes()));
      output.append(PrintUtils.printMemCost(" ", perHostMemCost_));
      output.append("\n");
    }
    return output.toString();
  }

  @Override
  protected TDataSink toThrift() {
    TDataSink result = new TDataSink(TDataSinkType.TABLE_SINK);
    TTableSink tTableSink = new TTableSink(DescriptorTable.TABLE_SINK_ID,
        TTableSinkType.HBASE, sinkOp_.toThrift());
    result.table_sink = tTableSink;
    return result;
  }
}
