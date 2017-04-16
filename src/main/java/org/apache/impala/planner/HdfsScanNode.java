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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.impala.analysis.Analyzer;
import org.apache.impala.analysis.Expr;
import org.apache.impala.analysis.SlotDescriptor;
import org.apache.impala.analysis.TableRef;
import org.apache.impala.analysis.TupleDescriptor;
import org.apache.impala.analysis.TupleId;
import org.apache.impala.catalog.Column;
import org.apache.impala.catalog.HdfsFileFormat;
import org.apache.impala.catalog.HdfsPartition;
import org.apache.impala.catalog.HdfsPartition.FileBlock;
import org.apache.impala.catalog.HdfsTable;
import org.apache.impala.catalog.Type;
import org.apache.impala.common.ImpalaException;
import org.apache.impala.common.NotImplementedException;
import org.apache.impala.common.PrintUtils;
import org.apache.impala.common.RuntimeEnv;
import org.apache.impala.thrift.TExplainLevel;
import org.apache.impala.thrift.TExpr;
import org.apache.impala.thrift.THdfsFileBlock;
import org.apache.impala.thrift.THdfsFileSplit;
import org.apache.impala.thrift.THdfsScanNode;
import org.apache.impala.thrift.TNetworkAddress;
import org.apache.impala.thrift.TPlanNode;
import org.apache.impala.thrift.TPlanNodeType;
import org.apache.impala.thrift.TQueryOptions;
import org.apache.impala.thrift.TReplicaPreference;
import org.apache.impala.thrift.TScanRange;
import org.apache.impala.thrift.TScanRangeLocation;
import org.apache.impala.thrift.TScanRangeLocationList;
import org.apache.impala.util.MembershipSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Objects.ToStringHelper;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

/**
 * Scan of a single single table. Currently limited to full-table scans.
 *
 * It's expected that the creator of this object has already done any necessary
 * partition pruning before creating this object. In other words, the 'conjuncts'
 * passed to the constructors are conjucts not fully evaluated by partition pruning
 * and 'partitions' are the remaining partitions after pruning.
 *
 * TODO: pass in range restrictions.
 */
public class HdfsScanNode extends ScanNode {
  private final static Logger LOG = LoggerFactory.getLogger(HdfsScanNode.class);

  // Read size of the backend I/O manager. Used in computeCosts().
  private final static long IO_MGR_BUFFER_SIZE = 8L * 1024L * 1024L;

  // Maximum number of I/O buffers per thread executing this scan.
  private final static long MAX_IO_BUFFERS_PER_THREAD = 10;

  // Number of scanner threads per core executing this scan.
  private final static int THREADS_PER_CORE = 3;

  // Factor capturing the worst-case deviation from a uniform distribution of scan ranges
  // among nodes. The factor of 1.2 means that a particular node may have 20% more
  // scan ranges than would have been estimated assuming a uniform distribution.
  private final static double SCAN_RANGE_SKEW_FACTOR = 1.2;

  private final HdfsTable tbl_;

  // Partitions that are filtered in for scanning by the key ranges
  private final List<HdfsPartition> partitions_;

  private final TReplicaPreference replicaPreference_;
  private final boolean randomReplica_;

  // Total number of files from partitions_
  private long totalFiles_ = 0;

  // Total number of bytes from partitions_
  private long totalBytes_ = 0;

  // True if this scan node should use the MT implementation in the backend.
  private boolean useMtScanNode_;

  // Conjuncts that can be evaluated while materializing the items (tuples) of
  // collection-typed slots. Maps from tuple descriptor to the conjuncts bound by that
  // tuple. Uses a linked hash map for consistent display in explain.
  private final Map<TupleDescriptor, List<Expr>> collectionConjuncts_ =
      Maps.newLinkedHashMap();

  // Indicates corrupt table stats based on the number of non-empty scan ranges and
  // numRows set to 0. Set in computeStats().
  private boolean hasCorruptTableStats_;

  // Number of header lines to skip at the beginning of each file of this table. Only set
  // to values > 0 for hdfs text files.
  private int skipHeaderLineCount_ = 0;

