/*
 * Copyright 2004,2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wso2.maven.p2;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.codehaus.plexus.util.FileUtils;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.internal.p2.director.app.DirectorApplication;
import org.wso2.maven.p2.generate.utils.FileManagementUtil;
import org.wso2.maven.p2.generate.utils.MavenUtils;
import org.wso2.maven.p2.generate.utils.P2Constants;
import org.wso2.maven.p2.generate.utils.P2Utils;

/**
 * Write environment information for the current build to file.
 */
@Mojo(name = "p2-profile-gen", defaultPhase = LifecyclePhase.PACKAGE)
public class ProfileGenMojo extends AbstractMojo {

    /**
     * Destination to which the features should be installed
     */
    @Parameter(name = "destination", required = true)
    private String destination;

    /**
     * target profile
     */
    @Parameter(name = "profile", required = true)
    private String profile;

    /**
     * URL of the Metadata Repository
     */
    @Parameter(name = "metadataRepository")
    private URL metadataRepository;

    /**
     * URL of the Artifact Repository
     */
    @Parameter(name = "artifactRepository")
    private URL artifactRepository;

    /**
     * List of features
     */
    @Parameter(name = "features", required = true)
    private ArrayList features;

    /**
     * Flag to indicate whether to delete old profile files
     */
    @Parameter(name = "deleteOldProfileFiles", defaultValue = "true")
    private boolean deleteOldProfileFiles = true;

    /**
     * Location of the p2 repository
     */
    @Parameter(name = "p2Repository")
    private P2Repository p2Repository;

    @Parameter(name = "project", defaultValue = "${project}")
    private MavenProject project;

    @Component
    private org.apache.maven.artifact.factory.ArtifactFactory artifactFactory;

    @Component
    private org.apache.maven.artifact.resolver.ArtifactResolver resolver;

    @Parameter(name = "localRepository", defaultValue = "${localRepository}")
    private org.apache.maven.artifact.repository.ArtifactRepository localRepository;

    @Parameter(name = "remoteRepositories", defaultValue = "${project.remoteArtifactRepositories}")
    private java.util.List remoteRepositories;

    /**
     * Equinox p2 configuration path
     */
    @Parameter(name = "p2Profile")
    private P2Profile p2Profile;

    /**
     * Maven ProjectHelper.
     */
    @Component
    private MavenProjectHelper projectHelper;

    /**
     * Kill the forked test process after a certain number of seconds. If set to 0, wait forever for
     * the process, never timing out.
     */
    @Parameter(name = "forkedProcessTimeoutInSeconds", property = "p2.timeout")
    private int forkedProcessTimeoutInSeconds;


    private File FOLDER_TARGET;
    private File FOLDER_TEMP;
    private File FOLDER_TEMP_REPO_GEN;
    private File FILE_FEATURE_PROFILE;
    private File p2AgentDir;

    private final String STREAM_TYPE_IN = "inputStream";
    private final String STREAM_TYPE_ERROR = "errorStream";

    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            if (profile == null){
                profile = P2Constants.DEFAULT_PROFILE_ID;
            }
            createAndSetupPaths();
            rewriteEclipseIni();
//          	verifySetupP2RepositoryURL();
            this.getLog().info("Running Equinox P2 Director Application");
            installFeatures(getIUsToInstall());
            //updating profile's config.ini p2.data.area property using relative path
            File profileConfigIni = FileManagementUtil.getProfileConfigIniFile(destination, profile);
            FileManagementUtil.changeConfigIniProperty(profileConfigIni, "eclipse.p2.data.area", "@config.dir/../../p2/");

