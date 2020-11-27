/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.fasten.core.merge;

import eu.fasten.core.data.ArrayImmutableDirectedGraph;
import eu.fasten.core.data.Constants;
import eu.fasten.core.data.DirectedGraph;
import eu.fasten.core.data.FastenJavaURI;
import eu.fasten.core.data.graphdb.RocksDao;
import eu.fasten.core.data.metadatadb.codegen.tables.Callables;
import eu.fasten.core.data.metadatadb.codegen.tables.Edges;
import eu.fasten.core.data.metadatadb.codegen.tables.Modules;
import eu.fasten.core.data.metadatadb.codegen.tables.PackageVersions;
import eu.fasten.core.data.metadatadb.codegen.tables.Packages;
import eu.fasten.core.data.metadatadb.codegen.udt.records.ReceiverRecord;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.jgrapht.Graphs;
import org.jgrapht.graph.DefaultDirectedGraph;
import org.jgrapht.graph.DefaultEdge;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.json.JSONObject;
import org.rocksdb.RocksDBException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatabaseMerger {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseMerger.class);

//    private final DirectedGraph callGraphData;
    private final Map<String, Set<String>> universalParents;
    private final Map<String, Set<String>> universalChildren;
    private final Map<String, Map<String, Set<Long>>> typeDictionary;
    private final DSLContext dbContext;
    private final RocksDao rocksDao;