  /**
   * Construct a node to scan given data files into tuples described by 'desc',
   * with 'conjuncts' being the unevaluated conjuncts bound by the tuple and
   * 'partitions' being the partitions which need to be included. Please see
   * class comments above for details.
   */
  public HdfsScanNode(PlanNodeId id, TupleDescriptor desc, List<Expr> conjuncts,
      List<HdfsPartition> partitions, TableRef hdfsTblRef) {
    super(id, desc, "SCAN HDFS");
    Preconditions.checkState(desc.getTable() instanceof HdfsTable);
    tbl_ = (HdfsTable)desc.getTable();
    conjuncts_ = conjuncts;
    partitions_ = partitions;
    replicaPreference_ = hdfsTblRef.getReplicaPreference();
    randomReplica_ = hdfsTblRef.getRandomReplica();
    HdfsTable hdfsTable = (HdfsTable)hdfsTblRef.getTable();
    Preconditions.checkState(tbl_ == hdfsTable);
    StringBuilder error = new StringBuilder();
    skipHeaderLineCount_ = tbl_.parseSkipHeaderLineCount(error);
    if (error.length() > 0) {
      // Any errors should already have been caught during analysis.
      throw new IllegalStateException(error.toString());
    }
  }

  @Override
  protected String debugString() {
    ToStringHelper helper = Objects.toStringHelper(this);
    for (HdfsPartition partition: partitions_) {
      helper.add("Partition " + partition.getId() + ":", partition.toString());
    }
    return helper.addValue(super.debugString()).toString();
  }

  /**
   * Populate collectionConjuncts_ and scanRanges_.
   */
  @Override
  public void init(Analyzer analyzer) throws ImpalaException {
    conjuncts_ = orderConjunctsByCost(conjuncts_);
    checkForSupportedFileFormats();

    assignCollectionConjuncts(analyzer);

    computeMemLayout(analyzer);

    // compute scan range locations
    Set<HdfsFileFormat> fileFormats = computeScanRangeLocations(analyzer);

    // Determine backend scan node implementation to use. The optimized MT implementation
    // is currently only supported for Parquet.
    if (analyzer.getQueryOptions().isSetMt_dop() &&
        analyzer.getQueryOptions().mt_dop > 0 &&
        fileFormats.size() == 1 && fileFormats.contains(HdfsFileFormat.PARQUET)) {
      useMtScanNode_ = true;
    } else {
      useMtScanNode_ = false;
    }

    // do this at the end so it can take all conjuncts and scan ranges into account
    computeStats(analyzer);

    // TODO: do we need this?
    assignedConjuncts_ = analyzer.getAssignedConjuncts();
  }

  /**
   * Throws if the table schema contains a complex type and we need to scan
   * a partition that has a format for which we do not support complex types,
   * regardless of whether a complex-typed column is actually referenced
   * in the query.
   */
  @Override
  protected void checkForSupportedFileFormats() throws NotImplementedException {
    Preconditions.checkNotNull(desc_);
    Preconditions.checkNotNull(desc_.getTable());
    Column firstComplexTypedCol = null;
    for (Column col: desc_.getTable().getColumns()) {
      if (col.getType().isComplexType()) {
        firstComplexTypedCol = col;
        break;
      }
    }
    if (firstComplexTypedCol == null) return;

    boolean referencesComplexTypedCol = false;
    for (SlotDescriptor slotDesc: desc_.getSlots()) {
      if (!slotDesc.isMaterialized()) continue;
      if (slotDesc.getType().isComplexType() || slotDesc.getColumn() == null) {
        referencesComplexTypedCol = true;
        break;
      }
    }

    for (HdfsPartition part: partitions_) {
      HdfsFileFormat format = part.getInputFormatDescriptor().getFileFormat();
      if (format.isComplexTypesSupported()) continue;
      // If the file format allows querying just scalar typed columns and the query
      // doesn't materialize any complex typed columns, it is allowed.
      if (format.canSkipComplexTypes() && !referencesComplexTypedCol) {
        continue;
      }
      String errSuffix = String.format(
          "Complex types are supported for these file formats: %s",
          Joiner.on(", ").join(HdfsFileFormat.complexTypesFormats()));
      if (desc_.getTable().getNumClusteringCols() == 0) {
        throw new NotImplementedException(String.format(
            "Scan of table '%s' in format '%s' is not supported because the table " +
            "has a column '%s' with a complex type '%s'.\n%s.",
            desc_.getAlias(), format, firstComplexTypedCol.getName(),
            firstComplexTypedCol.getType().toSql(), errSuffix));
      }
      throw new NotImplementedException(String.format(
          "Scan of partition '%s' in format '%s' of table '%s' is not supported " +
          "because the table has a column '%s' with a complex type '%s'.\n%s.",
          part.getPartitionName(), format, desc_.getAlias(),
          firstComplexTypedCol.getName(), firstComplexTypedCol.getType().toSql(),
          errSuffix));
    }
  }

