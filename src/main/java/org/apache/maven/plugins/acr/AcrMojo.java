/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.plugins.acr;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.input.XmlStreamReader;
import org.apache.maven.api.Project;
import org.apache.maven.api.Session;
import org.apache.maven.api.di.Inject;
import org.apache.maven.api.plugin.Log;
import org.apache.maven.api.plugin.MojoException;
import org.apache.maven.api.plugin.annotations.Mojo;
import org.apache.maven.api.plugin.annotations.Parameter;
import org.apache.maven.api.services.ProjectManager;
import org.apache.maven.shared.archiver.MavenArchiveConfiguration;
import org.apache.maven.shared.archiver.MavenArchiver;
import org.apache.maven.shared.archiver.MavenArchiverException;
import org.apache.maven.shared.filtering.FilterWrapper;
import org.apache.maven.shared.filtering.MavenFileFilter;
import org.apache.maven.shared.filtering.MavenFilteringException;
import org.apache.maven.shared.filtering.MavenResourcesExecution;
import org.codehaus.plexus.archiver.ArchiverException;
import org.codehaus.plexus.archiver.jar.JarArchiver;
import org.codehaus.plexus.archiver.util.DefaultFileSet;

/**
 * Build a JavaEE Application Client jar file from the current project.
 *
 * @author <a href="pablo@anahata-it.com">Pablo Rodriguez</a>
 * @author <a href="snicoll@apache.org">Stephane Nicoll</a>
 */
@Mojo(name = "acr", defaultPhase = "package")
public class AcrMojo implements org.apache.maven.api.plugin.Mojo {

    private static final String APP_CLIENT_XML = "META-INF/application-client.xml";

    private static final String[] DEFAULT_EXCLUDES = {APP_CLIENT_XML};

    /**
     * The directory for the generated jar.
     */
    @Parameter(defaultValue = "${project.build.directory}", required = true, readonly = true)
    private Path basedir;

    /**
     * Directory that resources are copied to during the build.<br/>
     * Starting with <b>3.0.0</b> the property has been renamed from <code>outputDirectory</code> to
     * <code>maven.acr.outputDirectory</code>.
     */
    @Parameter(property = "maven.acr.outputDirectory", defaultValue = "${project.build.outputDirectory}")
    private Path outputDirectory;

    /**
     * The name of the Application client JAR file to generate.
     */
    @Parameter(defaultValue = "${project.build.finalName}")
    private String jarName;

    /**
     * The files and directories to exclude from the main Application Client jar. Usage:
     * <p/>
     *
     * <pre>
     * &lt;excludes&gt;
     *   &lt;exclude&gt;**&#47;*DevOnly.class&lt;&#47;exclude&gt;
     * &lt;&#47;excludes&gt;
     * </pre>
     *
     * <br/>
     * Default exclusions: META-INF&#47;application-client.xml,
     */
    @Parameter
    private List<String> excludes;

    /**
     * The Maven project.
     */
    @Inject
    private Project project;

    /**
     * The archive configuration to use. See <a href="https://maven.apache.org/shared/maven-archiver/index.html">Maven
     * Archiver Reference</a>.
     */
    @Parameter
    private MavenArchiveConfiguration archive = new MavenArchiveConfiguration();

    /**
     * To escape interpolated value with windows path. c:\foo\bar will be replaced with c:\\foo\\bar.<br/>
     * Starting with <b>3.0.0</b> the property has been renamed from <code>acr.escapeBackslashesInFilePath</code> to
     * <code>maven.acr.escapeBackslashesInFilePath</code>.
     */
    @Parameter(property = "maven.acr.escapeBackslashesInFilePath", defaultValue = "false")
    private boolean escapeBackslashesInFilePath;

    /**
     * An expression preceded with this String won't be interpolated. \${foo} will be replaced with ${foo}.<br/>
     * Starting with <b>3.0.0</b> the property has been renamed from <code>acr.escapeString</code> to
     * <code>maven.acr.escapeString</code>.
     */
    @Parameter(property = "maven.acr.escapeString")
    private String escapeString;

    /**
     * To filter the deployment descriptor. Starting with <b>3.0.0</b> the property has been renamed from
     * <code>acr.filterDeploymentDescriptor</code> to <code>maven.acr.filterDeploymentDescriptor</code>.
     */
    @Parameter(property = "maven.acr.filterDeploymentDescriptor", defaultValue = "false")
    private boolean filterDeploymentDescriptor;

    /**
     * Filters (properties files) to include during the interpolation of the deployment descriptor.
     */
    @Parameter
    private List<String> filters;

    @Inject
    private Session session;

