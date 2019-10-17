/*
 * (C) Copyright 2017-2018 OpenVidu (https://openvidu.io/)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.openvidu.load.test.utils;

import static java.lang.invoke.MethodHandles.lookup;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;

import io.openvidu.load.test.OpenViduLoadTest;
import io.openvidu.load.test.browser.Browser;
import io.openvidu.load.test.browser.BrowserProperties;
import io.openvidu.load.test.browser.NetworkRestriction;
import io.openvidu.load.test.models.AmazonInstance;

public class BrowserSshManager {

	final static Logger log = getLogger(lookup().lookupClass());

	AmazonInstance amazonInstance;
	BrowserProperties properties;
	JSch jsch;
	Session jschSession;

	public BrowserSshManager(Browser browser) throws JSchException {
		this.amazonInstance = browser.getInstance();
		this.properties = browser.getBrowserProperties();
		this.jsch = new JSch();
		Properties config = new Properties();
		config.put("StrictHostKeyChecking", "no");
		config.put("PreferredAuthentications", "publickey");
		jsch.addIdentity(OpenViduLoadTest.PRIVATE_KEY_PATH);
		jschSession = jsch.getSession(OpenViduLoadTest.SERVER_SSH_USER, amazonInstance.getPublicIp(), 22);
		jschSession.setConfig(config);
		jschSession.connect(10000);
	}

	public void startRecording() throws Exception {
		log.info("Starting recording of browser {} in instance {}", properties.userId(), amazonInstance.toString());
		String command;
		try {
			command = readCommandFromFile("startRecording.txt");
			command = command.replace("USERID", properties.userId());
		} catch (IOException e) {
			log.error("Couldn't read file '{}' to get recording start commands. Browser instance {} won't be recorded",
					properties.userId(), amazonInstance.toString());
			return;
		}
		if (command != null) {
			String response = this.sendCommand(command);
			if (response.isEmpty()) {
				log.info("Browser {} is now being recorded", properties.userId());
			} else {
				throw new Exception(
						"Some error ocurred in browser instance " + properties.userId() + " when starting recording");
			}
		}
	}

	public void stopRecording() {
		log.info("Stopping recording of browser {} in instance {}", properties.userId(), amazonInstance.toString());
		String command;
		try {
			command = readCommandFromFile("stopRecording.txt");
		} catch (IOException e) {
			log.error("Couldn't read file '{}' to get recording stop commands", "stopRecording.txt");
			return;
		}
		log.info("Response of stopping recording: {}", this.sendCommand(command));
	}

	public void updateNetworkingRestrictions(NetworkRestriction networkRestriction) throws Exception {
		log.info("Updating networking restrictions (setting to {}) for browser {} of instance {}",
				properties.networkRestriction().name(), properties.userId(), amazonInstance.toString());
		String command = null;
		String response = null;
		try {
			switch (networkRestriction) {
			case ALL_OPEN:
				command = readCommandFromFile("allOpen.txt");
				break;
			case TCP_ONLY:
				command = readCommandFromFile("tcpOnly.txt");
				break;
			case TURN:
				command = readCommandFromFile("turnAux.txt") + " && ";
				String portNotBlocked = null;
				String activeTcpConnections = CommandExecutor.executeCommand("netstat -tn");

				String keyword = this.amazonInstance.getPublicIp() + ":" + 4444;
				String rx = "(\\w+)\\s+(" + keyword + ")";
				Matcher matcher = Pattern.compile(rx).matcher(activeTcpConnections);
				if (matcher.find()) {
					portNotBlocked = matcher.group(1);
					log.info("Not blocking port {} in remote browser machine {}", portNotBlocked, this.amazonInstance.getPublicIp());
				}

				command = command.replaceAll("DYNAMIC_PORT", portNotBlocked);
				String fixedCommands = readCommandFromFile("turn.txt");
				command = command + fixedCommands;
				break;
			}
		} catch (IOException e) {
			log.error(
					"Couldn't read file '{}' to get network configuration commands. Browser networking won't be changed","turn.txt");
			return;
		}
		if (command != null) {
			response = this.sendCommand(command);
			if (response.isEmpty()) {
				this.properties.changeNetworkingRestrictionConfig(networkRestriction);
				log.info("Networking restrictions successfully updated to {} for browser {} of instance {}",
						properties.networkRestriction().name(), properties.userId(), amazonInstance.toString());
			} else {
				throw new Exception("Some error ocurred in browser instance " + properties.userId()
						+ " when configuring network conditions");
			}
		}
	}

	public void startTcpDump() {
		log.info("Starting tcpdump process of browser {} in instance {}", properties.userId(),
				amazonInstance.toString());
		String command;
		try {
			command = readCommandFromFile("startTcpdump.txt");
			command = command.replace("USERID", properties.userId());
		} catch (IOException e) {
			log.error(
					"Couldn't read file '{}' to get starting tcpdump commands. Browser instance {} won't gather network info",
					properties.userId(),amazonInstance.getInstanceId());
			return;
		}
		if (command != null) {
			log.info("Response of start tcpdump: {}", this.sendCommand(command));
		}
	}

	public void stopTcpDump() {
		log.info("Stopping tcpdump process of browser {} in instance {}", properties.userId(),
				amazonInstance.toString());
		String command;
		try {
			command = readCommandFromFile("stopTcpdump.txt");
		} catch (IOException e) {
			log.error("Couldn't read file '{}' to get stopping tcpdump commands", "stopTcpdump.txt");
			return;
		}
		if (command != null) {
			log.info("Response of stop tcpdump: {}", this.sendCommand(command));
		}
	}

	private String sendCommand(String command) {
		if (this.jschSession.isConnected()) {
			StringBuilder outputBuffer = new StringBuilder();
			try {
				Channel channel = jschSession.openChannel("exec");
				((ChannelExec) channel).setCommand(command);
				InputStream commandOutput = channel.getInputStream();

				StringBuilder errorBuffer = new StringBuilder();
				InputStream errorOuput = ((ChannelExec) channel).getErrStream();

				channel.connect(4000);
				int readByte = commandOutput.read();
				int errorByte = errorOuput.read();
				while (readByte != 0xffffffff) {
					outputBuffer.append((char) readByte);
					readByte = commandOutput.read();
				}
				while (errorByte != 0xffffffff) {
					errorBuffer.append((char) errorByte);
					errorByte = errorOuput.read();
				}

				if (errorBuffer.length() > 0) {
					log.error("Error sending command '{}' to {}: {}", command, amazonInstance.getPublicIp(),
							errorBuffer.toString());
					return errorBuffer.toString();
				}

				channel.disconnect();
			} catch (IOException e) {
				log.warn(e.getMessage());
				return null;
			} catch (JSchException e) {
				log.warn(e.getMessage());
				return null;
			}
			return outputBuffer.toString();
		} else {
			log.error("There's no SSH connection to instance {} of user {}. Cannot send command '{}'",
					amazonInstance.getInstanceId(), properties.userId(), command);
			return null;
		}
	}

	private String readCommandFromFile(String fileName) throws IOException {
		File f = new File(getClass().getClassLoader().getResource(fileName).getFile());
		BufferedReader br = new BufferedReader(new FileReader(f));
		StringBuilder sb = new StringBuilder();
		try {
			String line = br.readLine();
			while (line != null) {
				sb.append(line);
				line = br.readLine();
				if (line != null) {
					sb.append(" && ");
				}
			}
		} finally {
			br.close();
		}
		return sb.toString();
	}

}