  public boolean isPartitionedTable() {
    return desc_.getTable().getNumClusteringCols() > 0;
  }

  /**
   * Populates the collection conjuncts, materializes their required slots, and marks
   * the conjuncts as assigned, if it is correct to do so. Some conjuncts may have to
   * also be evaluated at a subsequent semi or outer join.
   */
  private void assignCollectionConjuncts(Analyzer analyzer) {
    collectionConjuncts_.clear();
    assignCollectionConjuncts(desc_, analyzer);
  }

  /**
   * Recursively collects and assigns conjuncts bound by tuples materialized in a
   * collection-typed slot.
   *
   * Limitation: Conjuncts that must first be migrated into inline views and that cannot
   * be captured by slot binding will not be assigned here, but in an UnnestNode.
   * This limitation applies to conjuncts bound by inline-view slots that are backed by
   * non-SlotRef exprs in the inline-view's select list. We only capture value transfers
   * between slots, and not between arbitrary exprs.
   *
   * TODO for 2.3: The logic for gathering conjuncts and deciding which ones should be
   * marked as assigned needs to be clarified and consolidated in one place. The code
   * below is rather different from the code for assigning the top-level conjuncts in
   * init() although the performed tasks is conceptually identical. Refactoring the
   * assignment code is tricky/risky for now.
   */
  private void assignCollectionConjuncts(TupleDescriptor tupleDesc, Analyzer analyzer) {
    for (SlotDescriptor slotDesc: tupleDesc.getSlots()) {
      if (!slotDesc.getType().isCollectionType()) continue;
      Preconditions.checkNotNull(slotDesc.getItemTupleDesc());
      TupleDescriptor itemTupleDesc = slotDesc.getItemTupleDesc();
      TupleId itemTid = itemTupleDesc.getId();
      // First collect unassigned and binding predicates. Then remove redundant
      // predicates based on slot equivalences and enforce slot equivalences by
      // generating new predicates.
      List<Expr> collectionConjuncts =
          analyzer.getUnassignedConjuncts(Lists.newArrayList(itemTid));
      ArrayList<Expr> bindingPredicates = analyzer.getBoundPredicates(itemTid);
      for (Expr boundPred: bindingPredicates) {
        if (!collectionConjuncts.contains(boundPred)) collectionConjuncts.add(boundPred);
      }
      analyzer.createEquivConjuncts(itemTid, collectionConjuncts);
      // Mark those conjuncts as assigned that do not also need to be evaluated by a
      // subsequent semi or outer join.
      for (Expr conjunct: collectionConjuncts) {
        if (!analyzer.evalAfterJoin(conjunct)) analyzer.markConjunctAssigned(conjunct);
      }
      if (!collectionConjuncts.isEmpty()) {
        analyzer.materializeSlots(collectionConjuncts);
        collectionConjuncts_.put(itemTupleDesc, collectionConjuncts);
      }
      // Recursively look for collection-typed slots in nested tuple descriptors.
      assignCollectionConjuncts(itemTupleDesc, analyzer);
    }
  }

