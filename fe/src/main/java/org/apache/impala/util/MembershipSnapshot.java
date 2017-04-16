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

package org.apache.impala.util;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.impala.thrift.TNetworkAddress;
import org.apache.impala.thrift.TUpdateMembershipRequest;
import com.google.common.collect.Sets;

/**
 * Singleton class that represents a snapshot of the Impalad cluster membership.  Host
 * membership is determined by both IP address and hostname (to mimic the backend's
 * SimpleScheduler).  A new snapshot is created whenever the cluster membership changes
 * so that clients don't need to hold a lock while examining a snapshot.
 */
public class MembershipSnapshot {

  // The latest instance of the MembershipSnapshot.
  private static AtomicReference<MembershipSnapshot> cluster_ =
      new AtomicReference<MembershipSnapshot>(new MembershipSnapshot());

  // The set of hosts that are members of the cluster given by hostname.
  private final Set<String> hostnames_;

  // The set of hosts that are members of the cluster given by IP address.
  private final Set<String> ipAddresses_;

  // The number of nodes of the cluster.  Normally, this will be equal to
  // hostnames_.size(), except in the test minicluster where there are multiple
  // impalad's running on a single host.
  private final int numNodes_;

  // Used only to construct the initial MembershipSnapshot.  Before we get the first
  // snapshot, assume one node (the localhost) to mimic SimpleScheduler.
  private MembershipSnapshot() {
    hostnames_ = Sets.newHashSet();
    ipAddresses_ = Sets.newHashSet();
    numNodes_ = 1;
  }

  // Construct a new snapshot based on the TUpdateMembershipRequest.
  private MembershipSnapshot(TUpdateMembershipRequest request) {
    hostnames_ = request.getHostnames();
    ipAddresses_ = request.getIp_addresses();
    numNodes_ = request.getNum_nodes();
  }

  // Determine whether a host, given either by IP address or hostname, is a member of
  // this snapshot.  Returns true if it is, false otherwise.
  public boolean contains(TNetworkAddress address) {
    String host = address.getHostname();
    return ipAddresses_.contains(host) || hostnames_.contains(host);
  }

  // The number of nodes in this snapshot.
  public int numNodes() { return numNodes_; }

  // Atomically update the singleton snapshot instance.  After the update completes,
  // all calls to getCluster() will return the new snapshot.
  public static void update(TUpdateMembershipRequest request) {
    cluster_.set(new MembershipSnapshot(request));
  }

  // Return the current singleton snapshot instance.
  public static MembershipSnapshot getCluster() { return cluster_.get(); }

}
