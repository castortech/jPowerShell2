/*
 * Copyright 2016-2019 Javier Garcia Alonso.
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
package io.github.autocomplete1;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.CopyOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * This API allows to open a session into PowerShell console and launch different commands.<br>
 * This class cannot be instantiated directly. Please use instead the method
 * PowerShell.openSession() and call the commands using the returned instance.
 * <p>
 * Once the session is finished it should be closed in order to free resources.
 * For doing that, you can either call manually close() or implement a try with resources as
 * it implements {@link AutoCloseable}.
 *
 * @author Javier Garcia Alonso
 */
public class PowerShell implements AutoCloseable {
	//Declare logger
	private static final Logger logger = Logger.getLogger(PowerShell.class.getName());

	private static final String CRLF = "\r\n";
	private static final String SPACE = " ";
	
	// Process to store PowerShell session
	private Process p;
	//PID of the process
	private long pid = -1;
	// Writer to send commands
	private PrintWriter commandWriter;

	private BufferedReader outputReader;
	private NonBlockingInputStream errorStream;

	// Threaded session variables
	private boolean closed = false;
	private ExecutorService executorService;

	private Map<String, String> preferences = new HashMap<>();

	private static final String CONFIG_COMBINE_ERRORS = "combineErrors";
	private static final String CONFIG_PS_EXEC = "powerShellExecutable";
	private static final String CONFIG_WAIT_PAUSE = "waitPause";
	private static final String CONFIG_MAX_WAIT = "maxWait";
	private static final String CONFIG_TMP_FOLDER = "tempFolder";
	private static final String CONFIG_ERR_EXCEPTION = "errorAsException";

	/** Specifies a script folder that must be copied (including any sub-folders) into the same temp folder as the executing script.
	 *  <p> Note that unless set to its own value, this will be used as the temp folder in order to allow scripts to include all other necessary files.
	 *  Also this folder copy will happen once when the session is opened. 
	 */
	private static final String CONFIG_SCRIPT_FOLDER = "scriptFolder";

	//Default PowerShell executable path
	private static final String DEFAULT_WIN_EXECUTABLE = "powershell.exe";
	private static final String DEFAULT_LINUX_EXECUTABLE = "powershell";

	// Config values
	private boolean combineErrors = true;
	private boolean errorAsException = false;
	private String powerShellExecutable = getDefaultPowershellExecutable();
	private int waitPause = 5;
	private long maxWait = 10000;
	private File tempFolder = null;
	private File scriptFolder = null;

	// Variables used for script mode
	private boolean scriptMode = false;
	
	/** Used to create a dummy output on commands that have no output at all */
	public static final String END_COMMAND_STRING = "--END-JPOWERSHELL-COMMAND--";

	/** Used to mark the end of a script, so we can know where we're done */
	public static final String END_SCRIPT_STRING = "--END-JPOWERSHELL-SCRIPT--";

	// Private constructor. Instance using openSession method
	private PowerShell() {
	}