  /**
   * Computes scan ranges (hdfs splits) plus their storage locations, including volume
   * ids, based on the given maximum number of bytes each scan range should scan.
   * Returns the set of file formats being scanned.
   */
  private Set<HdfsFileFormat> computeScanRangeLocations(Analyzer analyzer) {
    long maxScanRangeLength = analyzer.getQueryCtx().getRequest().getQuery_options()
        .getMax_scan_range_length();
    scanRanges_ = Lists.newArrayList();
    Set<HdfsFileFormat> fileFormats = Sets.newHashSet();
    for (HdfsPartition partition: partitions_) {
      fileFormats.add(partition.getFileFormat());
      Preconditions.checkState(partition.getId() >= 0);
      for (HdfsPartition.FileDescriptor fileDesc: partition.getFileDescriptors()) {
        for (THdfsFileBlock thriftBlock: fileDesc.getFileBlocks()) {
          HdfsPartition.FileBlock block = FileBlock.fromThrift(thriftBlock);
          List<Integer> replicaHostIdxs = block.getReplicaHostIdxs();
          if (replicaHostIdxs.size() == 0) {
            // we didn't get locations for this block; for now, just ignore the block
            // TODO: do something meaningful with that
            continue;
          }
          // Collect the network address and volume ID of all replicas of this block.
          List<TScanRangeLocation> locations = Lists.newArrayList();
          for (int i = 0; i < replicaHostIdxs.size(); ++i) {
            TScanRangeLocation location = new TScanRangeLocation();
            // Translate from the host index (local to the HdfsTable) to network address.
            Integer tableHostIdx = replicaHostIdxs.get(i);
            TNetworkAddress networkAddress =
                partition.getTable().getHostIndex().getEntry(tableHostIdx);
            Preconditions.checkNotNull(networkAddress);
            // Translate from network address to the global (to this request) host index.
            Integer globalHostIdx = analyzer.getHostIndex().getIndex(networkAddress);
            location.setHost_idx(globalHostIdx);
            location.setVolume_id(block.getDiskId(i));
            location.setIs_cached(block.isCached(i));
            locations.add(location);
          }
          // create scan ranges, taking into account maxScanRangeLength
          long currentOffset = block.getOffset();
          long remainingLength = block.getLength();
          while (remainingLength > 0) {
            long currentLength = remainingLength;
            if (maxScanRangeLength > 0 && remainingLength > maxScanRangeLength) {
              currentLength = maxScanRangeLength;
            }
            TScanRange scanRange = new TScanRange();
            scanRange.setHdfs_file_split(new THdfsFileSplit(
                fileDesc.getFileName(), currentOffset, currentLength, partition.getId(),
                fileDesc.getFileLength(), fileDesc.getFileCompression(),
                fileDesc.getModificationTime()));
            TScanRangeLocationList scanRangeLocations = new TScanRangeLocationList();
            scanRangeLocations.scan_range = scanRange;
            scanRangeLocations.locations = locations;
            scanRanges_.add(scanRangeLocations);
            remainingLength -= currentLength;
            currentOffset += currentLength;
          }
        }
      }
    }
    return fileFormats;
  }

