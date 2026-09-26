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

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.After;
import org.junit.Test;
import org.openmrs.test.BaseModuleContextSensitiveTest;
import org.openmrs.util.OpenmrsUtil;

public class RhdFlagsActivatorContextTest extends BaseModuleContextSensitiveTest {
	
	private static final String MODULE_LOGGER = "org.openmrs.module.rhdflags.task.PatientFlagRefreshTask";
	
	@After
	public void restoreLogging() {
		context().reconfigure();
	}
	
	/**
	 * Uses the per-logger lookup core's save path makes on 2.4.4 and later, where saving log.level does
	 * not reload the configuration first.
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
	
	private LoggerContext context() {
		return ((Logger) LogManager.getRootLogger()).getContext();
	}
}
