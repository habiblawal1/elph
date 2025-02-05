package io.openliberty.elph.bnd;

import aQute.bnd.build.Project;
import aQute.bnd.build.Workspace;
import aQute.bnd.osgi.Constants;
import io.openliberty.elph.util.IO;

import java.io.File;
import java.nio.file.Path;
import java.util.function.Function;
import java.util.stream.Stream;

public class BndWorkspace {
    private final IO io;
    private final Function<Project, BndProject> lookup;
    private final Workspace workspace;

    public BndWorkspace(IO io, Path root, Function<String, BndProject> lookup) {
        this.io = io;
        Function<Project, String> getName = Project::getName;
        this.lookup = getName.andThen(lookup);
        File rootDir = root.toFile();
        /* Initialize the Bnd workspace */
        Workspace.setDriver(Constants.BNDDRIVER_ECLIPSE); // TODO: what should this be?
        Workspace.addGestalt(Constants.GESTALT_BATCH, null);
        try {
            workspace = new Workspace(rootDir, Workspace.CNFDIR);
            workspace.setOffline(true);
        } catch (Exception e) {
            throw io.error("Failed to parse bnd workspace", e);
        }
    }

    Stream<BndProject> getBuildAndTestDependencies(BndProject p) {
        Project project = workspace.getProject(p.name);
        return getBuildAndTestDependencies(project).map(lookup);
    }

    private Stream<Project> getBuildAndTestDependencies(Project p) {
        Stream<Project> buildDeps = Stream.empty();
        try {
            buildDeps = p.getBuildDependencies().stream();
        } catch (Exception e) {
            io.warn("Unable to retrieve build dependencies from bnd for project " + p.getName(), e);
        }
        Stream<Project> testDeps = Stream.empty();
        try {
            testDeps = p.getTestDependencies().stream();
        } catch (Exception e) {
            io.warn("Unable to retrieve test dependencies from bnd for project " + p.getName(), e);
        }
        return Stream.concat(
                buildDeps,
                testDeps
        );
    }
}
