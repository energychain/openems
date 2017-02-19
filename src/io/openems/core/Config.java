/*******************************************************************************
 * OpenEMS - Open Source Energy Management System
 * Copyright (c) 2016, 2017 FENECON GmbH and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 *
 * Contributors:
 *   FENECON GmbH - initial API and implementation and initial documentation
 *******************************************************************************/
package io.openems.core;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map.Entry;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import io.openems.api.bridge.Bridge;
import io.openems.api.channel.Channel;
import io.openems.api.channel.ChannelChangeListener;
import io.openems.api.channel.ConfigChannel;
import io.openems.api.controller.Controller;
import io.openems.api.device.Device;
import io.openems.api.doc.ThingDoc;
import io.openems.api.exception.ConfigException;
import io.openems.api.exception.NotImplementedException;
import io.openems.api.exception.OpenemsException;
import io.openems.api.exception.ReflectionException;
import io.openems.api.exception.WriteChannelException;
import io.openems.api.persistence.Persistence;
import io.openems.api.scheduler.Scheduler;
import io.openems.api.security.User;
import io.openems.core.utilities.AbstractWorker;
import io.openems.core.utilities.ConfigUtils;
import io.openems.core.utilities.InjectionUtils;
import io.openems.core.utilities.JsonUtils;

public class Config implements ChannelChangeListener {
	private final static Logger log = LoggerFactory.getLogger(Config.class);
	private static Config instance;

	public static synchronized Config getInstance() throws ConfigException {
		if (Config.instance == null) {
			Config.instance = new Config();
		}
		return Config.instance;
	}

	private final ThingRepository thingRepository;
	private final File file;

	public Config() throws ConfigException {
		thingRepository = ThingRepository.getInstance();
		this.file = getConfigFile();
	}

	public synchronized void readConfigFile()
			throws IOException, FileNotFoundException, ReflectionException, ConfigException, WriteChannelException {
		JsonObject jConfig = new JsonObject();
		log.info("Read configuration from " + file.getAbsolutePath());
		JsonParser parser = new JsonParser();
		JsonElement jsonElement = parser.parse(new FileReader(file));
		jConfig = jsonElement.getAsJsonObject();
		jConfig = addDefaultConfig(jConfig);
		// apply config
		parseJsonConfig(jConfig);
	}

	private JsonObject addDefaultConfig(JsonObject jConfig) {
		try {
			/*
			 * Things
			 */
			JsonArray jThings;
			if (!jConfig.has("things") || !jConfig.get("things").isJsonArray()) {
				jThings = new JsonArray();
			} else {
				jThings = JsonUtils.getAsJsonArray(jConfig, "things");
			}
			{
				/*
				 * Add SystemBridge
				 */
				if (!JsonUtils.hasElement(jConfig, "things", "class", "io.openems.impl.protocol.system.SystemBridge")) {
					JsonObject jBridge = new JsonObject();
					{
						jBridge.addProperty("class", "io.openems.impl.protocol.system.SystemBridge");
						JsonArray jDevices = new JsonArray();
						{
							JsonObject jSystem = new JsonObject();
							jSystem.addProperty("class", "io.openems.impl.device.system.System");
							{
								JsonObject jSystemNature = new JsonObject();
								{
									jSystemNature.addProperty("id", "system0");
								}
								jSystem.add("system", jSystemNature);
							}
							jDevices.add(jSystem);
						}
						jBridge.add("devices", jDevices);
					}
					jThings.add(jBridge);
				}
			}
			jConfig.add("things", jThings);
			/*
			 * Scheduler
			 */
			JsonObject jScheduler;
			if (!jConfig.has("scheduler") || !jConfig.get("scheduler").isJsonObject()) {
				jScheduler = new JsonObject();
				jScheduler.addProperty("class", "io.openems.impl.scheduler.SimpleScheduler");
			} else {
				jScheduler = JsonUtils.getAsJsonObject(jConfig, "scheduler");
			}
			{
				/*
				 * Controller
				 */
				JsonArray jControllers;
				if (!jScheduler.has("controllers") || !jScheduler.get("controllers").isJsonArray()) {
					jControllers = new JsonArray();
				} else {
					jControllers = JsonUtils.getAsJsonArray(jScheduler, "controllers");
				}
				{
					/*
					 * WebsocketApiController
					 */
					if (!JsonUtils.hasElement(jControllers, "class",
							"io.openems.impl.controller.api.websocket.WebsocketApiController")) {
						JsonObject jWebsocketApiController = new JsonObject();
						jWebsocketApiController.addProperty("class",
								"io.openems.impl.controller.api.websocket.WebsocketApiController");
						jWebsocketApiController.addProperty("priority", Integer.MIN_VALUE);
						jControllers.add(jWebsocketApiController);
					}
					/*
					 * RestApiController
					 */
					if (!JsonUtils.hasElement(jControllers, "class",
							"io.openems.impl.controller.api.rest.RestApiController")) {
						JsonObject jRestApiController = new JsonObject();
						jRestApiController.addProperty("class",
								"io.openems.impl.controller.api.rest.RestApiController");
						jRestApiController.addProperty("priority", Integer.MIN_VALUE);
						jControllers.add(jRestApiController);
					}
				}
				jScheduler.add("controllers", jControllers);
			}
			jConfig.add("scheduler", jScheduler);
		} catch (ReflectionException e) {
			log.warn("Error applying default config: " + e.getMessage());
		}
		return jConfig;
	}