	/**
	 * Allows overriding jPowerShell configuration using a map of key/value <br>
	 * Default values are taken from file <i>jpowershell.properties</i>, which can
	 * be replaced just setting it on project classpath
	 * <p>
	 * The values that can be overridden are:
	 * <ul>
	 * <li>waitPause: the pause in ms between each loop pooling for a response.
	 * Default value is 5</li>
	 * <li>maxWait: the maximum wait in ms for the command to execute. Default value
	 * is 10000</li>
	 * </ul>
	 *
	 * @param config map with the configuration in key/value format
	 * @return instance to chain
	 */
	public PowerShell configuration(Map<String, String> config) {
		if (config == null && PowerShellConfig.getConfig().getProperty(CONFIG_WAIT_PAUSE) == null) {
			return this;
		}

		if (config != null) {
			config.forEach((key, value) -> {
				switch (key) {
					case CONFIG_COMBINE_ERRORS:
						this.combineErrors = Boolean.parseBoolean(value);
						break;
					case CONFIG_ERR_EXCEPTION:
						this.errorAsException = Boolean.parseBoolean(value);
						break;
					case CONFIG_WAIT_PAUSE:
						this.waitPause = Integer.parseInt(value);
						break;
					case CONFIG_MAX_WAIT:
						this.maxWait = Long.parseLong(value);
						break;
					case CONFIG_PS_EXEC:
						this.powerShellExecutable = value;
						break;
					case CONFIG_TMP_FOLDER:
						this.tempFolder = getFolder(value);
						break;
					case CONFIG_SCRIPT_FOLDER:
						this.scriptFolder = getFolder(value);
						break;
					default:
						if (key.startsWith("$")) {  //preferences to set for the session
							preferences.put(key, value);
						}
						break;
				}
			});
			return this;
		}

		this.combineErrors = Boolean.parseBoolean(PowerShellConfig.getConfig().getProperty(CONFIG_COMBINE_ERRORS));
		this.errorAsException = Boolean.parseBoolean(PowerShellConfig.getConfig().getProperty(CONFIG_ERR_EXCEPTION));
		this.waitPause = Integer.parseInt(PowerShellConfig.getConfig().getProperty(CONFIG_WAIT_PAUSE));
		this.maxWait = Long.parseLong(PowerShellConfig.getConfig().getProperty(CONFIG_MAX_WAIT));
		this.powerShellExecutable = PowerShellConfig.getConfig().getProperty(CONFIG_PS_EXEC);
		this.tempFolder = getFolder(PowerShellConfig.getConfig().getProperty(CONFIG_TMP_FOLDER));
		this.scriptFolder = getFolder(PowerShellConfig.getConfig().getProperty(CONFIG_SCRIPT_FOLDER));

		preferences.putAll(PowerShellConfig.getConfig().entrySet().stream()
				.filter(entry -> entry.getKey() instanceof String && ((String)entry.getKey()).startsWith("$"))
				.collect(Collectors.toMap(entry -> (String)entry.getKey(), entry -> (String)entry.getValue())));

		return this;
	}

	/**
	 * Creates a session in PowerShell console which returns an instance which allows
	 * executing commands in PowerShell context.<br>
	 * It uses the default PowerShell installation in the system.
	 *
	 * @return an instance of the class
	 * @throws PowerShellNotAvailableException if PowerShell is not installed in the system
	 */
	public static PowerShell openSession() throws PowerShellNotAvailableException {
		return openSession((String)null);
	}

	/**
	 * Creates a session in PowerShell console which returns an instance which allows
	 * executing commands in PowerShell context.<br>
	 * This method allows to define a PowersShell executable path different from default
	 *
	 * @param customPowerShellExecutablePath the path of powershell executable. If you are using
	 *									   the default installation path, call {@link #openSession()} method instead
	 * @return an instance of the class
	 * @throws PowerShellNotAvailableException if PowerShell is not installed in the system
	 */
	public static PowerShell openSession(String customPowerShellExecutablePath) throws PowerShellNotAvailableException {
		Map<String, String> config = new HashMap<>();
		if (customPowerShellExecutablePath != null) {
			config.put(CONFIG_PS_EXEC, customPowerShellExecutablePath);
		}
		return openSession(config);
	}

	/**
	 * Creates a session in PowerShell console which returns an instance which allows
	 * executing commands in PowerShell context.<br>
	 * This method allows to define a PowersShell executable path different from default
	 *
	 * @param config						 map with the configuration in key/value format
	 * @return an instance of the class
	 * @throws PowerShellNotAvailableException if PowerShell is not installed in the system
	 */
	public static PowerShell openSession(Map<String, String> config) throws PowerShellNotAvailableException {
		PowerShell powerShell = new PowerShell();

		// Start with default configuration
		powerShell.configuration(config);

		return powerShell.initialize();
	}

