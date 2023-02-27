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
 * Result of PowerShell command. This object encapsulates all the useful
 * returned information
 *
 * @author Alain Picard
 */
public class PowerShellResult {
	private final String commandOutput;
	private final String errorOutput;

	PowerShellResult(String commandOutput, String errorOutput) {
		this.commandOutput = commandOutput;
		this.errorOutput = errorOutput;
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
}
