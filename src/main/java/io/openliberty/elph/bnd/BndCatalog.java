/*
 * =============================================================================
 * Copyright (c) 2021 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 * =============================================================================
 */
package io.openliberty.elph.bnd;

import io.openliberty.elph.util.IO;
import org.apache.commons.collections4.MultiValuedMap;
import org.apache.commons.collections4.multimap.HashSetValuedHashMap;
import org.jgrapht.Graph;
import org.jgrapht.graph.AsSubgraph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.EdgeReversedGraph;
import org.jgrapht.graph.SimpleDirectedGraph;
import org.jgrapht.traverse.TopologicalOrderIterator;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.Spliterators;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static io.openliberty.elph.bnd.ProjectPaths.asNames;
import static java.util.Comparator.comparing;
import static java.util.Spliterator.ORDERED;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toCollection;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Collectors.toUnmodifiableSet;

public class BndCatalog {
    private static final String SAVE_FILE = "deps.save";
    public static final String SAVE_FILE_DESC = "dependency save file";

    private static<T> SimpleDirectedGraph<T, DefaultEdge> newGraph() {
        return new SimpleDirectedGraph<>(DefaultEdge.class);
    }

    final Path root;
    final IO io;
    final Path saveFile;
    final SimpleDirectedGraph<BndProject, DefaultEdge> digraph = newGraph();
    final Map<String, BndProject> nameIndex = new TreeMap<>();
    final MultiValuedMap<Path, BndProject> pathIndex = new HashSetValuedHashMap<>();
    volatile boolean bndQueried;

    public BndCatalog(Path bndWorkspace, IO io, Path workspaceSettingsDir) throws IOException {
        this.io = io;
        this.root = bndWorkspace;
        this.saveFile = workspaceSettingsDir.resolve(SAVE_FILE);
        // add the vertices
        try (var files = Files.list(bndWorkspace)) {
            files
                    .filter(Files::isDirectory)                            // for every subdirectory
                    .filter(p -> Files.exists(p.resolve("bnd.bnd"))) // that has a bnd file
                    .map(BndProject::new)                                  // create a Project object
                    .forEach(digraph::addVertex);                          // add it as a vertex to the graph
        }

        // index projects by name
        digraph.vertexSet().stream()
                .peek(p -> nameIndex.put(p.name, p))
                .peek(p -> pathIndex.put(p.root.getFileName(), p))
                .filter(BndProject::symbolicNameDiffersFromName)
                .forEach(p -> nameIndex.put(p.symbolicName, p));


        // index projects by name and by symbolic name as paths
        // (even if those paths don't exist)
        // to allow globbing searches on them
        nameIndex.forEach((name, project) -> pathIndex.put(Paths.get(name), project));

        // add the edges
        digraph.vertexSet().forEach(p -> p.initialDeps.stream()
                .map(nameIndex::get)
                .filter(Objects::nonNull)
                .filter(not(p::equals))
                .forEach(q -> digraph.addEdge(p, q)));

        // make everything depend on 'cnf'
        var cnf = nameIndex.get("cnf");
        digraph.vertexSet().stream().filter(not(cnf::equals)).forEach(p -> digraph.addEdge(p, cnf));

        // make some bundles depend on build.image
        var buildImage = nameIndex.get("build.image");
        digraph.vertexSet().stream()
                .filter(not(p -> p.isNoBundle))
                .filter(not(p -> p.publishWlpJarDisabled))
                .forEach(p -> digraph.addEdge(p, buildImage));

        // re-load deps if possible
        loadDeps();
    }

    private void queryBnd() {
        if (bndQueried) return;
        synchronized (this) {
            if (bndQueried) return;
            var bnd = new BndWorkspace(io, root, nameIndex::get);
            digraph.vertexSet().forEach(p -> bnd
                    .getBuildAndTestDependencies(p)
                    .filter(not(p::equals))
                    .forEach(q -> digraph.addEdge(p,q)));
            var text = digraph.edgeSet()
                    .stream()
                    .map(this::formatEdge)
                    .collect(joining("\n", "", "\n"));
            io.writeFile(SAVE_FILE_DESC, saveFile, text);
        }
    }

    public void reanalyze() {
        bndQueried = false;
        queryBnd();
    }

    private String formatEdge(DefaultEdge e) {
        return "%s -> %s".formatted(digraph.getEdgeSource(e), digraph.getEdgeTarget(e));
    }

    private void loadDeps() {
        if (!Files.exists(saveFile)) return;
        FileTime saveTime = IO.getLastModified(saveFile);
        Predicate<BndProject> isNewer = p -> saveTime.compareTo(p.timestamp) < 0;
        var newerCount = nameIndex.values()
                .stream()
                .filter(isNewer)
                .peek(p -> io.debugf("bnd file for %s is newer than save file %s", p, saveFile))
                .count();
        io.logf("%d projects have bnd files newer than %s", newerCount, saveFile);
        if (newerCount > 0) return;
        io.readFile(SAVE_FILE_DESC, saveFile, this::loadDep);
        bndQueried = true;
    }