	public synchronized void writeConfigFile() throws OpenemsException {
		JsonObject jConfig = getJsonComplete();
		try (Writer writer = new FileWriter(file)) {
			Gson gson = new GsonBuilder().setPrettyPrinting().create();
			gson.toJson(jConfig, writer);
			log.info("Wrote configuration to " + file.getAbsolutePath());
		} catch (IOException e) {
			throw new ConfigException("Unable to write to file [" + file.getAbsolutePath() + "]");
		}
	}

	public synchronized void parseJsonConfig(JsonObject jConfig)
			throws ReflectionException, ConfigException, WriteChannelException {
		/*
		 * read Users
		 */
		if (jConfig.has("users")) {
			JsonObject jUsers = JsonUtils.getAsJsonObject(jConfig, "users");
			for (Entry<String, JsonElement> jUsersElement : jUsers.entrySet()) {
				JsonObject jUser = JsonUtils.getAsJsonObject(jUsersElement.getValue());
				String username = jUsersElement.getKey();
				String passwordBase64 = JsonUtils.getAsString(jUser, "password");
				String saltBase64 = JsonUtils.getAsString(jUser, "salt");
				try {
					User.getUserByName(username).initialize(passwordBase64, saltBase64);
				} catch (OpenemsException e) {
					log.error("Error parsing config: " + e.getMessage());
				}
			}
		}
		User.initializeFinished(); // important! no more setting of users allowed!

		/*
		 * read each Bridge in "things" array
		 */
		JsonArray jThings = JsonUtils.getAsJsonArray(jConfig, "things");
		for (JsonElement jBridgeElement : jThings) {
			JsonObject jBridge = JsonUtils.getAsJsonObject(jBridgeElement);
			String bridgeClass = JsonUtils.getAsString(jBridge, "class");
			Bridge bridge = (Bridge) InjectionUtils.getThingInstance(bridgeClass);
			thingRepository.addThing(bridge);
			log.debug("Add Bridge[" + bridge.id() + "], Implementation[" + bridge.getClass().getSimpleName() + "]");
			ConfigUtils.injectConfigChannels(thingRepository.getConfigChannels(bridge), jBridge);
			/*
			 * read each Device in "things" array
			 */
			List<Device> devices = new ArrayList<>();
			JsonArray jDevices = JsonUtils.getAsJsonArray(jBridge, "devices");
			for (JsonElement jDeviceElement : jDevices) {
				JsonObject jDevice = JsonUtils.getAsJsonObject(jDeviceElement);
				Device device = thingRepository.createDevice(jDevice);
				devices.add(device);
				bridge.addDevice(device);
			}
		}

		/*
		 * read Scheduler
		 */
		if (jConfig.has("scheduler")) {
			JsonObject jScheduler = JsonUtils.getAsJsonObject(jConfig, "scheduler");
			String schedulerClass = JsonUtils.getAsString(jScheduler, "class");
			Scheduler scheduler = (Scheduler) InjectionUtils.getThingInstance(schedulerClass);
			thingRepository.addThing(scheduler);
			log.debug("Add Scheduler[" + scheduler.id() + "], Implementation[" + scheduler.getClass().getSimpleName()
					+ "]");
			ConfigUtils.injectConfigChannels(thingRepository.getConfigChannels(scheduler), jScheduler);
			/*
			 * read each Controller in "controllers" array
			 */
			JsonArray jControllers = JsonUtils.getAsJsonArray(jScheduler, "controllers");
			for (JsonElement jControllerElement : jControllers) {
				JsonObject jController = JsonUtils.getAsJsonObject(jControllerElement);
				Controller controller = thingRepository.createController(jController);
				scheduler.addController(controller);
			}
		}

		/*
		 * read Persistence
		 */
		if (jConfig.has("persistence")) {
			JsonArray jPersistences = JsonUtils.getAsJsonArray(jConfig, "persistence");
			for (JsonElement jPersistenceElement : jPersistences) {
				JsonObject jPersistence = JsonUtils.getAsJsonObject(jPersistenceElement);
				String persistenceClass = JsonUtils.getAsString(jPersistence, "class");
				Persistence persistence = (Persistence) InjectionUtils.getThingInstance(persistenceClass);
				thingRepository.addThing(persistence);
				log.debug("Add Persistence[" + persistence.id() + "], Implementation["
						+ persistence.getClass().getSimpleName() + "]");
				ConfigUtils.injectConfigChannels(thingRepository.getConfigChannels(persistence), jPersistence);
			}
		}

		/*
		 * Configuration is finished -> start all worker threads
		 */
		thingRepository.getThings().forEach(thing -> {
			if (thing instanceof Thread) {
				((AbstractWorker) thing).start();
			}
		});

		/*
		 * Register myself as onChangeListener on all ConfigChannels
		 */
		for (ConfigChannel<?> channel : thingRepository.getConfigChannels()) {
			channel.addChangeListener(this);
		}
	}

