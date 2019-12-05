package com.roylenferink.jenkins.plugins.workflow.libs;

import com.google.common.annotations.VisibleForTesting;
import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.slaves.WorkspaceList;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.libs.LibraryRetriever;
import org.jenkinsci.plugins.workflow.libs.LibraryRetrieverDescriptor;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The goal of this plugin is to provide another way to retrieve shared libraries via the @Library declaration
 * in a Jenkinsfile.
 * <p>
 * The current official plugin (workflow-cps-global-lib) does provide only a way to retrieve shared libraries
 * through a SCM, such as Git, Subversion, etc.
 */
@Restricted(NoExternalUse.class)
public class NexusRetriever extends LibraryRetriever {

    private final String artifactDetails;

    private final Node jenkins;

    /**
     * Constructor used for JUnits
     * @param jenkins The representation of the Jenkins server
     */
    @VisibleForTesting
    NexusRetriever(String artifactDetails, Node jenkins) {
        this.jenkins = jenkins;
        this.artifactDetails = artifactDetails;
    }

    /**
     * Constructor
     */
    @DataBoundConstructor
    public NexusRetriever(@Nonnull String artifactDetails) {
        this.jenkins = Jenkins.get();
        this.artifactDetails = artifactDetails;
    }

    public String getArtifactDetails() {
        return artifactDetails;
    }

    /**
     * Retrieves the shared library code. Prefer this version of the method.
     * <p>
     * Checks first if the library is accessible via a HEAD call. Then retrieves the shared library from Nexus.
     *
     * @param name     Name of the library (as specified in the Jenkinsfile @Library)
     * @param version  Version of the library (as specified in the Jenkinsfile @Library)
     * @param target   Where the code should be retrieved
     * @param run      Jenkins context
     * @param listener Only used to get the logger
     * @throws Exception if the file cannot be downloaded, archive can't be extracted, workspace is not writable
     */
    @Override
    public void retrieve(@Nonnull String name, @Nonnull String version, @Nonnull FilePath target,
                         @Nonnull Run<?, ?> run, @Nonnull TaskListener listener) throws Exception {
        retrieve(name, version, true, target, run, listener);
    }


    /**
     * Checks first if the library is accessible via a HEAD call. Then retrieves the shared library from Nexus.
     *
     * @param name      Name of the library (as specified in the Jenkinsfile @Library)
     * @param version   Version of the library (as specified in the Jenkinsfile @Library)
     * @param changelog Not used
     * @param target    Where the code should be retrieved
     * @param run       Jenkins context
     * @param listener  Only used to get the logger
     * @throws Exception if the file cannot be downloaded, archive can't be extracted, workspace is not writable
     */
    @Override
    public void retrieve(@Nonnull String name, @Nonnull String version, @Nonnull boolean changelog,
                         @Nonnull FilePath target, @Nonnull Run<?, ?> run, @Nonnull TaskListener listener)
            throws Exception {

        String artifactDetails = convertVersion(getArtifactDetails(), name, version);
        String libDir = getDownloadFolder(name, run).getRemote();
        listener.getLogger().println("=> Library directory for build: '" + libDir + "'");

        String mvnCommand = "mvn dependency:copy -Dartifact=" + artifactDetails + " -DoutputDirectory=" + libDir;
        Process process = Runtime.getRuntime().exec(mvnCommand);

        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        StringBuilder output = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            output.append(line);
            output.append("\n");
        }
        reader.close();

        int exitVal = process.waitFor();
        listener.getLogger().println("=> Downloading library from Nexus");
        listener.getLogger().print(output.toString());

        if (exitVal == 0) {
            String artifactId = artifactDetails.split(":")[1]; // 0 = groupId, 1 = artifactId, etc...
            listener.getLogger().println("=> Looking for artifact id: " + artifactId);
            Supplier<Stream<Path>> filesSupplier = () -> {
                try {
                    return Files.find(Paths.get(libDir), 1, ((path, basicFileAttributes) -> {
                        File file = path.toFile();
                        return !file.isDirectory() && file.getName().endsWith(".zip") && file.getName().startsWith(artifactId);
                    }));
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return null;
            };

            if (filesSupplier.get().count() == 1) {
                listener.getLogger().println("=> File found");

                final Path[] destinationPath = new Path[1];
                filesSupplier.get().forEach((p) -> destinationPath[0] = p);

                if (Files.exists(destinationPath[0])) {
                    FilePath filePath = new FilePath(destinationPath[0].toFile());

                    listener.getLogger().println("=> About to unzip " + filePath.getRemote());
                    filePath.unzip(target); // Extract the archive
                    filePath.delete();      // Delete the archive
                    listener.getLogger().println("=> Retrieved (" + artifactDetails + ")");
                } else {
                    throw new IOException("File " + destinationPath[0].toString() + " does not exist");
                }
            } else {
                throw new IOException("Unable to find library " + artifactId);
            }
        } else {
            throw new Exception("Error downloading artifact (" + exitVal + ")");
        }
    }

    private String convertVersion(String input, String name, String version) {
        Pattern p = Pattern.compile("\\$\\{library." + name + ".version\\}");
        Matcher match = p.matcher(input);

        return match.find() ? match.replaceAll(version) : input;
    }

    private FilePath getDownloadFolder(String name, Run<?, ?> run) throws IOException {
        FilePath dir;
        if (run.getParent() instanceof TopLevelItem) {
            FilePath baseWorkspace = jenkins.getWorkspaceFor((TopLevelItem) run.getParent());
            if (baseWorkspace == null) {
                throw new IOException("Unable to determine workspace for build, " + jenkins.getDisplayName());
            }
            dir = baseWorkspace.withSuffix(getFilePathSuffix() + "libs").child(name);
        } else {
            throw new AbortException("Cannot check out in non-top-level build");
        }
        return dir;
    }

    // There is WorkspaceList.tempDir but no API to make other variants
    private static String getFilePathSuffix() {
        return System.getProperty(WorkspaceList.class.getName(), "@");
    }

    // ---------- DESCRIPTOR ------------ //

    @Override
    public LibraryRetrieverDescriptor getDescriptor() {
        return super.getDescriptor();
    }

    @Symbol("nexus")
    @Extension
    @Restricted(NoExternalUse.class)
    public static class DescriptorImpl extends LibraryRetrieverDescriptor {
        @Override
        public @Nonnull
        String getDisplayName() {
            return "Nexus";
        }
    }

}
