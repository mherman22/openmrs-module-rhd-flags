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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Date;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.After;
import org.junit.Test;
import org.openmrs.GlobalProperty;
import org.openmrs.api.context.Context;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.openmrs.module.rhdflags.task.PatientFlagRefreshTask;
import org.openmrs.scheduler.SchedulerService;
import org.openmrs.scheduler.TaskDefinition;
import org.openmrs.util.OpenmrsConstants;
import org.openmrs.util.OpenmrsUtil;

public class RhdFlagsActivatorContextTest extends BaseModuleContextSensitiveTest {
	
	private static final String MODULE_LOGGER = "org.openmrs.module.rhdflags.task.PatientFlagRefreshTask";
	
	@After
	public void restoreLogging() {
		context().reconfigure();
	}
	
	/**
	 * Calls applyLogLevel directly, as saving log.level does on 2.4.4 and later; on 2.4.0 a save
	 * reconfigures first.
	 */
	@Test
	public void aSavedLevelForThisModuleLeavesTheRestOfOpenmrsAlone() {
		new RhdFlagsActivator().started();
		LoggerConfig openmrs = context().getConfiguration().getLoggerConfig("org.openmrs");
		Level openmrsLevel = openmrs.getLevel();
		
		OpenmrsUtil.applyLogLevel("org.openmrs.module.rhdflags", "info");
		
		assertEquals(openmrsLevel, openmrs.getLevel());
		assertEquals(Level.INFO, LogManager.getLogger(MODULE_LOGGER).getLevel());
	}
	
	@Test
	public void theModuleStillFollowsOpenmrsWhenLogLevelDoesNotNameIt() {
		new RhdFlagsActivator().started();
		
		OpenmrsUtil.applyLogLevel("org.openmrs", "debug");
		
		assertEquals(Level.DEBUG, LogManager.getLogger(MODULE_LOGGER).getLevel());
	}
	
	@Test
	public void theEntryInLogLevelWinsOverALevelLog4jConfigurationSets() {
		Context.getAdministrationService().saveGlobalProperty(
		    new GlobalProperty(OpenmrsConstants.GLOBAL_PROPERTY_LOG_LEVEL, "org.openmrs.module.rhdflags:info"));
		context().getConfiguration().addLogger("org.openmrs.module.rhdflags",
		    new LoggerConfig("org.openmrs.module.rhdflags", Level.WARN, true));
		context().updateLoggers();
		// Fetched first, as the module's own loggers are, so it only sees the change if loggers are refreshed.
		org.apache.logging.log4j.Logger existing = LogManager.getLogger(MODULE_LOGGER);
		
		new RhdFlagsActivator().started();
		
		assertEquals(Level.INFO, existing.getLevel());
	}
	
	@Test
	public void keepsALevelLog4jConfigurationSetsWhenLogLevelDoesNotNameTheModule() {
		context().getConfiguration().addLogger("org.openmrs.module.rhdflags",
		    new LoggerConfig("org.openmrs.module.rhdflags", Level.WARN, true));
		context().updateLoggers();
		
		new RhdFlagsActivator().started();
		
		assertEquals(Level.WARN, LogManager.getLogger(MODULE_LOGGER).getLevel());
	}
	
	@Test
	public void anEntryForAnotherPackageLeavesTheModuleFollowingOpenmrs() {
		Context.getAdministrationService()
		        .saveGlobalProperty(new GlobalProperty(OpenmrsConstants.GLOBAL_PROPERTY_LOG_LEVEL, "org.openmrs.api:info"));
		context().reconfigure();
		
		new RhdFlagsActivator().started();
		OpenmrsUtil.applyLogLevel("org.openmrs", "debug");
		
		assertEquals(Level.DEBUG, LogManager.getLogger(MODULE_LOGGER).getLevel());
	}
	
	@Test
	public void updatesTheDescriptionOfATaskAnEarlierVersionRegistered() {
		SchedulerService schedulerService = Context.getSchedulerService();
		TaskDefinition task = new TaskDefinition();
		task.setName(RhdFlagsActivator.REFRESH_TASK_NAME);
		task.setDescription("an older description");
		task.setTaskClass(PatientFlagRefreshTask.class.getName());
		task.setRepeatInterval(3600L);
		Date startTime = new Date(1500000000000L);
		task.setStartTime(startTime);
		task.setStartOnStartup(Boolean.TRUE);
		task.setStarted(Boolean.FALSE);
		schedulerService.saveTaskDefinition(task);
		
		new RhdFlagsActivator().started();
		
		TaskDefinition refreshed = schedulerService.getTaskByName(RhdFlagsActivator.REFRESH_TASK_NAME);
		assertTrue(refreshed.getDescription(),
		    refreshed.getDescription().startsWith("Re-evaluates every enabled, unretired patient flag"));
		assertEquals(Long.valueOf(3600L), refreshed.getRepeatInterval());
		assertEquals(startTime.getTime(), refreshed.getStartTime().getTime());
	}
	
	private LoggerContext context() {
		return ((Logger) LogManager.getRootLogger()).getContext();
	}
}
