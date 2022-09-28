/*
 * Copyright (C) 2013 Brett Wooldridge
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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;

/**
 * This class is used to create operating system processes.
 * <p>
 * Each {@code NuProcessBuilder} instance manages a collection of process
 * attributes. The {@link #start()} method creates a new {@link NuProcess}
 * instance with those attributes. The {@link #start()} method can be invoked
 * repeatedly from the same instance to create new subprocesses with identical
 * or related attributes.
 * <p>
 * Each {@code NuProcessBuilder} manages these attributes:
 * <ul>
 * <li>a <em>command</em>, a list of strings which signifies the external
 * program file to be invoked and its arguments, if any. Which string lists
 * represent a valid operating system command is system-dependent. For example,
 * it is common for each conceptual argument to be an element in this list, but
 * there are operating systems where programs are expected to tokenize command
 * line strings themselves - on such a system a Java implementation might
 * require commands to contain exactly two elements.</li>
 *
 * <li>an <em>environment</em>, which is a system-dependent mapping from
 * variables to values. The initial value is a copy of the environment of the
 * current process. See {@link System#getenv()}.</li>
 * </ul>
 * <p>
 * Modifying a process builder's attributes will affect processes subsequently
 * started by that object's {@link #start()} method, but will never affect
 * previously started processes or the Java process itself.
 *
 * @author Brett Wooldridge
 */
public class NuProcessBuilder
{
   private static final NuProcessFactory factory;

   private final List<String> command;
   private final TreeMap<String, String> environment;
   private Path cwd;
   private NuProcessHandler processListener;

   static {
      String factoryClassName = null;
      String osname = System.getProperty("os.name").toLowerCase();
      if (osname.contains("mac") || osname.contains("freebsd")) {
         factoryClassName = "com.zaxxer.nuprocess.osx.OsxProcessFactory";
      }
      else if (osname.contains("win")) {
         factoryClassName = "com.zaxxer.nuprocess.windows.WinProcessFactory";
      }
      else if (osname.contains("linux")) {
         factoryClassName = "com.zaxxer.nuprocess.linux.LinProcessFactory";
      }
      else if (osname.contains("sunos")) {
         factoryClassName = "com.zaxxer.nuprocess.solaris.SolProcessFactory";
      }

      if (factoryClassName == null) {
         throw new RuntimeException("Unsupported operating system: " + osname);
      }

      try {
         Class<?> forName = Class.forName(factoryClassName);
         factory = (NuProcessFactory) forName.newInstance();
      }
      catch (Exception e) {
         throw new RuntimeException(e);
      }
   }

   /**
    * Constructs a process builder with the specified operating system program
    * and arguments. This constructor makes a copy of the command list. Invokers
    * of this constructor must later call
    * {@link #setProcessListener(NuProcessHandler)} in order to set a
    * {@link NuProcessHandler} instance.
    *
    * @param commands a {@link List} of commands
    * @param environment The environment for the process
    */
   public NuProcessBuilder(List<String> commands, Map<String, String> environment)
   {
      if (commands == null || commands.isEmpty()) {
         throw new IllegalArgumentException("List of commands may not be null or empty");
      }

      this.environment = new TreeMap<String, String>(environment);
      this.command = new ArrayList<String>(commands);
   }

   /**
    * Constructs a process builder with the specified operating system program
    * and arguments. This constructor makes a copy of the command list. Invokers
    * of this constructor must later call
    * {@link #setProcessListener(NuProcessHandler)} in order to set a
    * {@link NuProcessHandler} instance.
    *
    * @param commands a {@link List} of commands
    */
   public NuProcessBuilder(List<String> commands)
   {
      if (commands == null || commands.isEmpty()) {
         throw new IllegalArgumentException("List of commands may not be null or empty");
      }

      this.environment = new TreeMap<String, String>(System.getenv());
      this.command = new ArrayList<String>(commands);
   }

   /**
    * Constructs a process builder with the specified operating system program
    * and arguments. Invokers of this constructor must later call
    * {@link #setProcessListener(NuProcessHandler)} in order to set a
    * {@link NuProcessHandler} instance.
    *
    * @param commands a list of commands
    */
   public NuProcessBuilder(String... commands)
   {
      if (commands == null || commands.length == 0) {
         throw new IllegalArgumentException("List of commands may not be null or empty");
      }

      this.environment = new TreeMap<String, String>(System.getenv());
      this.command = new ArrayList<String>(Arrays.asList(commands));
   }

