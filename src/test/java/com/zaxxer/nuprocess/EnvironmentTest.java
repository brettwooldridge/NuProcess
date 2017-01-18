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

import java.io.IOException;
import java.io.InputStream;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CoderResult;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.zaxxer.nuprocess.NuProcess.Stream;
import com.zaxxer.nuprocess.codec.NuAbstractCharsetHandler;

public class EnvironmentTest {
	private String[] command;
	private static boolean isWin = System.getProperty("os.name").toLowerCase().contains("win"); 

	@Before
	public void setup() 
	{
		if (isWin) {
			command = new String[] { "cmd", "/C", "set" };
		} else {
			command = new String[] { "env" };
		}
	}

	@Test
	public void emptyEnv() throws InterruptedException, IOException 
	{
		Map<String, String> env = new HashMap<String, String>();
		Set<String> javaResult = runJavaProcess(command, env);
		Set<String> nuResult = runNuProcess(command, env);
		Assert.assertEquals(javaResult, nuResult);
	}
	
	@Test
	public void defaultEnv() throws InterruptedException, IOException 
	{
		Set<String> javaResult = runJavaProcess(command, System.getenv());
		Set<String> nuResult = runNuProcess(command, System.getenv());
		Assert.assertEquals(javaResult, nuResult);
	}

	private Set<String> runJavaProcess(String[] command, Map<String, String> env) throws IOException, InterruptedException 
	{
		ProcessBuilder pb = new ProcessBuilder(command);
		pb.environment().clear();
		pb.environment().putAll(env);

		System.out.println("Started Java Process");
		Process process = pb.start();

		try (InputStream is = process.getInputStream();
		     Scanner s = new Scanner(is);) {
		   Scanner scanner = s.useDelimiter(System.lineSeparator());
		   Set<String> result = new TreeSet<String>();
   		while (scanner.hasNext()) {
   			result.add(scanner.next());
   		}

   		System.out.println("Waited for Java Process");
   		Assert.assertEquals(0, process.waitFor());

   		System.out.println("Process env: " + result);
   		return result;
		}
	}

	private Set<String> runNuProcess(String[] command, Map<String, String> env) throws InterruptedException 
	{
		ProcessHandler processListener = new ProcessHandler();
		NuProcessBuilder pb = new NuProcessBuilder(Arrays.asList(command), env);
		pb.setProcessListener(processListener);
		NuProcess process = pb.start();
		process.want(Stream.STDOUT);
		System.out.println("Started NuProcess");

		System.out.println("Waited for NuProcess");
		Assert.assertEquals(0, process.waitFor(10, TimeUnit.SECONDS));

      try (Scanner s = new Scanner(processListener.getStdOut())) {
         Scanner scanner = s.useDelimiter(System.lineSeparator());
         Set<String> result = new TreeSet<String>();
   		while (scanner.hasNext()) {
   			result.add(scanner.next());
   		}

   		System.out.println("NuProcess env: " + result);
   		return result;
      }
	}

	private static class ProcessHandler extends NuAbstractCharsetHandler 
	{
		protected ProcessHandler() 
		{
			super(Charset.forName("UTF-8"));
		}

		private StringBuilder stdOut = new StringBuilder();

		@Override
		public boolean onStdoutChars(CharBuffer buffer, boolean closed, CoderResult coderResult) 
		{
			stdOut.append(buffer);
			buffer.position(buffer.limit());
			return !closed;
		}

		public String getStdOut() 
		{
			return stdOut.toString();
		}
	}

}
