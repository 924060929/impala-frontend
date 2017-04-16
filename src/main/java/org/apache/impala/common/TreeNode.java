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

package org.apache.impala.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.apache.impala.analysis.SyntaxBlock;
import org.apache.impala.util.Visitor;
import com.google.common.base.Predicate;

/**
 * Generic tree structure. Only concrete subclasses of this can be instantiated.
 */
public abstract class TreeNode<NodeType extends TreeNode<NodeType>> extends SyntaxBlock {
  protected ArrayList<NodeType> children_ = new ArrayList<NodeType>();

  public NodeType getChild(int i) {
    return hasChild(i) ? children_.get(i) : null;
  }

  public void addChild(NodeType n) {
    children_.add(n);
  }

  public void removeChild(NodeType n) { children_.remove(n); }

  public void clearChildren() { children_.clear(); }

  public void addChildren(List<? extends NodeType> l) {
    children_.addAll(l);
  }

  public boolean hasChild(int i) { return children_.size() > i; }
  public void setChild(int index, NodeType n) { children_.set(index, n); }
  public ArrayList<NodeType> getChildren() { return children_; }

  /**
   * Return list of all nodes of the tree rooted at 'this', obtained
   * through pre-order traversal.
   */
  public <C extends TreeNode<NodeType>> ArrayList<C> getNodesPreOrder() {
    ArrayList<C> result = new ArrayList<C>();
    getNodesPreOrderAux(result);
    return result;
  }

  protected <C extends TreeNode<NodeType>> void getNodesPreOrderAux(
      ArrayList<C> result) {
    result.add((C) this);
    for (NodeType child: children_) child.getNodesPreOrderAux(result);
  }

  /**
   * Count the total number of nodes in this tree. Leaf node will return 1.
   * Non-leaf node will include all its children.
   */
  public int numNodes() {
    int numNodes = 1;
    for (NodeType child: children_) numNodes += child.numNodes();
    return numNodes;
  }

  /**
   * Add all nodes in the tree that satisfy 'predicate' to the list 'matches'
   * This node is checked first, followed by its children in order. If the node
   * itself matches, the children are skipped.
   */
  public <C extends TreeNode<NodeType>, D extends C> void collect(
      Predicate<? super C> predicate, Collection<D> matches) {
    // TODO: the semantics of this function are very strange. contains()
    // checks using .equals() on the nodes. In the case of literals, slotrefs
    // and maybe others, two different tree node objects can be equal and
    // this function would only return one of them. This is not intuitive.
    // We rely on these semantics to not have duplicate nodes. Investigate this.
    if (predicate.apply((C) this) && !matches.contains(this)) {
      matches.add((D) this);
      return;
    }
    for (NodeType child: children_) child.collect(predicate, matches);
  }

  /**
   * Add all nodes in the tree that are of class 'cl' to the list 'matches'.
   * This node is checked first, followed by its children in order. If the node
   * itself is of class 'cl', the children are skipped.
   */
  public <C extends TreeNode<NodeType>, D extends C> void collect(
      Class cl, Collection<D> matches) {
    if (cl.equals(getClass())) {
      matches.add((D) this);
      return;
    }
    for (NodeType child: children_) child.collect(cl, matches);
  }

  /**
   * Add all nodes in the tree that satisfy 'predicate' to the list 'matches'
   * This node is checked first, followed by its children in order. All nodes
   * that match in the subtree are added.
   */
  public <C extends TreeNode<NodeType>, D extends C> void collectAll(
      Predicate<? super C> predicate, List<D> matches) {
    if (predicate.apply((C) this)) matches.add((D) this);
    for (NodeType child: children_) child.collectAll(predicate, matches);
  }

  /**
   * For each expression in 'nodeList', collect all subexpressions satisfying 'predicate'
   * into 'matches'
   */
  public static <C extends TreeNode<C>, D extends C> void collect(
      Collection<C> nodeList, Predicate<? super C> predicate, Collection<D> matches) {
    for (C node: nodeList) node.collect(predicate, matches);
  }

  /**
   * For each expression in 'nodeList', collect all subexpressions of class 'cl'
   * into 'matches'
   */
  public static <C extends TreeNode<C>, D extends C> void collect(
      Collection<C> nodeList, Class cl, Collection<D> matches) {
    for (C node: nodeList) node.collect(cl, matches);
  }

  /**
   * Return true if this node or any of its children satisfy 'predicate'.
   */
  public <C extends TreeNode<NodeType>> boolean contains(
      Predicate<? super C> predicate) {
    if (predicate.apply((C) this)) return true;
    for (NodeType child: children_) if (child.contains(predicate)) return true;
    return false;
  }

  /**
   * Return true if this node or any of its children is an instance of class 'cl'.
   */
  public boolean contains(Class cl) {
    if (cl.equals(getClass())) return true;
    for (NodeType child: children_) if (child.contains(cl)) return true;
    return false;
  }

  /**
   * For each node in nodeList, return true if any subexpression satisfies
   * contains('predicate').
   */
  public static <C extends TreeNode<C>, D extends C> boolean contains(
      Collection<C> nodeList, Predicate<? super C> predicate) {
    for (C node: nodeList) if (node.contains(predicate)) return true;
    return false;
  }

  /**
   * Return true if any node in nodeList contains children of class cl.
   */
  public static <C extends TreeNode<C>> boolean contains(
      List<C> nodeList, Class cl) {
    for (C node: nodeList) if (node.contains(cl)) return true;
    return false;
  }

  /**
   * Returns the first node/child of class cl (depth-first traversal).
   */
  public <C extends NodeType> C findFirstOf(Class<C> cl) {
    if (this.getClass().equals(cl)) return (C) this;
    for (NodeType child: children_) {
      NodeType result = child.findFirstOf(cl);
      if (result != null) return (C) result;
    }
    return null;
  }

  /**
   * Visitor pattern accept method
   */
  public <C extends TreeNode<NodeType>> void accept(Visitor<C> visitor) {
    visitor.visit((C) this);
    for (NodeType p: children_) p.accept(visitor);
  }
}
