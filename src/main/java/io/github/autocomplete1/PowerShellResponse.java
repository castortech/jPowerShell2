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

/**
 * Response of PowerShell command. This object encapsulates all the useful
 * returned information
 *
 * @author Javier Garcia Alonso
 */
public class PowerShellResponse {
	private final boolean error;
	private final String commandOutput;
	private final String errorOutput;
	private final boolean timeout;

	public PowerShellResponse(boolean isError, String commandOutput, String errorOutput, boolean timeout) {
		this.error = isError;
		this.commandOutput = commandOutput;
		this.errorOutput = errorOutput;
		this.timeout = timeout;
	}
	
	public PowerShellResponse(boolean isError, PowerShellResult commandResult, boolean timeout) {
		this.error = isError;
		this.commandOutput = commandResult.getCommandOutput();
		this.errorOutput = commandResult.getErrorOutput();
		this.timeout = timeout;
	}

	/**
	 * True if the command could not be correctly executed (timeout or unexpected error)<p>
	 *
	 * If you want to check if the command itself finished in error, use the method {@link PowerShell#isLastCommandInError()}
	 * instead
	 *
	 * @return boolean value
	 */
	public boolean isError() {
		return error;
	}

	/**
	 * Retrieves the content returned by the executed command
	 *
	 * @return String value
	 */
	public String getCommandOutput() {
		return commandOutput;
	}

	/**
	 * Retrieves the error content returned by the executed command if configured to split stdout and stderr
	 *
	 * @return String  value
	 */
	public String getErrorOutput() {
		return errorOutput;
	}

	/**
	 * True if the command finished in timeout
	 *
	 * @return boolean value
	 */
	public boolean isTimeout() {
		return timeout;
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("PowerShellResponse [error=");
		sb.append(error);
		sb.append(", commandOutput=");
		sb.append(commandOutput);
		sb.append(", errorOutput=");
		sb.append(errorOutput);
		sb.append(", timeout=");
		sb.append(timeout);
		sb.append("]");
		return sb.toString();
	}
}