            //deleting old profile files, if specified
            if (deleteOldProfileFiles) {
                deleteOldProfiles();
            }
        } catch (Exception e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
//        createArchive();
//        deployArtifact();
        performMopUp();
    }

    private String getIUsToInstall() throws MojoExecutionException {
        String installUIs = "";
        for (Object featureObj : features) {
            Feature f;
            if (featureObj instanceof Feature) {
                f = (Feature) featureObj;
            } else if (featureObj instanceof String) {
                f = Feature.getFeature(featureObj.toString());
            } else
                f = (Feature) featureObj;
            installUIs = installUIs + f.getId().trim() + "/" + f.getVersion().trim() + ",";
        }

        if (installUIs.length() == 0) {
            installUIs = installUIs.substring(0, installUIs.length() - 1);
        }
        return installUIs;
    }

    protected DirectorApplication getPublisherApplication() {
        return new DirectorApplication();
    }

    private void installFeatures(String installUIs) throws Exception {
        List<String> arguments = new ArrayList<>();

        addArguments(arguments, installUIs);

        Object result = getPublisherApplication().run(arguments.toArray(String[]::new));
        if (result != IApplication.EXIT_OK) {
            throw new MojoFailureException("P2 publisher return code was " + result);
        }
    }

    private void addArguments(List<String> arguments, String installUIs) throws IOException, MalformedURLException {
        arguments.add("-metadataRepository");
        arguments.add(metadataRepository.toExternalForm());
        arguments.add("-artifactRepository");
        arguments.add(artifactRepository.toExternalForm());
        arguments.add("-profileProperties");
        arguments.add("org.eclipse.update.install.features=true");
        arguments.add("-installIU");
        arguments.add(installUIs);
        arguments.add("-bundlepool");
        arguments.add(destination);
                //to support shared installation in carbon
        arguments.add("-shared");
        arguments.add(destination + File.separator + "p2");
                //target is set to a separate directory per Profile
        arguments.add("-destination");
        arguments.add(destination + File.separator + profile);
        arguments.add("-profile");
        arguments.add(profile.toString());
        arguments.add("-roaming");
    }

    public class InputStreamHandler implements Runnable {
        String streamType;
        InputStream inputStream;

        public InputStreamHandler(String name, InputStream is) {
            this.streamType = name;
            this.inputStream = is;
        }

        public void start() {
            Thread thread = new Thread(this);
            thread.start();
        }

        public void run() {
            try {
                InputStreamReader inputStreamReader = new InputStreamReader(inputStream);
                BufferedReader bufferedReader = new BufferedReader(inputStreamReader);

                while (true) {
                    String s = bufferedReader.readLine();
                    if (s == null) break;
                    if (STREAM_TYPE_IN.equals(streamType)) {
                        getLog().info(s);
                    } else if (STREAM_TYPE_ERROR.equals(streamType)) {
                        getLog().error(s);
                    }
                }
                inputStream.close();
            } catch (Exception ex) {
                getLog().error("Problem reading the " + streamType + ".", ex);
            }
        }

    }


    private void createAndSetupPaths() throws Exception {
        FOLDER_TARGET = new File(project.getBasedir(), "target");
        String timestampVal = String.valueOf((new Date()).getTime());
        FOLDER_TEMP = new File(FOLDER_TARGET, "tmp." + timestampVal);
        FOLDER_TEMP_REPO_GEN = new File(FOLDER_TEMP, "temp_repo");
        FILE_FEATURE_PROFILE = new File(FOLDER_TARGET, project.getArtifactId() + "-" + project.getVersion() + ".zip");


    }

    private void deleteOldProfiles() {
        if (!destination.endsWith("/")) {
            destination = destination + "/";
        }
        String profileFolderName = destination +
                "p2/org.eclipse.equinox.p2.engine/profileRegistry/" + profile + ".profile";

        File profileFolder = new File(profileFolderName);
        if (profileFolder.isDirectory()) {
            String[] profileFileList = profileFolder.list(new FilenameFilter() {
                public boolean accept(File dir, String name) {
                    return name.endsWith(".profile");
                }
            });

            Arrays.sort(profileFileList);

            //deleting old profile files
            for (int i = 0; i < (profileFileList.length - 1); i++) {
                File profileFile = new File(profileFolderName, profileFileList[i]);
                profileFile.delete();
            }
        }
    }

    private void rewriteEclipseIni(){
        File eclipseIni = null;
        String profileLocation = destination + File.separator + profile;
        // getting the file null.ini
        eclipseIni = new File(profileLocation + File.separator +"null.ini");
        if (eclipseIni.exists()) {
            rewriteFile(eclipseIni, profileLocation);
            return;
        }
        // null.ini does not exist. trying with eclipse.ini
        eclipseIni = new File(profileLocation + File.separator +"eclipse.ini");
        if (eclipseIni.exists()) {
            rewriteFile(eclipseIni, profileLocation);
            return;
        }
    }

    private  void rewriteFile(File file, String profileLocation) {
        file.delete();
        PrintWriter pw = null;
        try {
            pw = new PrintWriter(new FileWriter(file));
            pw.write("-install\n");
            pw.write(profileLocation);
            pw.flush();
        } catch (IOException e) {
            this.getLog().debug("Error while writing to file " + file.getName());
            e.printStackTrace();
        } finally {
            pw.close();
        }
    }

    private void deployArtifact() {
        if (FILE_FEATURE_PROFILE != null && FILE_FEATURE_PROFILE.exists()) {
            project.getArtifact().setFile(FILE_FEATURE_PROFILE);
            projectHelper.attachArtifact(project, "zip", null, FILE_FEATURE_PROFILE);
        }
    }

    private void performMopUp() {
        try {
            FileUtils.deleteDirectory(FOLDER_TEMP);
        } catch (Exception e) {
            getLog().warn(new MojoExecutionException("Unable complete mop up operation", e));
        }
    }
}
