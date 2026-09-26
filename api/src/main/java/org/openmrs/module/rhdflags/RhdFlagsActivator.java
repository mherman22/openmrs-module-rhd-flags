/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.rhdflags;

import java.util.Date;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.openmrs.api.context.Context;
import org.openmrs.module.BaseModuleActivator;
import org.openmrs.module.rhdflags.task.PatientFlagRefreshTask;
import org.openmrs.scheduler.SchedulerService;
import org.openmrs.scheduler.TaskDefinition;
import org.openmrs.util.OpenmrsConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers the flag refresh with the scheduler on first start. Initializer has no domain for
 * scheduler_task_config, and the patientflags module registers nothing of its own, so without this
 * the task would have to be created by hand in the admin UI on every environment.
 */
public class RhdFlagsActivator extends BaseModuleActivator {
	
	public static final String REFRESH_TASK_NAME = "RHD Patient Flag Refresh";
	
	static final String LOG_PACKAGE = "org.openmrs.module.rhdflags";
	
	/**
	 * The scheduler owns the interval once the task exists, so this is only the value the task is first
	 * registered with. Change it afterwards in Manage Scheduler.
	 */
	private static final long REFRESH_INTERVAL_SECONDS = 86400L;
	
	/**
	 * Keeps the first run minutes after installation rather than a day after it. The scheduler derives
	 * the first execution from startTime + repeatInterval once startTime is past, so a task registered
	 * at the current instant leaves a fresh install with no flag lists until tomorrow.
	 */
	private static final long INITIAL_DELAY_MILLIS = 300000L;
	
	private static final Logger log = LoggerFactory.getLogger(RhdFlagsActivator.class);
	
	@Override
	public void started() {
		applyConfiguredLogLevel();
		schedule(REFRESH_TASK_NAME, PatientFlagRefreshTask.class.getName(),
		    "Re-evaluates every enabled, unretired patient flag, then mirrors each flag into a patient list"
		            + " of the same name.");
		log.info("RHD Flags module started");
	}
	
	/**
	 * Gives this module its own logger, at log.level's entry for it or inheriting when there is none.
	 * Core resolves a saved entry to the nearest configured logger, which would otherwise be
	 * org.openmrs.
	 */
	private void applyConfiguredLogLevel() {
		try {
			Level level = null;
			String configured = Context.getAdministrationService()
			        .getGlobalProperty(OpenmrsConstants.GLOBAL_PROPERTY_LOG_LEVEL, "");
			for (String entry : configured.split(",")) {
				String[] packageAndLevel = entry.split(":");
				if (packageAndLevel.length == 2 && LOG_PACKAGE.equals(packageAndLevel[0].trim())) {
					level = Level.toLevel(packageAndLevel[1].trim(), Level.INFO);
				}
			}
			
			LoggerContext context = ((org.apache.logging.log4j.core.Logger) LogManager.getRootLogger()).getContext();
			Configuration configuration = context.getConfiguration();
			LoggerConfig own = configuration.getLoggers().get(LOG_PACKAGE);
			// Only when absent: a level log4j2.xml sets for this package must survive.
			if (own == null) {
				configuration.addLogger(LOG_PACKAGE, new LoggerConfig(LOG_PACKAGE, level, true));
			} else if (level != null) {
				own.setLevel(level);
			}
			context.updateLoggers();
			if (level != null) {
				log.info("Logging for {} set to {}", LOG_PACKAGE, level);
			}
		}
		catch (Exception e) {
			// Logging configuration is never a reason to fail the module's start.
			log.warn("Could not apply the configured log level for {}", LOG_PACKAGE, e);
		}
	}
	
	private void schedule(String name, String taskClass, String description) {
		try {
			SchedulerService schedulerService = Context.getSchedulerService();
			if (schedulerService.getTaskByName(name) != null) {
				log.debug("'{}' is already registered", name);
				return;
			}
			
			TaskDefinition task = new TaskDefinition();
			task.setName(name);
			task.setDescription(description);
			task.setTaskClass(taskClass);
			task.setStartTime(new Date(System.currentTimeMillis() + INITIAL_DELAY_MILLIS));
			task.setRepeatInterval(REFRESH_INTERVAL_SECONDS);
			task.setStartOnStartup(Boolean.TRUE);
			task.setStarted(Boolean.TRUE);
			
			schedulerService.saveTaskDefinition(task);
			schedulerService.scheduleTask(task);
			log.info("Registered '{}' every {}s", name, task.getRepeatInterval());
		}
		catch (Exception e) {
			// A module that cannot schedule itself must still start, or it takes the whole
			// distribution down with it on a machine where the scheduler is unavailable.
			log.error("Could not schedule '{}'", name, e);
		}
	}
	
	@Override
	public void stopped() {
		log.info("RHD Flags module stopped");
	}
}
