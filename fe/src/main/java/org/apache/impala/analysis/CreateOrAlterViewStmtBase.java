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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.impala.common.AnalysisException;
import org.apache.impala.thrift.TCreateOrAlterViewParams;
import org.apache.impala.thrift.TTableName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

/**
 * Base class for CREATE VIEW and ALTER VIEW AS SELECT statements.
 */
public abstract class CreateOrAlterViewStmtBase extends StatementBase {
  private final static Logger LOG =
      LoggerFactory.getLogger(CreateOrAlterViewStmtBase.class);

  protected final boolean ifNotExists_;
  protected final TableName tableName_;
  protected final ArrayList<ColumnDef> columnDefs_;
  protected final String comment_;
  protected final QueryStmt viewDefStmt_;

  // Set during analysis
  protected String dbName_;
  protected String owner_;

  // The original SQL-string given as view definition. Set during analysis.
  // Corresponds to Hive's viewOriginalText.
  protected String originalViewDef_;

  // Query statement (as SQL string) that defines the View for view substitution.
  // It is a transformation of the original view definition, e.g., to enforce the
  // columnDefs even if the original view definition has explicit column aliases.
  // If column definitions were given, then this "expanded" view definition
  // wraps the original view definition in a select stmt as follows.
  //
  // SELECT viewName.origCol1 AS colDesc1, viewName.origCol2 AS colDesc2, ...
  // FROM (originalViewDef) AS viewName
  //
  // Corresponds to Hive's viewExpandedText, but is not identical to the SQL
  // Hive would produce in view creation.
  protected String inlineViewDef_;

  // Columns to use in the select list of the expanded SQL string and when registering
  // this view in the metastore. Set in analysis.
  protected ArrayList<ColumnDef> finalColDefs_;

  public CreateOrAlterViewStmtBase(boolean ifNotExists, TableName tableName,
      ArrayList<ColumnDef> columnDefs, String comment, QueryStmt viewDefStmt) {
    Preconditions.checkNotNull(tableName);
    Preconditions.checkNotNull(viewDefStmt);
    this.ifNotExists_ = ifNotExists;
    this.tableName_ = tableName;
    this.columnDefs_ = columnDefs;
    this.comment_ = comment;
    this.viewDefStmt_ = viewDefStmt;
  }

