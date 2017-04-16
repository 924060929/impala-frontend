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

import java.util.List;

import org.apache.impala.common.Pair;
import org.apache.impala.planner.DataSink;
import org.apache.impala.planner.TableSink;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

/**
 * Representation of a DELETE statement.
 *
 * A delete statement contains three main parts, the target table reference, the from
 * clause and the optional where clause. Syntactically, this is represented as follows:
 *
 *     DELETE [FROM] dotted_path [WHERE expr]
 *     DELETE [table_alias] FROM table_ref_list [WHERE expr]
 *
 * Only the syntax using the explicit from clause can contain join conditions.
 */
public class DeleteStmt extends ModifyStmt {

  public DeleteStmt(List<String> targetTablePath, FromClause tableRefs,
      Expr wherePredicate) {
    super(targetTablePath, tableRefs, Lists.<Pair<SlotRef, Expr>>newArrayList(),
        wherePredicate);
  }

  public DeleteStmt(DeleteStmt other) {
    super(other.targetTablePath_, other.fromClause_.clone(),
        Lists.<Pair<SlotRef, Expr>>newArrayList(), other.wherePredicate_.clone());
  }

  @Override
  public DataSink createDataSink() {
    // analyze() must have been called before.
    Preconditions.checkState(table_ != null);
    TableSink tableSink = TableSink.create(table_, TableSink.Op.DELETE,
        ImmutableList.<Expr>of(), referencedColumns_, false, false);
    Preconditions.checkState(!referencedColumns_.isEmpty());
    return tableSink;
  }

  @Override
  public DeleteStmt clone() {
    return new DeleteStmt(this);
  }

  @Override
  public String toSql() {
    StringBuilder b = new StringBuilder();
    b.append("DELETE");
    if (fromClause_.size() > 1 || targetTableRef_.hasExplicitAlias()) {
      b.append(" ");
      if (targetTableRef_.hasExplicitAlias()) {
        b.append(targetTableRef_.getExplicitAlias());
      } else {
        b.append(targetTableRef_.toSql());
      }
    }
    b.append(fromClause_.toSql());
    if (wherePredicate_ != null) {
      b.append(" WHERE ");
      b.append(wherePredicate_.toSql());
    }
    return b.toString();
  }
}
