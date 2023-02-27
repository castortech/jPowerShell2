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
 * Custom checked exception produced when the Powershell script returns a JSON formatted exception.
 * <p>
 * In order to capture those exception as exception the config value for errorAsException to true, default is
 * false.
 * <p>
 * Also in your script you will need to capture the exception as JSON and make sure to only return the JSON
 * portion.
 * <p>
 * To capture only the JSON written to stderr, use Write-StdErr from
 * https://stackoverflow.com/a/15669365/1331732
 * <p>
 * And to capture the JSON:
 * 
 * <pre> {@code
 *   Try {
 *     ...do something -EA Stop
 *   } 
 *   Catch [System.Management.Automation.ItemNotFoundException] {
 *       $_ | ConvertTo-Json -Depth 5 | Write-StdErr 
 *   }
 * }</pre>
 * 
 * @author Alain Picard
 */
public class PowerShellScriptException extends RuntimeException {
	private String exceptionType;
	private String stackTraceInfo;

	PowerShellScriptException(String message) {
		super(message);
	}

	PowerShellScriptException(String message, String exceptionType, String stackTraceInfo) {
		super(message);
		this.exceptionType = exceptionType;
		this.stackTraceInfo = stackTraceInfo;
	}

	public String getExceptionType() {
		return exceptionType;
	}

	public String getStackTraceInfo() {
		return stackTraceInfo;
	}
}