	// Initializes PowerShell console in which we will enter the commands
	private PowerShell initialize() throws PowerShellNotAvailableException {
		String codePage = PowerShellCodepage.getIdentifierByCodePageName(Charset.defaultCharset().name());
		ProcessBuilder pb;

		//Start powershell executable in process
		if (OSDetector.isWindows()) {
			pb = new ProcessBuilder("cmd.exe", "/c", "chcp", codePage, ">", "NUL", "&", powerShellExecutable,
					"-ExecutionPolicy", "Bypass", "-NonInteractive","-NoExit", "-NoProfile", "-Command", "-");
		} 
		else {
			pb = new ProcessBuilder(powerShellExecutable, "-nologo", "-noexit", "-Command", "-");
		}

		//this is to make sure that we don't get any ANSI codes in returned error stream content.
		Map<String, String> environment = pb.environment();
		environment.put("PSStyle.OutputRendering", "plaintext");
		environment.put("__SuppressAnsiEscapeSequences", "1");  //finally what worked for me, but not well documented, opened issue in PS github
		environment.put("NO_COLOR", "1");

		if (combineErrors) {
			//Merge standard and error streams
			pb.redirectErrorStream(true);
		}

		try {
			if (scriptFolder != null) {
				tempFolder = copyScriptFolder();
			}

			//Launch process
			p = pb.start();

			if (p.waitFor(5, TimeUnit.SECONDS) && !p.isAlive()) {
				throw new PowerShellNotAvailableException(
						"Cannot execute PowerShell. Please make sure that it is installed in your system. Errorcode:" + p.exitValue());
			}
		} 
		catch (IOException | InterruptedException ex) {
			throw new PowerShellNotAvailableException(
					"Cannot execute PowerShell. Please make sure that it is installed in your system", ex);
		}

		//Prepare writer that will be used to send commands to powershell
		this.commandWriter = new PrintWriter(new OutputStreamWriter(new BufferedOutputStream(p.getOutputStream())), true);

		this.outputReader = new BufferedReader(new InputStreamReader(p.getInputStream()));
		if (!combineErrors) {
			this.errorStream = new NonBlockingInputStream(new BufferedInputStream(p.getErrorStream()), true);
		}

		// Init thread pool. 2 threads are needed: one to write and read console and the other to close it
		this.executorService = Executors.newFixedThreadPool(2);

		//Get and store the PID of the process
		this.pid = getPID();

		setPreferences();

		return this;
	}

	/**
	 * Execute a PowerShell command.
	 * <p>
	 * This method launch a thread which will be executed in the already created PowerShell console context
	 * <p>
	 * <b>IMPORTANT</b> Commands must return a value, otherwise they will wait and timeout. See
	 * {@link #setPreferences()} for an example of how to get around this.
	 *
	 * @param command
	 *          the command to call. Ex: dir
	 * @return PowerShellResponse the information returned by powerShell
	 */
	public PowerShellResponse executeCommand(String command) {
		PowerShellResult commandResult = new PowerShellResult(null, null);
		boolean isError = false;
		boolean timeout = false;

		checkState();

		PowerShellCommandProcessor commandProcessor = new PowerShellCommandProcessor(outputReader, errorStream, 
				waitPause, scriptMode, errorAsException);
		Future<PowerShellResult> result = executorService.submit(commandProcessor);

		// Launch command
		commandWriter.println(command);

		try {
			if (!result.isDone()) {
				try {
					commandResult = result.get(maxWait, TimeUnit.MILLISECONDS);
				} 
				catch (TimeoutException timeoutEx) {
					timeout = true;
					isError = true;
					//Interrupt command after timeout
					result.cancel(true);
				}
			}
		}
		catch (InterruptedException iex) {
			logger.log(Level.SEVERE, "Unexpected error when processing PowerShell command", iex);
			isError = true;
		}
		catch (ExecutionException ex) {
			if (ex.getCause() instanceof PowerShellScriptException) {
				throw (PowerShellScriptException)(ex.getCause());
			}
			else {
				logger.log(Level.SEVERE, "Unexpected error when processing PowerShell command", ex);
			}
			isError = true;
		}
		finally {
			// issue #2. Close and cancel processors/threads - Thanks to r4lly for helping me here
			commandProcessor.close();
		}

		return new PowerShellResponse(isError, commandResult, timeout);
	}

	/**
	 * Execute a single command in PowerShell console scriptMode and gets result
	 *
	 * @param command the command to execute
	 * @return response with the output of the command
	 */
	public static PowerShellResponse executeSingleCommand(String command) {
		PowerShellResponse response = null;

		try (PowerShell session = PowerShell.openSession()) {
			response = session.executeCommand(command);
		}
		catch (PowerShellNotAvailableException ex) {
			logger.log(Level.SEVERE, "PowerShell not available", ex);
		}

		return response;
	}

	/**
	 * Allows chaining command executions providing a more fluent API.
	 * <p>
	 * This method allows also to optionally handle the response in a closure
	 *
	 * @param command  the command to execute
	 * @param response optionally, the response can be handled in a closure
	 * @return The {@link PowerShell} instance
	 */
	public PowerShell executeCommandAndChain(String command, PowerShellResponseHandler... response) {
		PowerShellResponse powerShellResponse = executeCommand(command);

		if (response.length > 0) {
			handleResponse(response[0], powerShellResponse);
		}

		return this;
	}

