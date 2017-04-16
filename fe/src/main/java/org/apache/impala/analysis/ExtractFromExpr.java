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

import java.util.Set;

import org.apache.impala.catalog.Catalog;
import org.apache.impala.catalog.Type;
import org.apache.impala.common.AnalysisException;
import org.apache.impala.thrift.TExtractField;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

/**
 * Representation of an EXTRACT(<Time Unit> FROM <Datetime Expr>) expression. EXTRACT(
 * <Datetime Expr>, <String>) is not handled by FunctionCallExpr.
 */
public class ExtractFromExpr extends FunctionCallExpr {

  // Behaves like an immutable linked hash set containing the TExtractFields in the same
  // order as declared.
  private static final Set<String> EXTRACT_FIELDS;
  static {
    ImmutableSet.Builder<String> builder = new ImmutableSet.Builder<String>();
    for (TExtractField extractField: TExtractField.values()) {
      if (extractField != TExtractField.INVALID_FIELD) {
        builder.add(extractField.name());
      }
    }
    EXTRACT_FIELDS = builder.build();
  }

  public ExtractFromExpr(FunctionName fnName, String extractFieldIdent, Expr e) {
    // Note that the arguments are swapped so that they align with the EXTRACT function.
    // There is no EXTRACT(STRING, TIMESTAMP) function because it conflicts with
    // EXTRACT(TIMESTAMP, STRING) if STRINGs are used for TIMESTAMPs with implicit
    // casting.
    super(fnName, Lists.newArrayList(e, new StringLiteral(extractFieldIdent)));
    type_ = Type.INT;
  }

  /**
   * Copy c'tor used in clone().
   */
  protected ExtractFromExpr(ExtractFromExpr other) {
    super(other);
  }

  @Override
  public void analyze(Analyzer analyzer) throws AnalysisException {
    getFnName().analyze(analyzer);
    if (!getFnName().getFunction().equals("extract")) {
      throw new AnalysisException("Function " + getFnName().getFunction().toUpperCase()
          + " does not accept the keyword FROM.");
    }
    if ((getFnName().getDb() != null)
        && !getFnName().getDb().equals(Catalog.BUILTINS_DB)) {
      throw new AnalysisException("Function " + getFnName().toString() + " conflicts " +
          "with the EXTRACT builtin.");
    }
    if (isAnalyzed_) return;
    super.analyze(analyzer);

    String extractFieldIdent = ((StringLiteral)children_.get(1)).getValue();
    Preconditions.checkNotNull(extractFieldIdent);
    if (!EXTRACT_FIELDS.contains(extractFieldIdent.toUpperCase())) {
      throw new AnalysisException("Time unit '" + extractFieldIdent + "' in expression '"
          + toSql() + "' is invalid. Expected one of "
          + Joiner.on(", ").join(EXTRACT_FIELDS) + ".");
    }
  }

  @Override
  protected String getFunctionNotFoundError(Type[] argTypes) {
    Expr e = children_.get(0);
    return "Expression '" + e.toSql() + "' in '" + toSql() + "' has a return type of "
          + e.getType().toSql() + " but a TIMESTAMP is required.";
  }

  @Override
  public String toSqlImpl() {
    StringBuilder strBuilder = new StringBuilder();
    strBuilder.append(getFnName().toString().toUpperCase());
    strBuilder.append("(");
    strBuilder.append(((StringLiteral)getChild(1)).getValue());
    strBuilder.append(" FROM ");
    strBuilder.append(getChild(0).toSql());
    strBuilder.append(")");
    return strBuilder.toString();
  }

  @Override
  public Expr clone() { return new ExtractFromExpr(this); }
}
