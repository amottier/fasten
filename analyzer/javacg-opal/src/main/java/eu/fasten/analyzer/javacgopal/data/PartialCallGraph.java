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

package eu.fasten.analyzer.javacgopal.data;

import com.google.common.collect.Lists;
import eu.fasten.analyzer.javacgopal.data.analysis.OPALClassHierarchy;
import eu.fasten.analyzer.javacgopal.data.analysis.OPALMethod;
import eu.fasten.analyzer.javacgopal.data.analysis.OPALType;
import eu.fasten.analyzer.javacgopal.data.exceptions.MissingArtifactException;
import eu.fasten.analyzer.javacgopal.data.exceptions.OPALException;
import eu.fasten.core.data.Constants;
import eu.fasten.core.data.ExtendedRevisionCallGraph;
import eu.fasten.core.data.ExtendedRevisionJavaCallGraph;
import eu.fasten.core.data.Graph;
import eu.fasten.core.data.JavaScope;
import eu.fasten.core.data.JavaType;
import eu.fasten.core.data.FastenURI;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.opalj.br.Method;
import org.opalj.br.ObjectType;
import org.opalj.br.analyses.Project;
import org.opalj.tac.cg.CallGraph;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.collection.JavaConverters;

/**
 * Call graphs that are not still fully resolved. i.e. isolated call graphs which within-artifact
 * calls (edges) are known as internal calls and Cross-artifact calls are known as external calls.
 */
public class PartialCallGraph {

    private static final Logger logger = LoggerFactory.getLogger(PartialCallGraph.class);

    private final Map<JavaScope, Map<FastenURI, JavaType>> classHierarchy;
    private final Graph graph;
    private final int nodeCount;

    /**
     * Given a file, algorithm and main class (in case of application package)
     * it creates a {@link PartialCallGraph} for it using OPAL.
     *
     * @param constructor call graph constructor
     */
    public PartialCallGraph(CallGraphConstructor constructor) throws OPALException {
        this.graph = new Graph();

        try {
            final var cha = createInternalCHA(constructor.getProject());

            createGraphWithExternalCHA(constructor.getCallGraph(), cha);

            this.nodeCount = cha.getNodeCount();
            this.classHierarchy = cha.asURIHierarchy(constructor.getProject().classHierarchy());
        } catch (Exception e) {
            if (e.getStackTrace().length > 0) {
                var stackTrace = e.getStackTrace()[0];
                if (stackTrace.toString().startsWith("org.opalj")) {
                    var opalException = new OPALException(
                            "Original error type: " + e.getClass().getSimpleName()
                                    + "; Original message: " + e.getMessage());
                    opalException.setStackTrace(e.getStackTrace());
                    throw opalException;
                }
            }
            throw e;
        }
    }

    public Map<JavaScope, Map<FastenURI, JavaType>> getClassHierarchy() {
        return classHierarchy;
    }

    public Graph getGraph() {
        return graph;
    }

    public int getNodeCount() {
        return nodeCount;
    }

    /**
     * Creates RevisionCallGraph using OPAL call graph generator for a given maven
     * coordinate. It also sets the forge to "mvn".
     *
     * @param coordinate maven coordinate of the revision to be processed
     * @param timestamp  timestamp of the revision release
     * @return RevisionCallGraph of the given coordinate.
     * @throws FileNotFoundException in case there is no jar file for the given coordinate on the
     *                               Maven central it throws this exception.
     */
    public static ExtendedRevisionJavaCallGraph createExtendedRevisionJavaCallGraph(
            final MavenCoordinate coordinate, final String mainClass,
            final String algorithm, final long timestamp)
            throws MissingArtifactException, OPALException {

        File file = null;
        try {
            file = new MavenCoordinate.MavenResolver().downloadArtifact(coordinate);
            final var opalCG = new CallGraphConstructor(file, mainClass, algorithm);

            final var partialCallGraph = new PartialCallGraph(opalCG);

            return new ExtendedRevisionJavaCallGraph(Constants.mvnForge, coordinate.getProduct(),
                    coordinate.getVersionConstraint(), timestamp,
                    partialCallGraph.getNodeCount(), Constants.opalGenerator,
                    partialCallGraph.getClassHierarchy(),
                    partialCallGraph.getGraph());
        } finally {
            if (file != null) {
                file.delete();
            }
        }
    }