//    private final Map<Long, Node> typeMap;
//    private final List<Arc> arcs;

    /**
     * Create instance of database merger.
     *
     * @param dependencySet dependencies present in the resolution
     * @param dbContext    DSL context
     * @param rocksDao     rocks DAO
     */
    public DatabaseMerger(final List<String> dependencySet,
                          final DSLContext dbContext, final RocksDao rocksDao) {
        this.dbContext = dbContext;
        this.rocksDao = rocksDao;
        final var dependenciesIds = getDependenciesIds(dependencySet, dbContext);
        final var universalCHA = createUniversalCHA(dependenciesIds, dbContext, rocksDao);

        this.universalParents = universalCHA.getLeft();
        this.universalChildren = universalCHA.getRight();
        this.typeDictionary = createTypeDictionary(dependenciesIds, dbContext, rocksDao);

    }

    /**
     * Single arc containing source and target IDs and a list of receivers.
     */
    private static class Arc {
        private final Long source;
        private final Long target;
        private final ReceiverRecord[] receivers;

        /**
         * Create new Arc instance.
         *
         * @param source    source ID
         * @param target    target ID
         * @param receivers list of receivers
         */
        public Arc(final Long source, final Long target, final ReceiverRecord[] receivers) {
            this.source = source;
            this.target = target;
            this.receivers = receivers;
        }
    }

    /**
     * Node containing method signature and type information.
     */
    private static class Node {
        private final String signature;
        private final String typeUri;

        /**
         * Create new Node instance.
         *
         * @param uri fastenURI
         */
        public Node(final FastenJavaURI uri) {
            this.typeUri = "/" + uri.getNamespace() + "/" + uri.getClassName();
            this.signature = StringUtils.substringAfter(uri.getEntity(), ".");
        }

        /**
         * Check if the given method is a constructor.
         *
         * @return true, if the method is constructor
         */
        public boolean isConstructor() {
            return signature.startsWith("<init>");
        }

        /**
         * Get full fastenURI.
         *
         * @return fastenURI
         */
        public String getUri() {
            return typeUri + "." + signature;
        }
    }

    /**
     * Merges a call graph with its dependencies using CHA algorithm.
     *
     * @return merged call graph
     */
    public DirectedGraph mergeWithCHA(final String artifact) {

        final var callGraphData = fetchCallGraphData(artifact, dbContext, rocksDao);
        final var typeMap = createTypeMap(callGraphData, dbContext);
        final var arcs = getArcs(callGraphData, dbContext);

        var result = new ArrayImmutableDirectedGraph.Builder();

        final long startTime = System.currentTimeMillis();
        for (final var arc : arcs) {
            if (callGraphData.isExternal(arc.target)) {
                resolve(result, arc, typeMap.get(arc.target), false);
            } else {
                resolve(result, arc, typeMap.get(arc.source), callGraphData.isExternal(arc.source));
            }
        }
        logger.info("Stitched in {} seconds", new DecimalFormat("#0.000")
                .format((System.currentTimeMillis() - startTime) / 1000d));
        return result.build();
    }

    /**
     * Resolve an external call.
     *
     * @param result     graph with resolved calls
     * @param arc        source, target and receivers information
     * @param node       type and method information
     * @param isCallback true, if a given arc is a callback
     */
    private void resolve(final ArrayImmutableDirectedGraph.Builder result,
                         final Arc arc,
                         final Node node,
                         final boolean isCallback) {
        if (node.isConstructor()) {
            resolveInitsAndConstructors(result, arc, node, isCallback);
        }

        for (var entry : arc.receivers) {
            var receiverTypeUri = entry.component3();
            var type = entry.component2().getLiteral();
            switch (type) {
                case "virtual":
                case "interface":
                    final var types = universalChildren.get(receiverTypeUri);
                    if (types != null) {
                        for (final var depTypeUri : types) {
                            for (final var target : typeDictionary.getOrDefault(depTypeUri,
                                    new HashMap<>()).getOrDefault(node.signature, new HashSet<>())) {
                                addEdge(result, arc.source, target, isCallback);
                            }
                        }
                    }
                    break;
                case "special":
                    resolveInitsAndConstructors(result, arc, node, isCallback);
                    break;
                case "dynamic":
                    logger.warn("OPAL didn't rewrite the dynamic");
                    break;
                default:
                    for (final var target : typeDictionary.getOrDefault(receiverTypeUri,
                            new HashMap<>()).getOrDefault(node.signature, new HashSet<>())) {
                        addEdge(result, arc.source, target, isCallback);
                    }
                    break;
            }

        }
    }

    /**
     * Resolves constructors.
     *
     * @param result     {@link DirectedGraph} with resolved calls
     * @param arc        source, target and receivers information
     * @param node       type and method information
     * @param isCallback true, if a given arc is a callback
     */
    private void resolveInitsAndConstructors(final ArrayImmutableDirectedGraph.Builder result,
                                             final Arc arc,
                                             final Node node,
                                             final boolean isCallback) {
        final var typeList = universalParents.get(node.typeUri);
        if (typeList != null) {
            for (final var superTypeUri : typeList) {
                for (final var target : typeDictionary.getOrDefault(superTypeUri,
                        new HashMap<>()).getOrDefault(node.signature, new HashSet<>())) {
                    addEdge(result, arc.source, target, isCallback);
                }
                var superSignature = node.signature.replace("<init>", "<clinit>");
                for (final var target : typeDictionary.getOrDefault(superTypeUri,
                        new HashMap<>()).getOrDefault(superSignature, new HashSet<>())) {
                    addEdge(result, arc.source, target, isCallback);
                }
            }
        }
    }

    /**
     * Retrieve external calls and constructor calls from a call graph.
     *
     * @param callGraphData call graph
     * @param dbContext     DSL context
     * @return list of external and constructor calls
     */
    private List<Arc> getArcs(final DirectedGraph callGraphData, final DSLContext dbContext) {
        var arcsList = new ArrayList<Arc>();
        Condition arcsCondition = null;
        for (var source : callGraphData.nodes()) {
            for (var target : callGraphData.successors(source)) {
                if (callGraphData.isExternal(target)
                        || callGraphData.isExternal(source)
                        || source.equals(target)) {
                    if (arcsCondition == null) {
                        arcsCondition = Edges.EDGES.SOURCE_ID.eq(source)
                                .and(Edges.EDGES.TARGET_ID.eq(target));
                    } else {
                        arcsCondition = arcsCondition.or(Edges.EDGES.SOURCE_ID.eq(source)
                                .and(Edges.EDGES.TARGET_ID.eq(target)));
                    }
                }
            }
        }
        dbContext.select(Edges.EDGES.SOURCE_ID, Edges.EDGES.TARGET_ID, Edges.EDGES.RECEIVERS)
                .from(Edges.EDGES)
                .where(arcsCondition)
                .fetch()
                .forEach(arc -> arcsList.add(new Arc(arc.value1(), arc.value2(), arc.value3())));
        return arcsList;
    }

    /**
     * Retrieve a call graph from a graph database given a maven coordinate.
     *
     * @param dbContext DSL context
     * @param rocksDao  rocks DAO
     * @return call graph
     */
    private DirectedGraph fetchCallGraphData(final String artifact, final DSLContext dbContext,
                                             final RocksDao rocksDao) {
        var packageName = artifact.split(":")[0] + ":" + artifact.split(":")[1];
        var version = artifact.split(":")[2];

        var artifactId = dbContext
                .select(PackageVersions.PACKAGE_VERSIONS.ID)
                .from(PackageVersions.PACKAGE_VERSIONS).join(Packages.PACKAGES)
                .on(PackageVersions.PACKAGE_VERSIONS.PACKAGE_ID.eq(Packages.PACKAGES.ID))
                .where(PackageVersions.PACKAGE_VERSIONS.VERSION.eq(version))
                .and(Packages.PACKAGES.PACKAGE_NAME.eq(packageName))
                .and(Packages.PACKAGES.FORGE.eq(Constants.mvnForge))
                .fetchOne()
                .component1();

        DirectedGraph callGraphData = null;
        try {
            callGraphData = rocksDao.getGraphData(artifactId);
        } catch (RocksDBException e) {
            e.printStackTrace();
        }

        return callGraphData;
    }

    /**
     * Create a mapping from callable IDs to {@link Node}.
     *
     * @param callGraphData call graph
     * @param dbContext     DSL context
     * @return a map of callable IDs and {@link Node}
     */
    private Map<Long, Node> createTypeMap(final DirectedGraph callGraphData, final DSLContext dbContext) {
        var typeMap = new HashMap<Long, Node>();
        var nodesIds = callGraphData.nodes();
        var callables = dbContext
                .select(Callables.CALLABLES.ID, Callables.CALLABLES.FASTEN_URI)
                .from(Callables.CALLABLES)
                .where(Callables.CALLABLES.ID.in(nodesIds))
                .fetch();

        callables.forEach(callable -> typeMap.put(callable.value1(),
                new Node(FastenJavaURI.create(callable.value2()).decanonicalize())));
        return typeMap;
    }

    /**
     * Create a mapping from types and method signatures to callable IDs.
     *
     * @param dependenciesIds IDs of dependencies
     * @param dbContext       DSL context
     * @param rocksDao        rocks DAO
     * @return a type dictionary
     */
    private Map<String, Map<String, Set<Long>>> createTypeDictionary(
            final Set<Long> dependenciesIds, final DSLContext dbContext, final RocksDao rocksDao) {
        final long startTime = System.currentTimeMillis();
        var result = new HashMap<String, Map<String, Set<Long>>>();

        var callables = getCallables(dependenciesIds, rocksDao);

        dbContext.select(Callables.CALLABLES.FASTEN_URI, Callables.CALLABLES.ID)
                .from(Callables.CALLABLES)
                .where(Callables.CALLABLES.ID.in(callables))
                .fetch()
                .forEach(callable -> {
                    var node = new Node(FastenJavaURI.create(callable.value1()).decanonicalize());
                    result.putIfAbsent(node.typeUri, new HashMap<>());
                    var type = result.get(node.typeUri);
                    var newestSet = new HashSet<Long>();
                    newestSet.add(callable.value2());
                    type.merge(node.signature, newestSet, (old, newest) -> {
                        old.addAll(newest);
                        return old;
                    });
                });

        logger.info("Created the type dictionary with {} types in {} seconds", result.size(),
                new DecimalFormat("#0.000")
                        .format((System.currentTimeMillis() - startTime) / 1000d));

        return result;
    }

    /**
     * Create a universal class hierarchy from all dependencies.
     *
     * @param dependenciesIds IDs of dependencies
     * @param dbContext       DSL context
     * @param rocksDao        rocks DAO
     * @return universal CHA
     */
    private Pair<Map<String, Set<String>>, Map<String, Set<String>>> createUniversalCHA(
            final Set<Long> dependenciesIds, final DSLContext dbContext, final RocksDao rocksDao) {
        final long startTime = System.currentTimeMillis();
        var universalCHA = new DefaultDirectedGraph<String, DefaultEdge>(DefaultEdge.class);

        var callables = getCallables(dependenciesIds, rocksDao);

        var modulesIds = dbContext
                .select(Callables.CALLABLES.MODULE_ID)
                .from(Callables.CALLABLES)
                .where(Callables.CALLABLES.ID.in(callables))
                .fetch();

        var modules = dbContext
                .select(Modules.MODULES.NAMESPACE, Modules.MODULES.METADATA)
                .from(Modules.MODULES)
                .where(Modules.MODULES.ID.in(modulesIds))
                .fetch();

        for (var callable : modules) {
            if (!universalCHA.containsVertex(callable.value1())) {
                universalCHA.addVertex(callable.value1());
            }

            var superClasses = new JSONObject(callable.value2().data())
                    .getJSONArray("superClasses").toList();
            var superInterfaces = new JSONObject(callable.value2().data())
                    .getJSONArray("superInterfaces").toList();
            addSuperTypes(universalCHA, callable.value1(), superClasses);
            addSuperTypes(universalCHA, callable.value1(), superInterfaces);
        }

        final Map<String, Set<String>> universalParents = new HashMap<>();
        final Map<String, Set<String>> universalChildren = new HashMap<>();
        for (final var type : universalCHA.vertexSet()) {

            final var children = new HashSet<>(Collections.singletonList(type));
            children.addAll(getAllChildren(universalCHA, type));
            universalChildren.put(type, children);

            final var parents = new HashSet<>(Collections.singletonList(type));
            parents.addAll(getAllParents(universalCHA, type));
            universalParents.put(type, parents);
        }

        logger.info("Created the Universal CHA with {} vertices in {}",
                universalCHA.vertexSet().size(),
                new DecimalFormat("#0.000")
                        .format((System.currentTimeMillis() - startTime) / 1000d));

        return ImmutablePair.of(universalParents, universalChildren);
    }

    /**
     * Get all parents of a given type.
     *
     * @param graph universal CHA
     * @param type  type uri
     * @return list of types parents
     */
    private List<String> getAllParents(final DefaultDirectedGraph<String, DefaultEdge> graph,
                                       final String type) {
        final var children = Graphs.predecessorListOf(graph, type);
        final List<String> result = new ArrayList<>(children);
        for (final var child : children) {
            result.addAll(getAllParents(graph, child));
        }
        return result;
    }

    /**
     * Get all children of a given type.
     *
     * @param graph universal CHA
     * @param type  type uri
     * @return list of types children
     */
    private List<String> getAllChildren(final DefaultDirectedGraph<String, DefaultEdge> graph,
                                        final String type) {
        final var children = Graphs.successorListOf(graph, type);
        final List<String> result = new ArrayList<>(children);
        for (final var child : children) {
            result.addAll(getAllChildren(graph, child));
        }
        return result;
    }

    /**
     * Get callables from dependencies.
     *
     * @param dependenciesIds dependencies IDs
     * @param rocksDao        rocks DAO
     * @return list of callables
     */
    private List<Long> getCallables(final Set<Long> dependenciesIds, final RocksDao rocksDao) {
        var callables = new ArrayList<Long>();
        for (var id : dependenciesIds) {
            try {
                var cg = rocksDao.getGraphData(id);
                var nodes = cg.nodes();
                nodes.removeAll(cg.externalNodes());
                callables.addAll(nodes);
            } catch (RocksDBException | NullPointerException e) {
                logger.error("Couldn't retrieve a call graph with ID: {}", id);
            }
        }
        return callables;
    }

    /**
     * Get dependencies IDs from a metadata database.
     *
     * @param dbContext DSL context
     * @return set of IDs of dependencies
     */
    private Set<Long> getDependenciesIds(final List<String> dependencySet,
                                         final DSLContext dbContext) {
        var coordinates = new HashSet<>(dependencySet);

        Condition depCondition = null;

        for (var dependency : coordinates) {
            var packageName = dependency.split(":")[0] + ":" + dependency.split(":")[1];
            var version = dependency.split(":")[2];

            if (depCondition == null) {
                depCondition = Packages.PACKAGES.PACKAGE_NAME.eq(packageName)
                        .and(PackageVersions.PACKAGE_VERSIONS.VERSION.eq(version));
            } else {
                depCondition = depCondition.or(Packages.PACKAGES.PACKAGE_NAME.eq(packageName)
                        .and(PackageVersions.PACKAGE_VERSIONS.VERSION.eq(version)));
            }
        }

        return dbContext
                .select(PackageVersions.PACKAGE_VERSIONS.ID)
                .from(PackageVersions.PACKAGE_VERSIONS).join(Packages.PACKAGES)
                .on(PackageVersions.PACKAGE_VERSIONS.PACKAGE_ID.eq(Packages.PACKAGES.ID))
                .where(depCondition)
                .and(Packages.PACKAGES.FORGE.eq(Constants.mvnForge))
                .fetch()
                .intoSet(PackageVersions.PACKAGE_VERSIONS.ID);
    }

    /**
     * Add super classes and interfaces to the universal CHA
     *
     * @param result      universal CHA graph
     * @param sourceType  source type
     * @param targetTypes list of target target types
     */
    private void addSuperTypes(final DefaultDirectedGraph<String, DefaultEdge> result,
                               final String sourceType,
                               final List<Object> targetTypes) {
        for (final var superClass : targetTypes) {
            if (!result.containsVertex((String) superClass)) {
                result.addVertex((String) superClass);
            }
            if (!result.containsEdge(sourceType, (String) superClass)) {
                result.addEdge((String) superClass, sourceType);
            }
        }
    }

    /**
     * Add a resolved edge to the {@link DirectedGraph}.
     *
     * @param result     graph with resolved calls
     * @param source     source callable ID
     * @param target     target callable ID
     * @param isCallback true, if a given arc is a callback
     */
    private void addEdge(final ArrayImmutableDirectedGraph.Builder result,
                         final Long source, final Long target, final boolean isCallback) {
        try {
            result.addExternalNode(source);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            result.addExternalNode(target);
        } catch (IllegalArgumentException ignored) {
        }
        try {
            if (isCallback) {
                result.addArc(target, source);
            } else {
                result.addArc(source, target);
            }
        } catch (IllegalArgumentException ignored) {
        }
    }
}
