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

import org.apache.impala.catalog.Catalog;
import org.apache.impala.catalog.Db;
import org.apache.impala.common.AnalysisException;
import org.apache.impala.thrift.TFunctionName;
import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;

/**
 * Class to represent a function name. Function names are specified as
 * db.function_name.
 */
public class FunctionName extends SyntaxBlock {
  // Only set for parsed function names.
  private final ArrayList<String> fnNamePath_;

  // Set/validated during analysis.
  private String db_;
  private String fn_;
  private boolean isBuiltin_ = false;
  private boolean isAnalyzed_ = false;

  /**
   * C'tor for parsed function names. The function names could be invalid. The validity
   * is checked during analysis.
   */
  public FunctionName(ArrayList<String> fnNamePath) {
    fnNamePath_ = fnNamePath;
  }

  public FunctionName(String dbName, String fn) {
    db_ = (dbName != null) ? dbName.toLowerCase() : null;
    fn_ = fn.toLowerCase();
    fnNamePath_ = null;
  }

  public FunctionName(String fn) {
    this(null, fn);
  }

  @Override
  public boolean equals(Object obj) {
    if (!(obj instanceof FunctionName)) return false;
    FunctionName o = (FunctionName)obj;
    if ((db_ == null || o.db_ == null) && (db_ != o.db_)) {
      if (db_ == null && o.db_ != null) return false;
      if (db_ != null && o.db_ == null) return false;
      if (!db_.equalsIgnoreCase(o.db_)) return false;
    }
    return fn_.equalsIgnoreCase(o.fn_);
  }

  public String getDb() { return db_; }
  public String getFunction() { return fn_; }
  public boolean isFullyQualified() { return db_ != null; }
  public boolean isBuiltin() { return isBuiltin_; }
  public ArrayList<String> getFnNamePath() { return fnNamePath_; }

  @Override
  public String toString() {
    // The fnNamePath_ is not always set.
    if (!isAnalyzed_ && fnNamePath_ != null) return Joiner.on(".").join(fnNamePath_);
    if (db_ == null || isBuiltin_) return fn_;
    return db_ + "." + fn_;
  }

  public void analyze(Analyzer analyzer) throws AnalysisException {
    if (isAnalyzed_) return;
    analyzeFnNamePath();
    if (fn_.isEmpty()) throw new AnalysisException("Function name cannot be empty.");
    for (int i = 0; i < fn_.length(); ++i) {
      if (!isValidCharacter(fn_.charAt(i))) {
        throw new AnalysisException(
            "Function names must be all alphanumeric or underscore. " +
            "Invalid name: " + fn_);
      }
    }
    if (Character.isDigit(fn_.charAt(0))) {
      throw new AnalysisException("Function cannot start with a digit: " + fn_);
    }

    // Resolve the database for this function.
    if (!isFullyQualified()) {
      Db builtinDb = analyzer.getCatalog().getBuiltinsDb();
      if (builtinDb.containsFunction(fn_)) {
        // If it isn't fully qualified and is the same name as a builtin, use
        // the builtin.
        db_ = Catalog.BUILTINS_DB;
        isBuiltin_ = true;
      } else {
        db_ = analyzer.getDefaultDb();
        isBuiltin_ = false;
      }
    } else {
      isBuiltin_ = db_.equals(Catalog.BUILTINS_DB);
    }
    isAnalyzed_ = true;
  }

  private void analyzeFnNamePath() throws AnalysisException {
    if (fnNamePath_ == null) return;
    if (fnNamePath_.size() > 2 || fnNamePath_.isEmpty()) {
      throw new AnalysisException(
          String.format("Invalid function name: '%s'. Expected [dbname].funcname.",
              Joiner.on(".").join(fnNamePath_)));
    } else if (fnNamePath_.size() > 1) {
      db_ = fnNamePath_.get(0);
      fn_ = fnNamePath_.get(1).toLowerCase();
    } else {
      Preconditions.checkState(fnNamePath_.size() == 1);
      fn_ = fnNamePath_.get(0).toLowerCase();
    }
  }

  private boolean isValidCharacter(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  public TFunctionName toThrift() {
    TFunctionName name = new TFunctionName(fn_);
    name.setDb_name(db_);
    return name;
  }

  public static FunctionName fromThrift(TFunctionName fnName) {
    return new FunctionName(fnName.getDb_name(), fnName.getFunction_name());
  }
}
