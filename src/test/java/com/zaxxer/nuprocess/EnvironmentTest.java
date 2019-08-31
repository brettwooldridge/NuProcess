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
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CoderResult;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

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
		Set<String> javaResult = runJavaProcess(command, Collections.<String, String>emptyMap());
		Set<String> nuResult = runNuProcess(command, Collections.<String, String>emptyMap());
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
		Process process = pb.start();
		Set<String> result = new LinkedHashSet<>();
		Scanner s = new Scanner(process.getInputStream(), "UTF-8").useDelimiter(System.lineSeparator());
		while (s.hasNext()) {
			result.add(s.next());
		}
		Assert.assertEquals(0, process.waitFor());
		return result;
	}

	private Set<String> runNuProcess(String[] command, Map<String, String> env) throws InterruptedException 
	{
		ProcessHandler processListener = new ProcessHandler();
		NuProcessBuilder pb = new NuProcessBuilder(Arrays.asList(command), env);
		pb.setProcessListener(processListener);
		NuProcess process = pb.start();
		process.waitFor(10, TimeUnit.SECONDS);
		Set<String> result = new LinkedHashSet<>();
		Scanner s = new Scanner(processListener.getStdOut()).useDelimiter(System.lineSeparator());
		while (s.hasNext()) {
			result.add(s.next());
		}
		return result;
	}

	private static class ProcessHandler extends NuAbstractCharsetHandler 
	{
		ProcessHandler()
		{
			super(Charset.forName("UTF-8"));
		}

		private final StringBuilder stdOut = new StringBuilder();

		@Override
		public void onStdoutChars(CharBuffer buffer, boolean closed, CoderResult coderResult) 
		{
			stdOut.append(buffer);
			buffer.position(buffer.limit());
		}

		String getStdOut()
		{
			return stdOut.toString();
		}
	}
}
