package com.roylenferink.jenkins.plugins.workflow.libs;

import com.google.common.annotations.VisibleForTesting;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.FilePath;
import hudson.model.Node;
import hudson.model.Run;
import hudson.model.TaskListener;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.workflow.libs.LibraryRetriever;
import org.jenkinsci.plugins.workflow.libs.LibraryRetrieverDescriptor;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The goal of this plugin is to provide another way to retrieve shared libraries via the @Library declaration
 * in a Jenkinsfile.
 * <p>
 * The current official pipeline-groovy-lib-plugin only provides a way to retrieve shared libraries
 * through an SCM, such as Git, Subversion, etc.
 */
@Restricted(NoExternalUse.class)
public class NexusRetriever extends LibraryRetriever {

    private final String artifactDetails;
    private final String mavenHome;

    private final Node jenkins;

    /**
     * Constructor used for JUnits
     * @param jenkins The representation of the Jenkins server
     */
    @VisibleForTesting
    NexusRetriever(String artifactDetails, String mavenHome, Node jenkins) {
        this.jenkins = jenkins;
        this.artifactDetails = artifactDetails;
        this.mavenHome = mavenHome;
    }

    /**
     * Constructor
     */
    @DataBoundConstructor
    public NexusRetriever(@NonNull String artifactDetails, @NonNull String mavenHome) {
        this.jenkins = Jenkins.get();
        this.artifactDetails = artifactDetails;
        this.mavenHome = mavenHome;
    }

    public String getArtifactDetails() {
        return artifactDetails;
    }

    public String getMavenHome() {
        return mavenHome;
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
    public void retrieve(@NonNull String name, @NonNull String version, @NonNull FilePath target,
                         @NonNull Run<?, ?> run, @NonNull TaskListener listener) throws Exception {
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
    public void retrieve(@NonNull String name, @NonNull String version, boolean changelog,
                         @NonNull FilePath target, @NonNull Run<?, ?> run, @NonNull TaskListener listener)
            throws Exception {

        String artifactDetails = this.getArtifactDetails();
        if (artifactDetails == null || artifactDetails.isEmpty()) {
            throw new IOException("No artifact details specified for shared library: " + name + ":" + version);
        }

        artifactDetails = convertVersion(artifactDetails, name, version);
        String libDir = target.getRemote();
        listener.getLogger().println("=> Library directory for build: '" + libDir + "'");

        String mvnExecutable = null;

        if (this.getMavenHome() != null) {
            Path mavenExec = Paths.get(this.getMavenHome(), "bin", "mvn");
            if (Files.exists(mavenExec) && Files.isExecutable(mavenExec)) {
                mvnExecutable = mavenExec.toString();
            } else {
                listener.getLogger().println("=> Incorrect MAVEN_HOME specified, trying system Maven...");
            }
        }

        if (mvnExecutable == null) {
            // check if system 'mvn' available
            Process proc = Runtime.getRuntime().exec("which mvn");
            int exitVal = proc.waitFor();
            if (exitVal == 0) {
                mvnExecutable = getProcessOutput(proc);
            }
        }

        if (mvnExecutable == null) {
            throw new IOException("Unable to find mvn executable, set MAVEN_HOME in the plugin configuration or add mvn to the PATH");
        }

        listener.getLogger().println("=> Using " + mvnExecutable + " for downloading library");

        List<String> mvnCommand = new ArrayList<>();
        mvnCommand.add(mvnExecutable);
        mvnCommand.add("dependency:copy");
        mvnCommand.add("--update-snapshots");
        mvnCommand.add("-Dartifact=" + artifactDetails);
        mvnCommand.add("-DoutputDirectory=" + libDir);

        listener.getLogger().println("=> Executing " + StringUtils.join(mvnCommand, " "));

        ProcessBuilder pb = new ProcessBuilder(mvnCommand);
        Process process = pb.start();
        String output = getProcessOutput(process);
        int exitVal = process.waitFor();

        listener.getLogger().println("=> Downloading library from Nexus");
        listener.getLogger().print(output);

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


    private String getProcessOutput(Process p) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder output = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            output.append(line);
            output.append("\n");
        }
        reader.close();

        return output.toString();
    }

    private String convertVersion(String input, String name, String version) {
        Pattern p = Pattern.compile("\\$\\{library." + name + ".version\\}");
        Matcher match = p.matcher(input);

        return match.find() ? match.replaceAll(version) : input;
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
        public @NonNull
        String getDisplayName() {
            return "Nexus";
        }
    }

}