	// Handle response in callback way
	private void handleResponse(PowerShellResponseHandler response, PowerShellResponse powerShellResponse) {
		try {
			response.handle(powerShellResponse);
		}
		catch (Exception ex) {
			logger.log(Level.SEVERE, "PowerShell not available", ex);
		}
	}

	/**
	 * Indicates if the last executed command finished in error
	 *
	 * @return boolean
	 */
	public boolean isLastCommandInError() {
		return !Boolean.parseBoolean(executeCommand("$?").getCommandOutput());
	}

	/**
	 * Executed the provided PowerShell script in PowerShell console and gets
	 * result.
	 *
	 * @param scriptPath the full path of the script
	 * @return response with the output of the command
	 */
	public PowerShellResponse executeScript(String scriptPath) {
		return executeScript(scriptPath, "");
	}

	/**
	 * Executed the provided PowerShell script in PowerShell console and gets
	 * result.
	 *
	 * @param scriptPath the full path of the script
	 * @param params	 the parameters of the script
	 * @return response with the output of the command
	 */
	@SuppressWarnings("WeakerAccess")
	public PowerShellResponse executeScript(String scriptPath, String... params) {
		try (BufferedReader srcReader = new BufferedReader(new FileReader(scriptPath))) {
			return executeScript(srcReader, params);
		}
		catch (FileNotFoundException fnfex) {
			logger.log(Level.SEVERE, "Unexpected error when processing PowerShell script: file not found", fnfex);
			String output = "Wrong script path: " + scriptPath;
			return new PowerShellResponse(true, output, output, false);
		}
		catch (IOException ioe) {
			logger.log(Level.SEVERE, "Unexpected error when processing PowerShell script", ioe);
			String output = "IO error reading: " + scriptPath;
			return new PowerShellResponse(true, output, output, false);
		}
	}

	/**
	 * Execute the provided PowerShell script in PowerShell console and gets
	 * result.
	 *
	 * @param srcReader the script as BufferedReader (when loading File from jar)
	 * @return response with the output of the command
	 */
	public PowerShellResponse executeScript(BufferedReader srcReader) {
		return executeScript(srcReader, "");
	}

	/**
	 * Execute the provided PowerShell script in PowerShell console and gets
	 * result.
	 *
	 * @param srcReader the script as BufferedReader (when loading File from jar)
	 * @param params	the parameters of the script
	 * @return response with the output of the command
	 */
	public PowerShellResponse executeScript(BufferedReader srcReader, String... params) {
		PowerShellResponse response;

		if (srcReader != null) {
			File tmpFile = createWriteTempFile(srcReader);

			if (tmpFile != null) {
				this.scriptMode = true;
				String paramArgs = Arrays.asList(params).stream().collect(Collectors.joining(" "));
				response = executeCommand(tmpFile.getAbsolutePath() + " " + paramArgs);
				this.scriptMode = false;
				tmpFile.delete();
			} 
			else {
				String output = "Cannot create temp script file!";
				response = new PowerShellResponse(true, output, output, false);
			}
		} 
		else {
			logger.log(Level.SEVERE, "Script buffered reader is null!");
			String output = "Script buffered reader is null!";
			response = new PowerShellResponse(true, output, output, false);
		}

		return response;
	}

	// Writes a temp powershell script file based on the srcReader
	private File createWriteTempFile(BufferedReader srcReader) {
		BufferedWriter tmpWriter = null;
		File tmpFile = null;

		try {
			tmpFile = File.createTempFile("psscript_" + new Date().getTime(), ".ps1", this.tempFolder);
			if (!tmpFile.exists()) {
				return null;
			}

			tmpWriter = new BufferedWriter(new FileWriter(tmpFile));
			String line;
			while (srcReader != null && (line = srcReader.readLine()) != null) {
				tmpWriter.write(line);
				tmpWriter.newLine();
			}

			// Add end script line
			tmpWriter.write("Write-Output \"" + END_SCRIPT_STRING + "\"");
		} 
		catch (IOException ioex) {
			logger.log(Level.SEVERE, "Unexpected error while writing temporary PowerShell script", ioex);
		} 
		finally {
			try {
				if (tmpWriter != null) {
					tmpWriter.close();
				}
			} 
			catch (IOException ex) {
				logger.log(Level.SEVERE, "Unexpected error when processing temporary PowerShell script", ex);
			}
		}

		return tmpFile;
	}

