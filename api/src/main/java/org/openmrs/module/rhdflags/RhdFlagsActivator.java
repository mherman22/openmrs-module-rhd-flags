package org.openmrs.module.rhdflags;

import java.util.Date;

import org.openmrs.api.context.Context;
import org.openmrs.module.BaseModuleActivator;
import org.openmrs.module.rhdflags.task.PatientFlagRefreshTask;
import org.openmrs.scheduler.SchedulerService;
import org.openmrs.scheduler.TaskDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers the flag refresh with the scheduler on first start.
 *
 * Initializer has no domain for scheduler_task_config, and the patientflags module registers
 * nothing of its own, so without this the task would have to be created by hand in the admin UI
 * on every environment.
 */
public class RhdFlagsActivator extends BaseModuleActivator {

	public static final String REFRESH_TASK_NAME = "RHD Patient Flag Refresh";

	public static final String INTERVAL_PROPERTY = "rhdflags.refreshIntervalSeconds";

	private static final long DEFAULT_INTERVAL_SECONDS = 86400L;

	private static final Logger log = LoggerFactory.getLogger(RhdFlagsActivator.class);

	@Override
	public void started() {
		schedule(REFRESH_TASK_NAME, PatientFlagRefreshTask.class.getName(),
		    "Re-evaluates patient flags whose criteria depend on the passage of time, then mirrors each"
		            + " flag into a patient list of the same name.");
		log.info("RHD Flags module started");
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
			task.setStartTime(new Date());
			task.setRepeatInterval(intervalSeconds());
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

	private Long intervalSeconds() {
		String configured = Context.getAdministrationService().getGlobalProperty(INTERVAL_PROPERTY);
		if (configured != null && !configured.trim().isEmpty()) {
			try {
				return Long.valueOf(configured.trim());
			}
			catch (NumberFormatException e) {
				log.warn("{} is not a number, using {}s", INTERVAL_PROPERTY, DEFAULT_INTERVAL_SECONDS);
			}
		}
		return DEFAULT_INTERVAL_SECONDS;
	}

	@Override
	public void stopped() {
		log.info("RHD Flags module stopped");
	}
}
