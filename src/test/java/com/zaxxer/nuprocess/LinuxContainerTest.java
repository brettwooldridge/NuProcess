/*
 * Copyright (C) 2026 Brett Wooldridge
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zaxxer.nuprocess;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * Runs the NuProcess test suite inside a Linux container, exercising the Linux
 * (epoll / posix_spawn) code paths even when the build itself is running on
 * macOS or Windows.
 * <p>
 * The compiled {@code target/classes} and {@code target/test-classes} directories,
 * along with the JUnit and Hamcrest jars from the host's test classpath, are copied
 * into a stock JDK 25 Linux image and executed with JUnit's console runner; no Maven
 * or network access is needed inside the container.
 * <p>
 * The test is skipped automatically when Docker is not available. The container
 * image can be overridden with {@code -Dnuprocess.test.linuxImage=<image>}, e.g. to
 * test a different distribution or, with a multi-arch host, a different architecture.
 */
public class LinuxContainerTest
{
   private static final String LINUX_IMAGE = System.getProperty("nuprocess.test.linuxImage", "eclipse-temurin:25-jdk");

   private static final String CONTAINER_WORK_DIR = "/nuprocess";

   static {
      // Testcontainers 1.21.x pins Docker API version 1.32, which recent Docker engines
      // reject (e.g. Docker Desktop with Engine 29 has a minimum API version of 1.40),
      // failing every request with HTTP 400. API version 1.44 corresponds to Docker
      // Engine 25.0 (January 2024). Pass -Dapi.version=<version> to override if your
      // Docker daemon is older than that.
      if (System.getProperty("api.version") == null) {
         System.setProperty("api.version", "1.44");
      }
   }

   @BeforeClass
   public static void checkDockerAvailable()
   {
      boolean dockerAvailable;
      try {
         dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
      }
      catch (Throwable t) {
         System.err.println("Docker is not available: " + t);
         dockerAvailable = false;
      }
      Assume.assumeTrue("Docker is not available; skipping Linux container test", dockerAvailable);
   }

   @Test
   public void runTestSuiteOnLinux() throws Exception
   {
      Path classes = Paths.get("target", "classes");
      Path testClasses = Paths.get("target", "test-classes");
      Assume.assumeTrue("Compiled classes not found; run via Maven", Files.isDirectory(classes) && Files.isDirectory(testClasses));

      List<Path> testLibraries = findTestLibraries();
      List<String> testClassNames = findTestClasses(testClasses);
      assertFalse("No test classes found in " + testClasses, testClassNames.isEmpty());

      try (GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(LINUX_IMAGE))) {
         container.withCommand("sleep", "infinity");
         container.withCopyFileToContainer(MountableFile.forHostPath(classes), CONTAINER_WORK_DIR + "/classes");
         container.withCopyFileToContainer(MountableFile.forHostPath(testClasses), CONTAINER_WORK_DIR + "/test-classes");

         List<String> classpath = new ArrayList<>();
         classpath.add(CONTAINER_WORK_DIR + "/classes");
         classpath.add(CONTAINER_WORK_DIR + "/test-classes");
         for (Path library : testLibraries) {
            String containerPath = CONTAINER_WORK_DIR + "/lib/" + library.getFileName();
            container.withCopyFileToContainer(MountableFile.forHostPath(library), containerPath);
            classpath.add(containerPath);
         }

         container.start();

         List<String> command = new ArrayList<>();
         command.add("java");
         command.add("--enable-native-access=ALL-UNNAMED");
         command.add("-cp");
         command.add(String.join(":", classpath));
         command.add("org.junit.runner.JUnitCore");
         command.addAll(testClassNames);

         ExecResult result = container.execInContainer(command.toArray(new String[0]));

         System.out.println("---- Linux container (" + LINUX_IMAGE + ") test output ----");
         System.out.println(result.getStdout());
         if (!result.getStderr().isEmpty()) {
            System.err.println(result.getStderr());
         }

         assertEquals("Test suite failed inside Linux container '" + LINUX_IMAGE + "' (see output above)", 0, result.getExitCode());
      }
   }

   /**
    * Locates the JUnit and Hamcrest jars on the host test classpath so they can be
    * copied into the container.
    */
   private static List<Path> findTestLibraries()
   {
      List<Path> libraries = new ArrayList<>();
      for (String entry : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
         Path path = Paths.get(entry);
         String name = path.getFileName() == null ? "" : path.getFileName().toString();
         if ((name.startsWith("junit") || name.startsWith("hamcrest")) && name.endsWith(".jar") && Files.isRegularFile(path)) {
            libraries.add(path);
         }
      }
      return libraries;
   }

   /**
    * Scans {@code target/test-classes} for top-level {@code *Test} classes, excluding
    * this class itself (so the containerized run cannot recurse).
    */
   private static List<String> findTestClasses(Path testClasses) throws IOException
   {
      List<String> classNames = new ArrayList<>();
      try (Stream<Path> paths = Files.walk(testClasses)) {
         paths.filter(p -> p.getFileName().toString().endsWith("Test.class"))
              .filter(p -> !p.getFileName().toString().contains("$"))
              .forEach(p -> {
                 String className = testClasses.relativize(p).toString()
                    .replace(java.io.File.separatorChar, '.')
                    .replaceAll("\\.class$", "");
                 if (!className.equals(LinuxContainerTest.class.getName())) {
                    classNames.add(className);
                 }
              });
      }
      classNames.sort(String::compareTo);
      return classNames;
   }
}