	public JsonArray getBridgesJson(boolean includeEverything) throws NotImplementedException {
		JsonArray jBridges = new JsonArray();
		for (Bridge bridge : thingRepository.getBridges()) {
			JsonObject jBridge = (JsonObject) ConfigUtils.getAsJsonElement(bridge, includeEverything);
			/*
			 * Device
			 */
			JsonArray jDevices = new JsonArray();
			for (Device device : bridge.getDevices()) {
				JsonObject jDevice = (JsonObject) ConfigUtils.getAsJsonElement(device, includeEverything);
				jDevices.add(jDevice);
			}
			jBridge.add("devices", jDevices);
			jBridges.add(jBridge);
		}
		return jBridges;
	}

	public JsonObject getSchedulerJson(boolean includeEverything) throws NotImplementedException {
		JsonObject jScheduler = null;
		for (Scheduler scheduler : thingRepository.getSchedulers()) {
			jScheduler = (JsonObject) ConfigUtils.getAsJsonElement(scheduler, includeEverything);
			/*
			 * Controller
			 */
			JsonArray jControllers = new JsonArray();
			for (Controller controller : scheduler.getControllers()) {
				jControllers.add(ConfigUtils.getAsJsonElement(controller, includeEverything));
			}
			jScheduler.add("controllers", jControllers);
			break; // TODO only one Scheduler supported
		}
		return jScheduler;
	}

	public JsonArray getPersistenceJson(boolean includeEverything) throws NotImplementedException {
		JsonArray jPersistences = new JsonArray();
		for (Persistence persistence : thingRepository.getPersistences()) {
			JsonObject jPersistence = (JsonObject) ConfigUtils.getAsJsonElement(persistence, includeEverything);
			jPersistences.add(jPersistence);
		}
		return jPersistences;
	}

	private JsonObject getUsersJson() {
		JsonObject jUsers = new JsonObject();
		for (User user : User.getUsers()) {
			JsonObject jUser = new JsonObject();
			jUser.addProperty("password", user.getPasswordBase64());
			jUser.addProperty("salt", user.getSaltBase64());
			jUsers.add(user.getName(), jUser);
		}
		return jUsers;
	}

	public synchronized JsonObject getJson(boolean includeEverything) throws NotImplementedException {
		JsonObject jConfig = new JsonObject();
		/*
		 * Bridge
		 */
		jConfig.add("things", getBridgesJson(includeEverything));
		/*
		 * Scheduler
		 */
		jConfig.add("scheduler", getSchedulerJson(includeEverything));
		/*
		 * Persistence
		 */
		jConfig.add("persistence", getPersistenceJson(includeEverything));
		return jConfig;
	}

