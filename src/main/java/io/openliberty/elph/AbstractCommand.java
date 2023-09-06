package io.openliberty.elph;

import io.openliberty.elph.bnd.Projects;
import io.openliberty.elph.io.IO;
import picocli.CommandLine;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toCollection;

abstract class AbstractCommand {
    @CommandLine.ParentCommand
    ElphCommand elph;
    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;
    @CommandLine.Mixin
    IO io;

    void addDeps(Collection<Path> projects) {
        var names = Projects.toNames(projects);
        elph.getCatalog().getRequiredProjectPaths(names).forEach(projects::add);
    }

    void addUsers(Collection<Path> projects) {
        var names = Projects.toNames(projects);
        elph.getCatalog().getDependentProjectPaths(names).forEach(projects::add);
    }

    /**
     * Removes (and returns) the projects already imported to Eclipse from the supplied collection.
     * @return the set of projects that were removed
     */
    Set<Path> removeImported(Collection<Path> projects) {
        var imported = elph.getEclipseProjects();
        var removed = projects.stream().filter(imported::contains).collect(toCollection(TreeSet::new));
        projects.removeAll(removed);
        return removed;
    }

    /**
     * Removes (and returns) the subset of projects that do not depend on any other projects in the supplied collection.
     * @return the set of projects that were removed
     */
    Set<Path> removeLeaves(Collection<Path> projects) {
        var leaves = elph.getCatalog().getLeavesOfSubset(projects);
        projects.removeAll(leaves);
        return leaves;
    }

    Set<Path> findProjects(Stream<String> patterns, boolean includeUsers) {
        var result = elph.getCatalog().findProjects(patterns).collect(toCollection(TreeSet::new));
        if (includeUsers) addUsers(result);
        return result;
    }
}