    /**
     * Creates a class hierarchy for the given call graph's artifact with entries
     * only in internalCHA. ExternalCHA to be added at a later stage.
     *
     * @param project OPAL {@link Project}
     * @return class hierarchy for a given package
     * @implNote Inside {@link OPALType} all of the methods are indexed.
     */
    private OPALClassHierarchy createInternalCHA(final Project<?> project) {
        final Map<ObjectType, OPALType> result = new HashMap<>();
        final AtomicInteger methodNum = new AtomicInteger();

        final var objs = Lists.newArrayList(JavaConverters.asJavaIterable(project.allClassFiles()));
        objs.sort(Comparator.comparing(Object::toString));

        for (final var classFile : objs) {
            final var currentClass = classFile.thisType();
            final var methods = getMethodsMap(methodNum.get(),
                    JavaConverters.asJavaIterable(classFile.methods()));
            var namespace = OPALMethod.getPackageName(classFile.thisType());
            var filepath = namespace != null ? namespace.replace(".", "/") : "";
            final var type = new OPALType(methods,
                    OPALType.extractSuperClasses(project.classHierarchy(), currentClass),
                    OPALType.extractSuperInterfaces(project.classHierarchy(), currentClass),
                    classFile.sourceFile().isDefined()
                            ? filepath + "/" + classFile.sourceFile().get()
                            : "NotFound",
                    classFile.isPublic() ? "public" : "packagePrivate", classFile.isFinal());

            result.put(currentClass, type);
            methodNum.addAndGet(methods.size());
        }
        return new OPALClassHierarchy(result, new HashMap<>(), methodNum.get());
    }

    /**
     * Assign each method an id. Ids start from the the first parameter and increase by one number
     * for every method.
     *
     * @param methods Iterable of {@link Method} to get mapped to ids.
     * @return A map of passed methods and their ids.
     * @implNote Methods are keys of the result map and values are the generated Integer keys.
     */
    private Map<Method, Integer> getMethodsMap(final int keyStartsFrom,
                                               final Iterable<Method> methods) {
        final Map<Method, Integer> result = new HashMap<>();
        final AtomicInteger i = new AtomicInteger(keyStartsFrom);
        for (final var method : methods) {
            result.put(method, i.get());
            i.addAndGet(1);
        }
        return result;
    }

    /**
     * Given a call graph generated by OPAL and class hierarchy iterates over methods
     * declared in the package that call external methods and add them to externalCHA of
     * a call hierarchy. Build a graph for both internal and external calls in parallel.
     *
     * @param cg  call graph from OPAL generator
     * @param cha class hierarchy
     */
    private void createGraphWithExternalCHA(final CallGraph cg, final OPALClassHierarchy cha) {
        for (final var sourceDeclaration : JavaConverters
                .asJavaIterable(cg.reachableMethods().toIterable())) {

            if (sourceDeclaration.hasMultipleDefinedMethods()) {
                for (final var source : JavaConverters
                        .asJavaIterable(sourceDeclaration.definedMethods())) {
                    cha.appendGraph(source, cg.calleesOf(sourceDeclaration), graph);
                }
            } else if (sourceDeclaration.hasSingleDefinedMethod()) {
                cha.appendGraph(sourceDeclaration.definedMethod(),
                        cg.calleesOf(sourceDeclaration), graph);

            } else if (sourceDeclaration.isVirtualOrHasSingleDefinedMethod()) {
                cha.appendGraph(sourceDeclaration, cg.calleesOf(sourceDeclaration), graph);
            }
        }
    }
}
