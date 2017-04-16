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

import java.util.LinkedList;

import org.apache.impala.analysis.AnalysisContext;
import org.apache.impala.analysis.Analyzer;
import org.apache.impala.analysis.QueryStmt;
import org.apache.impala.common.IdGenerator;
import org.apache.impala.thrift.TQueryCtx;
import org.apache.impala.thrift.TQueryOptions;

import com.google.common.collect.Lists;

/**
 * Contains the analysis result of a query as well as planning-specific
 * parameters and state such as plan-node and plan-fragment id generators.
 */
public class PlannerContext {
  // Estimate of the overhead imposed by storing data in a hash tbl;
  // used for determining whether a broadcast join is feasible.
  public final static double HASH_TBL_SPACE_OVERHEAD = 1.1;

  // The maximum fraction of remaining memory that a sort node can use during execution.
  public final static double SORT_MEM_MAX_FRACTION = 0.80;

  // Assumed average number of items in a nested collection, since we currently have no
  // statistics on nested fields. The motivation for this constant is to avoid
  // pathological plan choices that could result from a SubplanNode having an unknown
  // cardinality (due to UnnestNodes not knowing their cardinality), or from a ScanNode
  // significantly underestimating its output cardinality because intermediate collections
  // are not accounted for at all. For example, we will place a table ref plan with a
  // SubplanNode on the build side of a join due to an unknown cardinality if the other
  // input is a base table scan with stats.
  // The constant value was chosen arbitrarily to not be "too high" or "too low".
  // TODO: Compute stats for nested types and pick them up here.
  public static final long AVG_COLLECTION_SIZE = 10;

  private final IdGenerator<PlanNodeId> nodeIdGenerator_ = PlanNodeId.createGenerator();
  private final IdGenerator<PlanFragmentId> fragmentIdGenerator_ =
      PlanFragmentId.createGenerator();

  // Keeps track of subplan nesting. Maintained with push/popSubplan().
  private final LinkedList<SubplanNode> subplans_ = Lists.newLinkedList();

  private final TQueryCtx queryCtx_;
  private final AnalysisContext.AnalysisResult analysisResult_;
  private final QueryStmt queryStmt_;

  public PlannerContext (AnalysisContext.AnalysisResult analysisResult,
      TQueryCtx queryCtx) {
    analysisResult_ = analysisResult;
    queryCtx_ = queryCtx;
    if (isInsertOrCtas()) {
      queryStmt_ = analysisResult.getInsertStmt().getQueryStmt();
    } else if (analysisResult.isUpdateStmt()) {
      queryStmt_ = analysisResult.getUpdateStmt().getQueryStmt();
    } else if (analysisResult.isDeleteStmt()) {
      queryStmt_ = analysisResult.getDeleteStmt().getQueryStmt();
    } else {
      queryStmt_ = analysisResult.getQueryStmt();
    }
  }

  public QueryStmt getQueryStmt() { return queryStmt_; }
  public TQueryCtx getQueryCtx() { return queryCtx_; }
  public TQueryOptions getQueryOptions() { return getRootAnalyzer().getQueryOptions(); }
  public AnalysisContext.AnalysisResult getAnalysisResult() { return analysisResult_; }
  public Analyzer getRootAnalyzer() { return analysisResult_.getAnalyzer(); }
  public boolean isSingleNodeExec() { return getQueryOptions().num_nodes == 1; }
  public PlanNodeId getNextNodeId() { return nodeIdGenerator_.getNextId(); }
  public PlanFragmentId getNextFragmentId() { return fragmentIdGenerator_.getNextId(); }
  public boolean isInsertOrCtas() {
    return analysisResult_.isInsertStmt() || analysisResult_.isCreateTableAsSelectStmt();
  }
  public boolean isInsert() { return analysisResult_.isInsertStmt(); }
  public boolean isUpdateOrDelete() {
    return analysisResult_.isUpdateStmt() || analysisResult_.isDeleteStmt(); }
  public boolean isQuery() { return analysisResult_.isQueryStmt(); }
  public boolean hasTableSink() {
    return isInsertOrCtas() || analysisResult_.isUpdateStmt()
        || analysisResult_.isDeleteStmt();
  }
  public boolean hasSubplan() { return !subplans_.isEmpty(); }
  public SubplanNode getSubplan() { return subplans_.getFirst(); }
  public boolean pushSubplan(SubplanNode n) { return subplans_.offerFirst(n); }
  public void popSubplan() { subplans_.removeFirst(); }
  public boolean isUpdate() { return analysisResult_.isUpdateStmt(); }
  public boolean isDelete() { return analysisResult_.isDeleteStmt(); }
}
