package io.openliberty.elph;

import picocli.CommandLine.Command;

import static io.openliberty.elph.ElphCommand.TOOL_NAME;

@Command(name = "eclipse", description = "Start Eclipse with the specified workspace. This is a good test of your " + TOOL_NAME + " configuration, but it does not replace other ways of launching Eclipse.")
public class EclipseCommand extends AbstractCommand implements Runnable {
    @Override
    public void run() {
        elph.runExternal(elph.getEclipseCmd());
    }
}