  /**
   * Also computes totalBytes_, totalFiles_, numPartitionsMissingStats_,
   * and sets hasCorruptTableStats_.
   */
  @Override
  public void computeStats(Analyzer analyzer) {
    super.computeStats(analyzer);
    if (LOG.isTraceEnabled()) {
      LOG.trace("collecting partitions for table " + tbl_.getName());
    }
    numPartitionsMissingStats_ = 0;
    totalFiles_ = 0;
    totalBytes_ = 0;
    if (tbl_.getNumClusteringCols() == 0) {
      cardinality_ = tbl_.getNumRows();
      if (cardinality_ < -1 || (cardinality_ == 0 && tbl_.getTotalHdfsBytes() > 0)) {
        hasCorruptTableStats_ = true;
      }
      if (partitions_.isEmpty()) {
        // Nothing to scan. Definitely a cardinality of 0 even if we have no stats.
        cardinality_ = 0;
      } else {
        Preconditions.checkState(partitions_.size() == 1);
        totalFiles_ += partitions_.get(0).getFileDescriptors().size();
        totalBytes_ += partitions_.get(0).getSize();
      }
    } else {
      cardinality_ = 0;
      boolean hasValidPartitionCardinality = false;
      for (HdfsPartition p: partitions_) {
        // Check for corrupt table stats
        if (p.getNumRows() < -1  || (p.getNumRows() == 0 && p.getSize() > 0))  {
          hasCorruptTableStats_ = true;
        }
        // ignore partitions with missing stats in the hope they don't matter
        // enough to change the planning outcome
        if (p.getNumRows() > -1) {
          cardinality_ = addCardinalities(cardinality_, p.getNumRows());
          hasValidPartitionCardinality = true;
        } else {
          ++numPartitionsMissingStats_;
        }
        totalFiles_ += p.getFileDescriptors().size();
        totalBytes_ += p.getSize();
      }
      if (!partitions_.isEmpty() && !hasValidPartitionCardinality) {
        // if none of the partitions knew its number of rows, we fall back on
        // the table stats
        cardinality_ = tbl_.getNumRows();
      }
    }
    // Adjust cardinality for all collections referenced along the tuple's path.
    if (cardinality_ != -1) {
      for (Type t: desc_.getPath().getMatchedTypes()) {
        if (t.isCollectionType()) cardinality_ *= PlannerContext.AVG_COLLECTION_SIZE;
      }
    }
    inputCardinality_ = cardinality_;

    // Sanity check scan node cardinality.
    if (cardinality_ < -1) {
      hasCorruptTableStats_ = true;
      cardinality_ = -1;
    }

    if (cardinality_ > 0) {
      if (LOG.isTraceEnabled()) {
        LOG.trace("cardinality_=" + Long.toString(cardinality_) +
                  " sel=" + Double.toString(computeSelectivity()));
      }
      cardinality_ = Math.round(cardinality_ * computeSelectivity());
      // IMPALA-2165: Avoid setting the cardinality to 0 after rounding.
      cardinality_ = Math.max(cardinality_, 1);
    }
    cardinality_ = capAtLimit(cardinality_);
    if (LOG.isTraceEnabled()) {
      LOG.trace("computeStats HdfsScan: cardinality_=" + Long.toString(cardinality_));
    }

    computeNumNodes(analyzer, cardinality_);
    if (LOG.isTraceEnabled()) {
      LOG.trace("computeStats HdfsScan: #nodes=" + Integer.toString(numNodes_));
    }
  }

  /**
   * Estimate the number of impalad nodes that this scan node will execute on (which is
   * ultimately determined by the scheduling done by the backend's SimpleScheduler).
   * Assume that scan ranges that can be scheduled locally will be, and that scan
   * ranges that cannot will be round-robined across the cluster.
   */
  protected void computeNumNodes(Analyzer analyzer, long cardinality) {
    Preconditions.checkNotNull(scanRanges_);
    MembershipSnapshot cluster = MembershipSnapshot.getCluster();
    HashSet<TNetworkAddress> localHostSet = Sets.newHashSet();
    int totalNodes = 0;
    int numLocalRanges = 0;
    int numRemoteRanges = 0;
    for (TScanRangeLocationList range: scanRanges_) {
      boolean anyLocal = false;
      for (TScanRangeLocation loc: range.locations) {
        TNetworkAddress dataNode = analyzer.getHostIndex().getEntry(loc.getHost_idx());
        if (cluster.contains(dataNode)) {
          anyLocal = true;
          // Use the full datanode address (including port) to account for the test
          // minicluster where there are multiple datanodes and impalads on a single
          // host.  This assumes that when an impalad is colocated with a datanode,
          // there are the same number of impalads as datanodes on this host in this
          // cluster.
          localHostSet.add(dataNode);
        }
      }
      // This range has at least one replica with a colocated impalad, so assume it
      // will be scheduled on one of those nodes.
      if (anyLocal) {
        ++numLocalRanges;
      } else {
        ++numRemoteRanges;
      }
      // Approximate the number of nodes that will execute locally assigned ranges to
      // be the smaller of the number of locally assigned ranges and the number of
      // hosts that hold block replica for those ranges.
      int numLocalNodes = Math.min(numLocalRanges, localHostSet.size());
      // The remote ranges are round-robined across all the impalads.
      int numRemoteNodes = Math.min(numRemoteRanges, cluster.numNodes());
      // The local and remote assignments may overlap, but we don't know by how much so
      // conservatively assume no overlap.
      totalNodes = Math.min(numLocalNodes + numRemoteNodes, cluster.numNodes());
      // Exit early if all hosts have a scan range assignment, to avoid extraneous work
      // in case the number of scan ranges dominates the number of nodes.
      if (totalNodes == cluster.numNodes()) break;
    }
    // Tables can reside on 0 nodes (empty table), but a plan node must always be
    // executed on at least one node.
    numNodes_ = (cardinality == 0 || totalNodes == 0) ? 1 : totalNodes;
    if (LOG.isTraceEnabled()) {
      LOG.trace("computeNumNodes totalRanges=" + scanRanges_.size() +
          " localRanges=" + numLocalRanges + " remoteRanges=" + numRemoteRanges +
          " localHostSet.size=" + localHostSet.size() +
          " clusterNodes=" + cluster.numNodes());
    }
  }

