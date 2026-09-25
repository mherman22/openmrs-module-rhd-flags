package org.openmrs.module.rhdflags.task;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openmrs.Cohort;
import org.openmrs.CohortMembership;
import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.PatientFlag;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.scheduler.tasks.AbstractTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Re-evaluates every enabled flag and writes only the difference.
 *
 * The patientflags module evaluates a flag when its definition is saved and through AOP advice
 * on clinical writes, so a criterion that becomes true purely because time passed never fires
 * on its own. This runs on the scheduler to close that gap.
 *
 * This adds and removes only what changed, so it never resets date_created itself. The
 * patientflags module still does: its AOP advice deletes and re-inserts a patient's rows on every
 * clinical write, so date_created does not say how long a flag has been raised.
 *
 * The flag lists are synced at the end of each run, so they reflect the rows just written.
 */
public class PatientFlagRefreshTask extends AbstractTask {

	private static final Logger log = LoggerFactory.getLogger(PatientFlagRefreshTask.class);

	@Override
	public void execute() {
		FlagService flagService = Context.getService(FlagService.class);
		int added = 0;
		int removed = 0;

		for (Flag flag : flagService.getAllFlags()) {
			if (!Boolean.TRUE.equals(flag.getEnabled()) || Boolean.TRUE.equals(flag.getRetired())) {
				continue;
			}
			try {
				int[] delta = reconcile(flagService, flag);
				added += delta[0];
				removed += delta[1];
			}
			catch (Exception e) {
				// One bad criterion must not stop the flags behind it from being refreshed.
				log.error("Could not refresh flag '{}'", flag.getName(), e);
			}
		}

		log.info("Patient flag refresh complete: {} raised, {} cleared", added, removed);

		new FlagListSync().syncAll();
	}

	int[] reconcile(FlagService flagService, Flag flag) {
		Map<Object, Object> evaluationContext = new HashMap<Object, Object>();
		Set<Integer> matching = evaluate(flagService, flag, evaluationContext);
		Set<Integer> alreadyFlagged = alreadyFlagged(flag);

		int added = 0;
		for (Integer patientId : matching) {
			if (!alreadyFlagged.contains(patientId)) {
				flagService.savePatientFlag(new PatientFlag(new Patient(patientId), flag,
				    message(flag, patientId, evaluationContext)));
				added++;
			}
		}

		int removed = 0;
		for (Integer patientId : alreadyFlagged) {
			if (!matching.contains(patientId)) {
				flagService.deletePatientFlagForPatient(new Patient(patientId), flag);
				removed++;
			}
		}

		if (added > 0 || removed > 0) {
			log.debug("Flag '{}': {} raised, {} cleared", flag.getName(), added, removed);
		}
		return new int[] { added, removed };
	}

	private Set<Integer> evaluate(FlagService flagService, Flag flag, Map<Object, Object> evaluationContext) {
		Set<Integer> patientIds = new HashSet<Integer>();
		Cohort cohort = flagService.getFlaggedPatients(flag, evaluationContext);
		if (cohort != null) {
			for (CohortMembership membership : cohort.getMemberships()) {
				patientIds.add(membership.getPatientId());
			}
		}
		return patientIds;
	}

	/**
	 * FlagService can read a patient's flags but not a flag's patients, so this reads the rows
	 * directly. The id is an Integer from the flag itself, so it cannot carry a quote. Voided rows
	 * are skipped because patientflags' own delete skips them too.
	 */
	Set<Integer> alreadyFlagged(Flag flag) {
		Set<Integer> patientIds = new HashSet<Integer>();
		List<List<Object>> rows = Context.getAdministrationService().executeSQL(
		    "select patient_id from patientflags_patient_flag where flag_id = " + flag.getFlagId() + " and voided = 0",
		    true);
		if (rows != null) {
			for (List<Object> row : rows) {
				if (row != null && !row.isEmpty() && row.get(0) != null) {
					patientIds.add(((Number) row.get(0)).intValue());
				}
			}
		}
		return patientIds;
	}

	/**
	 * Custom evaluators hand back their own text per patient through the evaluation context;
	 * everything else falls back to the flag's own message.
	 */
	@SuppressWarnings("unchecked")
	private String message(Flag flag, Integer patientId, Map<Object, Object> evaluationContext) {
		Object supplied = evaluationContext.get(patientId);
		if (supplied instanceof List) {
			List<String> messages = (List<String>) supplied;
			if (!messages.isEmpty()) {
				return messages.get(0);
			}
		}
		return flag.evalMessage(patientId);
	}
}