   /**
    * Constructs a process builder with the specified {@link NuProcessHandler}
    * and operating system program and arguments.
    *
    * @param nuProcessHandler a {@link NuProcessHandler} instance
    * @param commands a list of commands
    */
   public NuProcessBuilder(NuProcessHandler nuProcessHandler, String... commands)
   {
      this(commands);

      if (nuProcessHandler == null) {
         throw new IllegalArgumentException("A NuProcessListener must be specified");
      }

      this.processListener = nuProcessHandler;
   }

   /**
    * Constructs a process builder with the specified {@link NuProcessHandler}
    * and operating system program and arguments. This constructor makes a copy
    * of the command list.
    *
    * @param nuProcessHandler a {@link NuProcessHandler} instance
    * @param commands a {@link List} of commands
    */
   public NuProcessBuilder(NuProcessHandler nuProcessHandler, List<String> commands)
   {
      this(commands);

      if (nuProcessHandler == null) {
         throw new IllegalArgumentException("A NuProcessListener must be specified");
      }

      this.processListener = nuProcessHandler;
   }

   /**
    * Get the {@link List} of commands that were used to construct this
    * {@link NuProcessBuilder}.
    *
    * @return a {@link List} of commands
    */
   public List<String> command()
   {
      return command;
   }

   /**
    * Returns a string map view of this process builder's environment. Whenever
    * a process builder is created, the environment is initialized to a copy of
    * the current process environment. Subprocesses subsequently started by this
    * object's {@link #start()} method will use this map as their environment.
    * <p>
    * The returned object may be modified using ordinary Map operations prior to
    * invoking the {@link #start()} method. The returned map is typically
    * case-sensitive on all platforms.
    *
    * @return This process builder's environment
    */
   public Map<String, String> environment()
   {
      return environment;
   }

   /**
    * Set the {@link NuProcessHandler} instance that will be used for the next
    * and subsequent launch of a {@link NuProcess} when calling the
    * {@link #start()} method.
    *
    * @param listener a {@link NuProcessHandler} instance
    */
   public void setProcessListener(NuProcessHandler listener)
   {
      if (listener == null) {
         throw new IllegalArgumentException("A NuProcessListener must be specified");
      }

      this.processListener = listener;
   }

   /**
    * Set the {@link Path} to which the current working directory (cwd) of the
    * subsequent launch of a {@link NuProcess} will be set when calling the
    * {@link #start()} method.
    *
    * @param cwd a {@link Path} to use for the process's current working
    *        directory, or {@code null} to disable setting the cwd of
    *        subsequently launched proceses
    */
   public void setCwd(Path cwd)
   {
      this.cwd = cwd;
   }

   /**
    * Spawn the child process with the configured commands, environment, and
    * {@link NuProcessHandler}.
    *
    * @return a {@link NuProcess} instance or {@code null} if there is an
    *         immediately detectable launch failure
    */
   public NuProcess start()
   {
      ensureNoNullCharacters(command);
      ensureListener();
      String[] env = prepareEnvironment();

      return factory.createProcess(command, env, processListener, cwd);
   }

   /**
    * Spawn the child process with the configured commands, environment, and {@link NuProcessHandler}
    * and wait for it to complete running.
    *
    * @since 1.3
    */
   public void run()
   {
      ensureNoNullCharacters(command);
      ensureListener();
      String[] env = prepareEnvironment();

      factory.runProcess(command, env, processListener, cwd);
   }

   private void ensureListener()
   {
      if (processListener == null) {
         throw new IllegalArgumentException("NuProcessHandler not specified");
      }
   }

   private void ensureNoNullCharacters(List<String> commands) {
      for (String command : commands) {
         if (command.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("Commands may not contain null characters");
         }
      }
   }

   private void ensureNoNullCharacters(String environment) {
      if (environment.indexOf('\u0000') >= 0) {
         throw new IllegalArgumentException("Environment may not contain null characters");
      }
   }

   private String[] prepareEnvironment()
   {
      String[] env = new String[environment.size()];
      int i = 0;
      for (Entry<String, String> entrySet : environment.entrySet()) {
         String key = entrySet.getKey();
         String value = entrySet.getValue();
         ensureNoNullCharacters(key);
         ensureNoNullCharacters(value);
         env[i++] = key + "=" + value;
      }

      return env;
   }
}