	private synchronized JsonObject getJsonComplete() throws NotImplementedException {
		JsonObject jConfig = getJson(false);
		/*
		 * Users
		 */
		jConfig.add("users", getUsersJson());
		return jConfig;
	}

	/**
	 * Receives update events for config channels and rewrites the json config
	 */
	@Override
	public void channelChanged(Channel channel, Optional<?> newValue, Optional<?> oldValue) {
		// TODO: trigger ConfigUpdated event
		try {
			writeConfigFile();
		} catch (OpenemsException e) {
			log.error("Config-Error.", e);
		}
	}

	/*
	 * Provides the File path of the config file ("/etc/openems.d/config.json") or a local file on a development machine
	 */
	private static File getConfigFile() throws ConfigException {
		File configFile = Paths.get("/etc", "openems.d", "config.json").toFile();
		if (!configFile.isFile()) {
			configFile = Paths.get("D:", "fems", "openems", "etc", "openems.d", "config.json").toFile();
		}
		if (!configFile.isFile()) {
			configFile = Paths.get("C:", "Users", "matthias.rossmann", "Documents", "config.json").toFile();
		}
		if (!configFile.isFile()) {
			throw new ConfigException("No config file found!");
		}
		return configFile;
	}

	/**
	 * Generates a JsonObject including the current configuration as well as meta-data about available controllers,
	 * bridges,...
	 *
	 * @return
	 */
	public JsonObject getMetaConfigJson() {
		try {
			/*
			 * Json Config
			 */
			JsonObject j = getJson(true);
			JsonObject jMeta = new JsonObject();
			/*
			 * Devices -> Natures
			 */
			JsonObject jDeviceNatures = new JsonObject();
			thingRepository.getDeviceNatures().forEach(nature -> {
				JsonArray jNatureImplements = new JsonArray();
				/*
				 * get important classes/interfaces that are implemented by this nature
				 */
				for (Class<?> iface : InjectionUtils.getImportantNatureInterfaces(nature.getClass())) {
					jNatureImplements.add(iface.getSimpleName());
				}
				JsonObject jDeviceNature = new JsonObject();
				jDeviceNature.add("implements", jNatureImplements);
				JsonObject jChannels = new JsonObject();
				thingRepository.getConfigChannels(nature).forEach(channel -> {
					try {
						jChannels.add(channel.id(), channel.toJsonObject());
					} catch (NotImplementedException e) {
						/* ignore */
					}
				});
				jDeviceNature.add("channels", jChannels);
				jDeviceNatures.add(nature.id(), jDeviceNature);
			});
			jMeta.add("natures", jDeviceNatures);
			/*
			 * Available
			 */
			ClassRepository classRepository = ClassRepository.getInstance();
			// Controllers
			JsonArray jAvailableControllers = new JsonArray();
			for (ThingDoc description : classRepository.getAvailableControllers()) {
				jAvailableControllers.add(description.getAsJsonObject());
			}
			jMeta.add("availableControllers", jAvailableControllers);
			// Bridges
			JsonArray jAvailableBridges = new JsonArray();
			for (ThingDoc description : classRepository.getAvailableBridges()) {
				jAvailableBridges.add(description.getAsJsonObject());
			}
			jMeta.add("availableBridges", jAvailableBridges);
			// Devices
			JsonArray jAvailableDevices = new JsonArray();
			for (ThingDoc description : classRepository.getAvailableDevices()) {
				jAvailableDevices.add(description.getAsJsonObject());
			}
			jMeta.add("availableDevices", jAvailableDevices);
			// Schedulers
			JsonArray jAvailableSchedulers = new JsonArray();
			for (ThingDoc description : classRepository.getAvailableSchedulers()) {
				jAvailableSchedulers.add(description.getAsJsonObject());
			}
			jMeta.add("availableSchedulers", jAvailableSchedulers);
			j.add("_meta", jMeta);
			return j;
		} catch (NotImplementedException | ReflectionException e) {
			log.warn("Unable to create config: " + e.getMessage());
			return new JsonObject();
		}
	}
}