    private void loadDep(String dep) {
        String[] parts = dep.split(" -> ");
        if (parts.length != 2) {
            io.warn("Failed to parse dependency", dep);
            return;
        }
        BndProject source = nameIndex.get(parts[0]);
        BndProject target = nameIndex.get(parts[1]);
        digraph.addEdge(source, target);
    }

    Path getProject(String name) { return find(name).root; }

    Stream<Path> allProjects() {
        return pathIndex.values().stream()
                .map(p -> p.root)
                .sorted()
                .distinct();
    }

    public Stream<Path> findProjects(String pattern) {
        var set = pathIndex.keySet().stream()
                // Use Java's globbing support to match paths
                .filter(FileSystems.getDefault().getPathMatcher("glob:" + pattern)::matches)
                // find all the projects indexed by each matching path
                .map(pathIndex::get)
                // create a single stream from all the found collections
                .flatMap(Collection::stream)
                .map(p -> p.root)
                // put the results into a set to eliminate duplicates
                .collect(toUnmodifiableSet());
        if (set.isEmpty()) io.warn("No project found matching pattern \"" + pattern + '"');
        return set.stream();
    }

    public Stream<Path> findProjects(Stream<String> patterns) {
        return patterns.flatMap(this::findProjects);
    }

    private BndProject find(String name) {
        BndProject result = nameIndex.get(name);
        if (null == result) throw new Error("No project found with name \"" + name + '"');
        return result;
    }

    public Set<Path> getLeavesOfSubset(Collection<Path> subset, int max) {
        queryBnd();
        assert max > 0;
        var nodes = asNames(subset).map(this::find).collect(toUnmodifiableSet());
        var subGraph = new AsSubgraph<>(digraph, nodes);
        var leaves = subGraph.vertexSet().stream()
                .filter(p -> subGraph.outgoingEdgesOf(p).size() == 0)
                .map(p -> p.root)
                .sorted()
                .limit(max)
                .collect(toCollection(TreeSet::new));
        io.debugf("getLeafProjects() found %d leaf projects", leaves.size());
        return leaves;
    }

    public Stream<Path> getRequiredProjectPaths(Collection<String> projectNames) {
        queryBnd();
        var deps = getProjectAndDependencySubgraph(projectNames);
        var rDeps = new EdgeReversedGraph<>(deps);
        var topo = new TopologicalOrderIterator<>(rDeps, comparing(p -> p.name));
        return stream(topo).map(p -> p.root);
    }

    public Stream<Path> inTopologicalOrder(Stream<Path> paths) {
        queryBnd();
        var projects = paths.map(ProjectPaths::toName).map(nameIndex::get).collect(toSet());
        var subGraph = new EdgeReversedGraph<>(new AsSubgraph<>(digraph, projects));
        var topo = new TopologicalOrderIterator<>(subGraph, comparing(p -> p.name));
        return stream(topo).map(p -> p.root);
    }

    public Stream<Path> getDependentProjectPaths(Collection<String> projectNames) {
        queryBnd();
        return projectNames.stream()
                .map(this::find)
                .map(digraph::incomingEdgesOf)
                .flatMap(Set::stream)
                .map(digraph::getEdgeSource)
                .map(p -> p.root)
                .distinct();
    }

    Graph<BndProject, ?> getProjectAndDependencySubgraph(Collection<String> projectNames) {
        queryBnd();
        // collect the named projects to start with
        var projects = projectNames.stream()
                .map(this::find)
                .filter(Objects::nonNull)
                .collect(toUnmodifiableSet());
        var results = new HashSet<BndProject>();
        // collect all known dependencies, breadth-first
        while (!projects.isEmpty()) {
            results.addAll(projects);
            projects = projects.stream()
                    .map(digraph::outgoingEdgesOf)
                    .flatMap(Set::stream)
                    .map(digraph::getEdgeTarget)
                    .filter(not(results::contains))
                    .collect(toUnmodifiableSet());
        }
        return new AsSubgraph<>(digraph, results);
    }

    private static <T> Stream<T> stream(Iterator<T> iterator) {
        var spl = Spliterators.spliteratorUnknownSize(iterator, ORDERED);
        return StreamSupport.stream(spl, false);
    }

    private static <T> Predicate<T> not(Predicate<T> predicate) { return t -> !predicate.test(t); }

    public String getProjectDetails(Path path) {
        String name = path.getFileName().toString();
        BndProject project = nameIndex.get(name);
        return project.details();
    }
}
