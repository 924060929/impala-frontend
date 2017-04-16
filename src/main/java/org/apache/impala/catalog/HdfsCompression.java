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

import org.apache.impala.thrift.THdfsCompression;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;

/**
 * Support for recognizing compression suffixes on data files.
 * Compression of a file is recognized in mapreduce by looking for suffixes of
 * supported codecs.
 * For now Impala supports LZO, GZIP, SNAPPY, and BZIP2. LZO can use the specific HIVE
 * input class.
 */
// TODO: Add LZ4?
public enum HdfsCompression {
  NONE,
  DEFLATE,
  GZIP,
  BZIP2,
  SNAPPY,
  LZO,
  LZO_INDEX; //Lzo index file.

  /* Map from a suffix to a compression type */
  private static final ImmutableMap<String, HdfsCompression> SUFFIX_MAP =
      ImmutableMap.<String, HdfsCompression>builder().
          put("deflate", DEFLATE).
          put("gz", GZIP).
          put("bz2", BZIP2).
          put("snappy", SNAPPY).
          put("lzo", LZO).
          put("index", LZO_INDEX).
          build();

  /* Given a file name return its compression type, if any. */
  public static HdfsCompression fromFileName(String fileName) {
    int index = fileName.lastIndexOf(".");
    if (index == -1) {
      return NONE;
    }

    String suffix = fileName.substring(index + 1);
    HdfsCompression compression = SUFFIX_MAP.get(suffix.toLowerCase());
    return compression == null ? NONE : compression;
  }

  public THdfsCompression toThrift() {
    switch (this) {
    case NONE: return THdfsCompression.NONE;
    case DEFLATE: return THdfsCompression.DEFLATE;
    case GZIP: return THdfsCompression.GZIP;
    case BZIP2: return THdfsCompression.BZIP2;
    case SNAPPY: return THdfsCompression.SNAPPY_BLOCKED;
    case LZO: return THdfsCompression.LZO;
    default: throw new IllegalStateException("Unexpected codec: " + this);
    }
  }

  /* Returns a compression type based on (Hive's) intput format. Special case for LZO. */
  public static HdfsCompression fromHdfsInputFormatClass(String inputFormatClass) {
    // TODO: Remove when we have the native LZO writer.
    Preconditions.checkNotNull(inputFormatClass);
    if (inputFormatClass.equals(HdfsFileFormat.LZO_TEXT.inputFormat())) {
      return LZO;
    }
    return NONE;
  }
}