  @Override
  protected void toThrift(TPlanNode msg) {
    msg.hdfs_scan_node = new THdfsScanNode(desc_.getId().asInt());
    if (replicaPreference_ != null) {
      msg.hdfs_scan_node.setReplica_preference(replicaPreference_);
    }
    msg.hdfs_scan_node.setRandom_replica(randomReplica_);
    msg.node_type = TPlanNodeType.HDFS_SCAN_NODE;
    if (!collectionConjuncts_.isEmpty()) {
      Map<Integer, List<TExpr>> tcollectionConjuncts = Maps.newLinkedHashMap();
      for (Map.Entry<TupleDescriptor, List<Expr>> entry:
        collectionConjuncts_.entrySet()) {
        tcollectionConjuncts.put(entry.getKey().getId().asInt(),
            Expr.treesToThrift(entry.getValue()));
      }
      msg.hdfs_scan_node.setCollection_conjuncts(tcollectionConjuncts);
    }
    if (skipHeaderLineCount_ > 0) {
      msg.hdfs_scan_node.setSkip_header_line_count(skipHeaderLineCount_);
    }
    msg.hdfs_scan_node.setUse_mt_scan_node(useMtScanNode_);
  }

  @Override
  protected String getDisplayLabelDetail() {
    HdfsTable table = (HdfsTable) desc_.getTable();
    List<String> path = Lists.newArrayList();
    path.add(table.getDb().getName());
    path.add(table.getName());
    Preconditions.checkNotNull(desc_.getPath());
    if (desc_.hasExplicitAlias()) {
      return desc_.getPath().toString() + " " + desc_.getAlias();
    } else {
      return desc_.getPath().toString();
    }
  }

  @Override
  protected String getNodeExplainString(String prefix, String detailPrefix,
      TExplainLevel detailLevel) {
    StringBuilder output = new StringBuilder();
    HdfsTable table = (HdfsTable) desc_.getTable();
    output.append(String.format("%s%s [%s", prefix, getDisplayLabel(),
        getDisplayLabelDetail()));
    if (detailLevel.ordinal() >= TExplainLevel.EXTENDED.ordinal() &&
        fragment_.isPartitioned()) {
      output.append(", " + fragment_.getDataPartition().getExplainString());
    }
    output.append("]\n");
    if (detailLevel.ordinal() >= TExplainLevel.STANDARD.ordinal()) {
      int numPartitions = partitions_.size();
      if (tbl_.getNumClusteringCols() == 0) numPartitions = 1;
      output.append(String.format("%spartitions=%s/%s files=%s size=%s", detailPrefix,
          numPartitions, table.getPartitions().size() - 1, totalFiles_,
          PrintUtils.printBytes(totalBytes_)));
      output.append("\n");
      if (!conjuncts_.isEmpty()) {
        output.append(
            detailPrefix + "predicates: " + getExplainString(conjuncts_) + "\n");
      }
      if (!collectionConjuncts_.isEmpty()) {
        for (Map.Entry<TupleDescriptor, List<Expr>> entry:
          collectionConjuncts_.entrySet()) {
          String alias = entry.getKey().getAlias();
          output.append(String.format("%spredicates on %s: %s\n",
              detailPrefix, alias, getExplainString(entry.getValue())));
        }
      }
      if (!runtimeFilters_.isEmpty()) {
        output.append(detailPrefix + "runtime filters: ");
        output.append(getRuntimeFilterExplainString(false));
      }
    }
    if (detailLevel.ordinal() >= TExplainLevel.EXTENDED.ordinal()) {
      output.append(getStatsExplainString(detailPrefix, detailLevel));
      output.append("\n");
    }
    return output.toString();
  }