    @Inject
    private ProjectManager projectManager;

    @Inject
    private Log log;

    /**
     * Timestamp for reproducible output archive entries, either formatted as ISO 8601
     * <code>yyyy-MM-dd'T'HH:mm:ssXXX</code> or as an int representing seconds since the epoch (like
     * <a href="https://reproducible-builds.org/docs/source-date-epoch/">SOURCE_DATE_EPOCH</a>).
     *
     * @since 3.2.0
     */
    @Parameter(defaultValue = "${project.build.outputTimestamp}")
    private String outputTimestamp;

    @Inject
    private MavenFileFilter mavenFileFilter;

    @Override
    public void execute() throws MojoException {
        log.info("Building JavaEE Application client: " + jarName);

        Path jarFile = basedir.resolve(jarName + ".jar");

        MavenArchiver archiver = new MavenArchiver();

        archiver.setArchiver(new JarArchiver());

        archiver.setCreatedBy("Maven ACR Plugin", "org.apache.maven.plugins", "maven-acr-plugin");

        archiver.setOutputFile(jarFile.toFile());

        // configure for Reproducible Builds based on outputTimestamp value
        archiver.configureReproducibleBuild(outputTimestamp);

        try {
            String[] mainJarExcludes = mainJarExcludes(excludes);

            if (Files.exists(outputDirectory)) {
                archiver.getArchiver()
                        .addFileSet(DefaultFileSet.fileSet(outputDirectory.toFile())
                                .prefixed("")
                                .includeExclude(null, mainJarExcludes)
                                .includeEmptyDirs(true));
            } else {
                log.info("No content to add: " + outputDirectory + " does not exist");
            }

            Path deploymentDescriptor = outputDirectory.resolve(APP_CLIENT_XML);

            if (Files.exists(deploymentDescriptor)) {
                if (filterDeploymentDescriptor) {
                    log.debug("Filtering deployment descriptor.");
                    MavenResourcesExecution mavenResourcesExecution = new MavenResourcesExecution();
                    mavenResourcesExecution.setEscapeString(escapeString);
                    List<FilterWrapper> filterWrappers = mavenFileFilter.getDefaultFilterWrappers(
                            project, filters, escapeBackslashesInFilePath, session, mavenResourcesExecution);

                    // Create a temporary file that we can copy-and-filter
                    Path unfilteredDeploymentDescriptor = outputDirectory.resolve(APP_CLIENT_XML + ".unfiltered");
                    Files.copy(
                            deploymentDescriptor,
                            unfilteredDeploymentDescriptor,
                            StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.COPY_ATTRIBUTES);
                    mavenFileFilter.copyFile(
                            unfilteredDeploymentDescriptor,
                            deploymentDescriptor,
                            true,
                            filterWrappers,
                            getEncoding(unfilteredDeploymentDescriptor));
                    // Remove the temporary file
                    Files.deleteIfExists(unfilteredDeploymentDescriptor);
                }
                archiver.getArchiver().addFile(deploymentDescriptor.toFile(), APP_CLIENT_XML);
            }

            // create archive
            archiver.createArchive(session, project, archive);
        } catch (ArchiverException | MavenArchiverException e) {
            throw new MojoException(
                    "There was a problem creating the JavaEE Application Client archive: " + e.getMessage(), e);
        } catch (IOException | UncheckedIOException e) {
            throw new MojoException(
                    "There was an I/O problem creating the JavaEE Application Client archive: " + e.getMessage(), e);
        } catch (MavenFilteringException e) {
            throw new MojoException("There was a problem filtering the deployment descriptor: " + e.getMessage(), e);
        }

        projectManager.attachArtifact(project, project.getMainArtifact().orElseThrow(), jarFile);
    }

    /**
     * Returns the excludes for the main jar, leaving the configured list untouched.
     */
    static String[] mainJarExcludes(List<String> configured) {
        if (configured == null || configured.isEmpty()) {
            return DEFAULT_EXCLUDES.clone();
        }
        List<String> allExcludes = new ArrayList<>(configured);
        allExcludes.add(APP_CLIENT_XML);
        return allExcludes.toArray(new String[0]);
    }

    /**
     * Get the encoding from an XML file.
     *
     * @param xmlFile the XML file
     * @return the encoding of the XML file, or UTF-8 if it's not specified in the file
     * @throws IOException if an error occurred while reading the file
     */
    private String getEncoding(Path xmlFile) throws IOException {
        try (XmlStreamReader xmlReader =
                XmlStreamReader.builder().setPath(xmlFile).get()) {
            return xmlReader.getEncoding();
        }
    }
}