	private File copyScriptFolder() throws IOException {
		Path scriptPath = scriptFolder.toPath();
		Path tmpScriptPath;

		if (tempFolder != null) {
			tmpScriptPath = tempFolder.toPath();
		}
		else {
			tmpScriptPath = Files.createTempDirectory(null);
		}

		copyFolder(scriptPath, tmpScriptPath, REPLACE_EXISTING);
		return tmpScriptPath.toFile();
	}
	
	public void copyFolder(Path source, Path target, CopyOption options) throws IOException {
		Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				Files.createDirectories(target.resolve(source.relativize(dir).toString()));
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				Files.copy(file, target.resolve(source.relativize(file).toString()), options);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/**
	 * Closes all the resources used to maintain the PowerShell context
	 */
	@Override
	public void close() {
		if (!this.closed) {
			try {
				Future<String> closeTask = executorService.submit(() -> {
					commandWriter.println("exit");
					p.waitFor();
					return "OK";
				});
				
				if (!closeAndWait(closeTask) && this.pid > 0) {
					//If it can be closed, force kill the process
					Logger.getLogger(PowerShell.class.getName()).log(Level.INFO,
							"Forcing PowerShell to close. PID: " + this.pid);
					try {
						Runtime.getRuntime().exec("taskkill.exe /PID " + pid + " /F /T");
						this.closed = true;
					}
					catch (IOException e) {
						Logger.getLogger(PowerShell.class.getName()).log(Level.SEVERE,
								"Unexpected error while killing powershell process", e);
					}
				}
			}
			catch (InterruptedException | ExecutionException ex) {
				logger.log(Level.SEVERE, "Unexpected error when when closing PowerShell", ex);
			}
			finally {
				commandWriter.close();
				try {
					if (p.isAlive()) {
						outputReader.close();
					}
				} 
				catch (IOException ex) {
					logger.log(Level.SEVERE,
							"Unexpected error when when closing streams", ex);
				}
				if (this.executorService != null) {
					try {
						this.executorService.shutdownNow();
						this.executorService.awaitTermination(5, TimeUnit.SECONDS);
					} 
					catch (InterruptedException ex) {
						logger.log(Level.SEVERE, "Unexpected error when when shutting down thread pool", ex);
					}
				}
				this.closed = true;
			}
		}
	}

	private boolean closeAndWait(Future<String> task) throws InterruptedException, ExecutionException {
		boolean closed = true;
		if (!task.isDone()) {
			try {
				task.get(maxWait, TimeUnit.MILLISECONDS);
			} 
			catch (TimeoutException timeoutEx) {
				logger.log(Level.WARNING, "Powershell process cannot be closed. Session seems to be blocked");
				//Interrupt command after timeout
				task.cancel(true);
				closed = false;
			}
		}
		return closed;
	}

	//Checks if PowerShell have been already closed
	private void checkState() {
		if (this.closed) {
			throw new IllegalStateException("PowerShell is already closed. Please open a new session.");
		}
	}

	//Use Powershell command '$PID' in order to recover the process identifier
	private long getPID() {
		String commandOutput = executeCommand("$pid").getCommandOutput();

		//Remove all non-numeric characters
		commandOutput = commandOutput.replaceAll("\\D", "");

		if (!commandOutput.isEmpty()) {
			return Long.parseLong(commandOutput);
		}

		return -1;
	}

	private boolean setPreferences() {
		if (!preferences.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			preferences.entrySet().stream().forEach(entry -> {
				sb.append(entry.getKey());
				sb.append(SPACE);
				sb.append("=");
				sb.append(SPACE);
				sb.append(entry.getValue());
				sb.append(CRLF);
			});

			//needed since setting preferences produces no output and o/w will just timeout
			sb.append("Write-Output \"" + END_COMMAND_STRING + "\"");

			PowerShellResponse response = executeCommand(sb.toString());
			if (response.isError() || response.isTimeout()) {
				throw new PowerShellNotAvailableException("Cannot configure PowerShell preferences");
			}
			return isLastCommandInError();
		}
		return false;
	}
	
	private String getDefaultPowershellExecutable() {
		return OSDetector.isWindows() ? DEFAULT_WIN_EXECUTABLE : DEFAULT_LINUX_EXECUTABLE;
	}

	//Return the temp folder File object or null if the path does not exist
	private File getFolder(String folderPath) {
		if (folderPath != null) {
			File folder = new File(folderPath);
			if (folder.exists()) {
				return folder;
			}
		}
		return null;
	}
}
