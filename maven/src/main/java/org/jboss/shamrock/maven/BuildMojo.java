/*
 * Copyright 2018 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jboss.shamrock.maven;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.eclipse.microprofile.config.Config;
import org.jboss.builder.BuildResult;
import org.jboss.shamrock.deployment.ClassOutput;
import org.jboss.shamrock.deployment.ShamrockAugmentor;
import org.jboss.shamrock.deployment.builditem.BytecodeTransformerBuildItem;
import org.jboss.shamrock.deployment.builditem.MainClassBuildItem;
import org.jboss.shamrock.deployment.builditem.substrate.SubstrateOutputBuildItem;
import org.jboss.shamrock.deployment.index.ResolvedArtifact;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.SmallRyeConfigProviderResolver;

@Mojo(name = "build", defaultPhase = LifecyclePhase.PREPARE_PACKAGE, requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class BuildMojo extends AbstractMojo {

    private static final String DEPENDENCIES_RUNTIME = "dependencies.runtime";
    private static final String PROVIDED = "provided";
    /**
     * The directory for compiled classes.
     */
    @Parameter(readonly = true, required = true, defaultValue = "${project.build.outputDirectory}")
    private File outputDirectory;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    /**
     * The directory for classes generated by processing.
     */
    @Parameter(defaultValue = "${project.build.directory}/wiring-classes")
    private File wiringClassesDirectory;

    @Parameter(defaultValue = "${project.build.directory}")
    private File buildDir;
    /**
     * The directory for library jars
     */
    @Parameter(defaultValue = "${project.build.directory}/lib")
    private File libDir;

    @Parameter(defaultValue = "${project.build.finalName}")
    private String finalName;

    @Parameter(defaultValue = "org.jboss.shamrock.runner.GeneratedMain")
    private String mainClass;

    @Parameter(defaultValue = "true")
    private boolean useStaticInit;

    @Parameter(defaultValue = "false")
    private boolean uberJar;

    public BuildMojo() {
        MojoLogger.logSupplier = this::getLog;
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        //first lets look for some config, as it is not on the current class path
        //and we need to load it to run the build process
        File config = new File(outputDirectory, "META-INF/microprofile-config.properties");
        if(config.exists()) {
            try {
                Config built = SmallRyeConfigProviderResolver.instance().getBuilder()
                        .addDefaultSources()
                        .addDiscoveredConverters()
                        .addDiscoveredSources()
                        .withSources(new PropertiesConfigSource(config.toURL())).build();
                SmallRyeConfigProviderResolver.instance().registerConfig(built, Thread.currentThread().getContextClassLoader());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }


        libDir.mkdirs();
        wiringClassesDirectory.mkdirs();
        try {
            StringBuilder classPath = new StringBuilder();
            List<String> problems = new ArrayList<>();
            Set<String> whitelist = new HashSet<>();
            for (Artifact a : project.getArtifacts()) {
                if (!"jar".equals(a.getType())) {
                    continue;
                }
                try (ZipFile zip = openZipFile(a)) {
                    if (!a.getScope().equals(PROVIDED) && zip.getEntry("META-INF/services/org.jboss.shamrock.deployment.ShamrockSetup") != null) {
                        problems.add("Artifact " + a + " is a deployment artifact, however it does not have scope required. This will result in unnecessary jars being included in the final image");
                    }
                    ZipEntry deps = zip.getEntry(DEPENDENCIES_RUNTIME);
                    if (deps != null) {
                        whitelist.add(a.getDependencyConflictId());
                        try (InputStream in = zip.getInputStream(deps)) {
                            BufferedReader reader = new BufferedReader(new InputStreamReader(in));
                            String line;
                            while ((line = reader.readLine()) != null) {
                                String[] parts = line.trim().split(":");
                                if (parts.length < 5) {
                                    continue;
                                }
                                StringBuilder sb = new StringBuilder();
                                //the last two bits are version and scope
                                //which we don't want
                                for (int i = 0; i < parts.length - 2; ++i) {
                                    if (i > 0) {
                                        sb.append(':');
                                    }
                                    sb.append(parts[i]);
                                }
                                whitelist.add(sb.toString());
                            }
                        }
                    }

                }
            }
            if (!problems.isEmpty()) {
                //TODO: add a config option to just log an error instead
                throw new MojoFailureException(problems.toString());
            }
            Set<String> seen = new HashSet<>();
            try (ZipOutputStream runner = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(new File(buildDir, finalName + "-runner.jar"))))) {
                Map<String, List<byte[]>> services = new HashMap<>();


                for (Artifact a : project.getArtifacts()) {
                    if (a.getScope().equals(PROVIDED) && !whitelist.contains(a.getDependencyConflictId())) {
                        continue;
                    }
                    if (a.getArtifactId().equals("svm") && a.getGroupId().equals("com.oracle.substratevm")) {
                        continue;
                    }
                    final File artifactFile = a.getFile();
                    if (uberJar) {
                        try (ZipInputStream in = new ZipInputStream(new FileInputStream(artifactFile))) {
                            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                                if (e.getName().startsWith("META-INF/services/") && e.getName().length() > 18) {
                                    services.computeIfAbsent(e.getName(), (u) -> new ArrayList<>()).add(read(in));
                                    continue;
                                } else if (e.getName().equals("META-INF/MANIFEST.MF")) {
                                    continue;
                                }
                                if (!seen.add(e.getName())) {
                                    if (!e.getName().endsWith("/")) {
                                        getLog().warn("Duplicate entry " + e.getName() + " entry from " + a + " will be ignored");
                                    }
                                    continue;
                                }
                                runner.putNextEntry(new ZipEntry(e.getName()));
                                doCopy(runner, in);
                            }
                        }
                    } else {
                        final String fileName = a.getGroupId() + "." + artifactFile.getName();
                        final Path targetPath = libDir.toPath().resolve(fileName);

                        Files.copy(artifactFile.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
                        classPath.append(" lib/" + fileName);
                    }
                }

                List<ResolvedArtifact> artifactList = new ArrayList<>();
                List<URL> classPathUrls = new ArrayList<>();
                for (Artifact artifact : project.getArtifacts()) {
                    classPathUrls.add(artifact.getFile().toURL());
                    artifactList.add(new ResolvedArtifact(artifact.getGroupId(), artifact.getArtifactId(), artifact.getVersion(), artifact.getClassifier(), Paths.get(artifact.getFile().getAbsolutePath())));
                }

                //we need to make sure all the deployment artifacts are on the class path
                //to do this we need to create a new class loader to actually use for the runner
                List<URL> cpCopy = new ArrayList<>();

                cpCopy.add(outputDirectory.toURL());
                cpCopy.addAll(classPathUrls);

                URLClassLoader runnerClassLoader = new URLClassLoader(cpCopy.toArray(new URL[0]), getClass().getClassLoader());
                ClassOutput classOutput = new ClassOutput() {
                    @Override
                    public void writeClass(boolean applicationClass, String className, byte[] data) throws IOException {
                        String location = className.replace('.', '/');
                        File file = new File(wiringClassesDirectory, location + ".class");
                        file.getParentFile().mkdirs();
                        try (FileOutputStream out = new FileOutputStream(file)) {
                            out.write(data);
                        }
                    }

                    @Override
                    public void writeResource(String name, byte[] data) throws IOException {
                        File file = new File(wiringClassesDirectory, name);
                        file.getParentFile().mkdirs();
                        try (FileOutputStream out = new FileOutputStream(file)) {
                            out.write(data);
                        }
                    }

                };


                ClassLoader old = Thread.currentThread().getContextClassLoader();
                BuildResult result;
                try {
                    Thread.currentThread().setContextClassLoader(runnerClassLoader);

                    ShamrockAugmentor.Builder builder = ShamrockAugmentor.builder();
                    builder.setRoot(outputDirectory.toPath());
                    builder.setClassLoader(runnerClassLoader);
                    builder.setOutput(classOutput);
                    builder.addFinal(BytecodeTransformerBuildItem.class)
                            .addFinal(MainClassBuildItem.class)
                            .addFinal(SubstrateOutputBuildItem.class);
                    result = builder.build().run();
                } finally {
                    Thread.currentThread().setContextClassLoader(old);
                }

                Map<String, List<BiFunction<String, ClassVisitor, ClassVisitor>>> bytecodeTransformers = new HashMap<>();
                List<BytecodeTransformerBuildItem> bytecodeTransformerBuildItems = result.consumeMulti(BytecodeTransformerBuildItem.class);
                if (!bytecodeTransformerBuildItems.isEmpty()) {
                    for (BytecodeTransformerBuildItem i : bytecodeTransformerBuildItems) {
                        bytecodeTransformers.computeIfAbsent(i.getClassToTransform(), (h) -> new ArrayList<>()).add(i.getVisitorFunction());
                    }
                }

                Path wiringJar = Paths.get(wiringClassesDirectory.getAbsolutePath());
                Files.walk(wiringJar).forEach(new Consumer<Path>() {
                    @Override
                    public void accept(Path path) {
                        try {
                            String pathName = wiringJar.relativize(path).toString();
                            if (Files.isDirectory(path)) {
                                String p = pathName + "/";
                                if (seen.contains(p)) {
                                    return;
                                }
                                seen.add(p);
                                if (!pathName.isEmpty()) {
                                    runner.putNextEntry(new ZipEntry(p));
                                }
                            } else if (pathName.startsWith("META-INF/services/") && pathName.length() > 18) {
                                services.computeIfAbsent(pathName, (u) -> new ArrayList<>()).add(CopyUtils.readFileContent(path));
                            } else {
                                seen.add(pathName);
                                runner.putNextEntry(new ZipEntry(pathName));
                                try (FileInputStream in = new FileInputStream(path.toFile())) {
                                    doCopy(runner, in);
                                }
                            }
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    }
                });

                Manifest manifest = new Manifest();
                manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
                manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath.toString());
                manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
                runner.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
                manifest.write(runner);
                //now copy all the contents to the runner jar
                //I am not 100% sure about this idea, but if we are going to support bytecode transforms it seems
                //like the cleanest way to do it
                //at the end of the PoC phase all this needs review
                Path appJar = Paths.get(outputDirectory.getAbsolutePath());
                ExecutorService executorPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
                ConcurrentLinkedDeque<Future<FutureEntry>> transformed = new ConcurrentLinkedDeque<>();
                try {
                    Files.walk(appJar).forEach(new Consumer<Path>() {
                        @Override
                        public void accept(Path path) {
                            try {
                                final String pathName = appJar.relativize(path).toString();
                                if (Files.isDirectory(path)) {
//                                if (!pathName.isEmpty()) {
//                                    out.putNextEntry(new ZipEntry(pathName + "/"));
//                                }
                                } else if (pathName.endsWith(".class") && !bytecodeTransformers.isEmpty()) {
                                    String className = pathName.substring(0, pathName.length() - 6).replace('/', '.');
                                    List<BiFunction<String, ClassVisitor, ClassVisitor>> visitors = bytecodeTransformers.get(className);

                                    if (visitors == null || visitors.isEmpty()) {
                                        runner.putNextEntry(new ZipEntry(pathName));
                                        try (FileInputStream in = new FileInputStream(path.toFile())) {
                                            doCopy(runner, in);
                                        }
                                    } else {
                                        transformed.add(executorPool.submit(new Callable<FutureEntry>() {
                                            @Override
                                            public FutureEntry call() throws Exception {
                                                final byte[] fileContent = CopyUtils.readFileContent(path);
                                                ClassReader cr = new ClassReader(fileContent);
                                                ClassWriter writer = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                                                ClassVisitor visitor = writer;
                                                for (BiFunction<String, ClassVisitor, ClassVisitor> i : visitors) {
                                                    visitor = i.apply(className, visitor);
                                                }
                                                cr.accept(visitor, 0);
                                                return new FutureEntry(writer.toByteArray(), pathName);
                                            }
                                        }));
                                    }
                                } else {
                                    runner.putNextEntry(new ZipEntry(pathName));
                                    try (FileInputStream in = new FileInputStream(path.toFile())) {
                                        doCopy(runner, in);
                                    }
                                }
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }
                    });
                    for (Future<FutureEntry> i : transformed) {

                        FutureEntry res = i.get();
                        runner.putNextEntry(new ZipEntry(res.location));
                        runner.write(res.data);
                    }
                } finally {
                    executorPool.shutdown();
                }
                for (Map.Entry<String, List<byte[]>> entry : services.entrySet()) {
                    runner.putNextEntry(new ZipEntry(entry.getKey()));
                    for (byte[] i : entry.getValue()) {
                        runner.write(i);
                        runner.write('\n');
                    }
                }
            }


        } catch (Exception e) {
            throw new MojoFailureException("Failed to run", e);
        }
    }

    private ZipFile openZipFile(final Artifact a) {
        final File file = a.getFile();
        if (file == null) {
            throw new RuntimeException("No file for Artifact:" + a.toString());
        }
        if (!Files.isReadable(file.toPath())) {
            throw new RuntimeException("File not existing or not allowed for reading: " + file.getAbsolutePath());
        }
        try {
            return new ZipFile(file);
        } catch (IOException e) {
            throw new RuntimeException("Error opening zip stream from artifact: " + a.toString());
        }
    }

    private static void doCopy(OutputStream out, InputStream in) throws IOException {
        byte[] buffer = new byte[1024];
        int r;
        while ((r = in.read(buffer)) > 0) {
            out.write(buffer, 0, r);
        }
    }

    private static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int r;
        while ((r = in.read(buffer)) > 0) {
            out.write(buffer, 0, r);
        }
        return out.toByteArray();
    }

    private static final class FutureEntry {
        final byte[] data;
        final String location;

        private FutureEntry(byte[] data, String location) {
            this.data = data;
            this.location = location;
        }
    }
}
