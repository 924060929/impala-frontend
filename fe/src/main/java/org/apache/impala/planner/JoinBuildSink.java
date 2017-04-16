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

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.impala.analysis.Analyzer;
import org.apache.impala.analysis.BinaryPredicate;
import org.apache.impala.analysis.Expr;
import org.apache.impala.common.ImpalaException;
import org.apache.impala.thrift.TDataSink;
import org.apache.impala.thrift.TDataSinkType;
import org.apache.impala.thrift.TExplainLevel;
import org.apache.impala.thrift.TJoinBuildSink;
import org.apache.impala.thrift.TPlanNode;
import org.apache.impala.thrift.TPlanNodeType;
import org.apache.impala.thrift.TQueryOptions;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

/**
 * Sink to materialize the build side of a join.
 */
public class JoinBuildSink extends DataSink {
  private final static Logger LOG = LoggerFactory.getLogger(JoinBuildSink.class);

  // id of join's build-side table assigned during planning
  private final JoinTableId joinTableId_;

  private final List<Expr> buildExprs_ = Lists.newArrayList();

  /**
   * Creates sink for build side of 'joinNode' (extracts buildExprs_ from joinNode).
   */
  public JoinBuildSink(JoinTableId joinTableId, JoinNode joinNode) {
    Preconditions.checkState(joinTableId.isValid());
    joinTableId_ = joinTableId;
    Preconditions.checkNotNull(joinNode);
    Preconditions.checkState(joinNode instanceof JoinNode);
    if (!(joinNode instanceof HashJoinNode)) return;
    for (Expr eqJoinConjunct: joinNode.getEqJoinConjuncts()) {
      BinaryPredicate p = (BinaryPredicate) eqJoinConjunct;
      // by convention the build exprs are the rhs of the join conjuncts
      buildExprs_.add(p.getChild(1).clone());
    }
  }

  public JoinTableId getJoinTableId() { return joinTableId_; }

  @Override
  protected TDataSink toThrift() {
    TDataSink result = new TDataSink(TDataSinkType.JOIN_BUILD_SINK);
    TJoinBuildSink tBuildSink = new TJoinBuildSink();
    tBuildSink.setJoin_table_id(joinTableId_.asInt());
    for (Expr buildExpr: buildExprs_) {
      tBuildSink.addToBuild_exprs(buildExpr.treeToThrift());
    }
    result.setJoin_build_sink(tBuildSink);
    return result;
  }

  @Override
  public String getExplainString(String prefix, String detailPrefix,
      TExplainLevel detailLevel) {
    StringBuilder output = new StringBuilder();
    output.append(String.format("%s%s\n", prefix, "JOIN BUILD"));
    if (detailLevel.ordinal() > TExplainLevel.MINIMAL.ordinal()) {
      output.append(
          detailPrefix + "join-table-id=" + joinTableId_.toString()
            + " plan-id=" + fragment_.getPlanId().toString()
            + " cohort-id=" + fragment_.getCohortId().toString() + "\n");
      if (!buildExprs_.isEmpty()) {
        output.append(detailPrefix + "build expressions: ")
            .append(Expr.toSql(buildExprs_) + "\n");
      }
    }
    return output.toString();
  }

  @Override
  public void computeCosts() {
    // TODO: implement?
  }
}