  @Override
  public void computeCosts(TQueryOptions queryOptions) {
    Preconditions.checkNotNull(scanRanges_, "Cost estimation requires scan ranges.");
    if (scanRanges_.isEmpty()) {
      perHostMemCost_ = 0;
      return;
    }
    Preconditions.checkState(0 < numNodes_ && numNodes_ <= scanRanges_.size());
    Preconditions.checkNotNull(desc_);
    Preconditions.checkNotNull(desc_.getTable() instanceof HdfsTable);
    HdfsTable table = (HdfsTable) desc_.getTable();
    int perHostScanRanges;
    if (table.getMajorityFormat() == HdfsFileFormat.PARQUET) {
      // For the purpose of this estimation, the number of per-host scan ranges for
      // Parquet files are equal to the number of non-partition columns scanned.
      perHostScanRanges = 0;
      for (SlotDescriptor slot: desc_.getSlots()) {
        if (slot.getColumn() == null ||
            slot.getColumn().getPosition() >= table.getNumClusteringCols()) {
          ++perHostScanRanges;
        }
      }
    } else {
      perHostScanRanges = (int) Math.ceil((
          (double) scanRanges_.size() / (double) numNodes_) * SCAN_RANGE_SKEW_FACTOR);
    }

    // TODO: The total memory consumption for a particular query depends on the number
    // of *available* cores, i.e., it depends the resource consumption of other
    // concurrent queries. Figure out how to account for that.
    int maxScannerThreads = Math.min(perHostScanRanges,
        RuntimeEnv.INSTANCE.getNumCores() * THREADS_PER_CORE);
    // Account for the max scanner threads query option.
    if (queryOptions.isSetNum_scanner_threads() &&
        queryOptions.getNum_scanner_threads() > 0) {
      maxScannerThreads =
          Math.min(maxScannerThreads, queryOptions.getNum_scanner_threads());
    }

    long avgScanRangeBytes = (long) Math.ceil(totalBytes_ / (double) scanRanges_.size());
    // The +1 accounts for an extra I/O buffer to read past the scan range due to a
    // trailing record spanning Hdfs blocks.
    long perThreadIoBuffers =
        Math.min((long) Math.ceil(avgScanRangeBytes / (double) IO_MGR_BUFFER_SIZE),
            MAX_IO_BUFFERS_PER_THREAD) + 1;
    perHostMemCost_ = maxScannerThreads * perThreadIoBuffers * IO_MGR_BUFFER_SIZE;

    // Sanity check: the tighter estimation should not exceed the per-host maximum.
    long perHostUpperBound = getPerHostMemUpperBound();
    if (perHostMemCost_ > perHostUpperBound) {
      LOG.warn(String.format("Per-host mem cost %s exceeded per-host upper bound %s.",
          PrintUtils.printBytes(perHostMemCost_),
          PrintUtils.printBytes(perHostUpperBound)));
      perHostMemCost_ = perHostUpperBound;
    }
  }

  /**
   * Hdfs scans use a shared pool of buffers managed by the I/O manager. Intuitively,
   * the maximum number of I/O buffers is limited by the total disk bandwidth of a node.
   * Therefore, this upper bound is independent of the number of concurrent scans and
   * queries and helps to derive a tighter per-host memory estimate for queries with
   * multiple concurrent scans.
   */
  public static long getPerHostMemUpperBound() {
    // THREADS_PER_CORE each using a default of
    // MAX_IO_BUFFERS_PER_THREAD * IO_MGR_BUFFER_SIZE bytes.
    return (long) RuntimeEnv.INSTANCE.getNumCores() * (long) THREADS_PER_CORE *
        MAX_IO_BUFFERS_PER_THREAD * IO_MGR_BUFFER_SIZE;
  }

  @Override
  public boolean hasCorruptTableStats() { return hasCorruptTableStats_; }
}