  /**
   * Sets the originalViewDef and the expanded inlineViewDef based on viewDefStmt.
   * If columnDefs were given, checks that they do not contain duplicate column names
   * and throws an exception if they do.
   */
  protected void createColumnAndViewDefs(Analyzer analyzer) throws AnalysisException {
    Preconditions.checkNotNull(dbName_);
    Preconditions.checkNotNull(owner_);

    // Set the finalColDefs to reflect the given column definitions.
    if (columnDefs_ != null) {
      Preconditions.checkState(!columnDefs_.isEmpty());
      if (columnDefs_.size() != viewDefStmt_.getColLabels().size()) {
        String cmp =
            (columnDefs_.size() > viewDefStmt_.getColLabels().size()) ? "more" : "fewer";
        throw new AnalysisException(String.format("Column-definition list has " +
            "%s columns (%s) than the view-definition query statement returns (%s).",
            cmp, columnDefs_.size(), viewDefStmt_.getColLabels().size()));
      }

      finalColDefs_ = columnDefs_;
      Preconditions.checkState(
          columnDefs_.size() == viewDefStmt_.getBaseTblResultExprs().size());
      for (int i = 0; i < columnDefs_.size(); ++i) {
        // Set type in the column definition from the view-definition statement.
        columnDefs_.get(i).setType(viewDefStmt_.getBaseTblResultExprs().get(i).getType());
      }
    } else {
      // Create list of column definitions from the view-definition statement.
      finalColDefs_ = Lists.newArrayList();
      List<Expr> exprs = viewDefStmt_.getBaseTblResultExprs();
      List<String> labels = viewDefStmt_.getColLabels();
      Preconditions.checkState(exprs.size() == labels.size());
      for (int i = 0; i < viewDefStmt_.getColLabels().size(); ++i) {
        ColumnDef colDef = new ColumnDef(labels.get(i), null);
        colDef.setType(exprs.get(i).getType());
        finalColDefs_.add(colDef);
      }
    }

    // Check that the column definitions have valid names, and that there are no
    // duplicate column names.
    Set<String> distinctColNames = Sets.newHashSet();
    for (ColumnDef colDesc: finalColDefs_) {
      colDesc.analyze(null);
      if (!distinctColNames.add(colDesc.getColName().toLowerCase())) {
        throw new AnalysisException("Duplicate column name: " + colDesc.getColName());
      }
    }

    // Set original and expanded view-definition SQL strings.
    originalViewDef_ = viewDefStmt_.toSql();

    // If no column definitions were given, then the expanded view SQL is the same
    // as the original one.
    if (columnDefs_ == null) {
      inlineViewDef_ = originalViewDef_;
      return;
    }

    // Wrap the original view-definition statement into a SELECT to enforce the
    // given column definitions.
    StringBuilder sb = new StringBuilder();
    sb.append("SELECT ");
    for (int i = 0; i < finalColDefs_.size(); ++i) {
      String colRef = ToSqlUtils.getIdentSql(viewDefStmt_.getColLabels().get(i));
      String colAlias = ToSqlUtils.getIdentSql(finalColDefs_.get(i).getColName());
      sb.append(String.format("%s.%s AS %s", tableName_.getTbl(), colRef, colAlias));
      sb.append((i+1 != finalColDefs_.size()) ? ", " : "");
    }
    // Do not use 'AS' for table aliases because Hive only accepts them without 'AS'.
    sb.append(String.format(" FROM (%s) %s", originalViewDef_, tableName_.getTbl()));
    inlineViewDef_ = sb.toString();
  }

  /**
   * Computes the column lineage graph for a create/alter view statement.
   */
  protected void computeLineageGraph(Analyzer analyzer) {
    ColumnLineageGraph graph = analyzer.getColumnLineageGraph();
    List<String> colDefs = Lists.newArrayList();
    for (ColumnDef colDef: finalColDefs_) {
      colDefs.add(dbName_ + "." + getTbl() + "." + colDef.getColName());
    }
    graph.addTargetColumnLabels(colDefs);
    graph.computeLineageGraph(viewDefStmt_.getResultExprs(), analyzer);
    if (LOG.isTraceEnabled()) LOG.trace("lineage: " + graph.debugString());
  }

  public TCreateOrAlterViewParams toThrift() {
    TCreateOrAlterViewParams params = new TCreateOrAlterViewParams();
    params.setView_name(new TTableName(getDb(), getTbl()));
    for (ColumnDef col: finalColDefs_) {
      params.addToColumns(col.toThrift());
    }
    params.setOwner(getOwner());
    params.setIf_not_exists(getIfNotExists());
    params.setOriginal_view_def(originalViewDef_);
    params.setExpanded_view_def(inlineViewDef_);
    if (comment_ != null) params.setComment(comment_);
    return params;
  }

  /**
   * Can only be called after analysis, returns the name of the database the table will
   * be created within.
   */
  public String getDb() {
    Preconditions.checkNotNull(dbName_);
    return dbName_;
  }

  /**
   * Can only be called after analysis, returns the owner of the view to be created.
   */
  public String getOwner() {
    Preconditions.checkNotNull(owner_);
    return owner_;
  }

  public List<ColumnDef> getColumnDescs() {return columnDefs_; }
  public String getComment() { return comment_; }
  public boolean getIfNotExists() { return ifNotExists_; }
  public String getOriginalViewDef() { return originalViewDef_; }
  public String getInlineViewDef() { return inlineViewDef_; }
  public String getTbl() { return tableName_.getTbl(); }
}
