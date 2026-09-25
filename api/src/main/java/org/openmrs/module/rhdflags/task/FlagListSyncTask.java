package org.openmrs.module.rhdflags.task;

import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.openmrs.Patient;
import org.openmrs.api.context.Context;
import org.openmrs.module.cohort.CohortM;
import org.openmrs.module.cohort.CohortMember;
import org.openmrs.module.cohort.CohortType;
import org.openmrs.module.cohort.api.CohortMemberService;
import org.openmrs.module.cohort.api.CohortService;
import org.openmrs.module.cohort.api.CohortTypeService;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.scheduler.tasks.AbstractTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mirrors each patient flag into a patient list of the same name.
 *
 * Cohort membership is static and nothing recomputes it, so a list only keeps matching its flag
 * if something keeps the two in step. Membership is taken from the flags the module has already
 * evaluated rather than from the criteria, so a list and the patient chart never disagree.
 *
 * Limit which flags get a list with the rhdflags.listFlagTag global property. An empty value
 * gives every enabled flag a list.
 */
public class FlagListSyncTask extends AbstractTask {

	public static final String TAG_PROPERTY = "rhdflags.listFlagTag";

	public static final String COHORT_TYPE_PROPERTY = "rhdflags.listCohortType";

	private static final String DEFAULT_COHORT_TYPE = "System List";

	private static final Logger log = LoggerFactory.getLogger(FlagListSyncTask.class);

	@Override
	public void execute() {
		if (!Context.isAuthenticated()) {
			log.warn("Skipping flag list sync: the scheduler session is not authenticated");
			return;
		}

		FlagService flagService = Context.getService(FlagService.class);
		String requiredTag = Context.getAdministrationService().getGlobalProperty(TAG_PROPERTY);

		for (Flag flag : flagService.getAllFlags()) {
			if (!Boolean.TRUE.equals(flag.getEnabled()) || Boolean.TRUE.equals(flag.getRetired())) {
				continue;
			}
			if (!carriesTag(flag, requiredTag)) {
				continue;
			}
			try {
				sync(flag);
			}
			catch (Exception e) {
				log.error("Could not sync the list for flag '{}'", flag.getName(), e);
			}
		}
	}

	private boolean carriesTag(Flag flag, String requiredTag) {
		if (requiredTag == null || requiredTag.trim().isEmpty()) {
			return true;
		}
		if (flag.getTags() == null) {
			return false;
		}
		for (org.openmrs.module.patientflags.Tag tag : flag.getTags()) {
			if (requiredTag.trim().equalsIgnoreCase(tag.getName())) {
				return true;
			}
		}
		return false;
	}

	void sync(Flag flag) {
		CohortService cohortService = Context.getService(CohortService.class);
		CohortMemberService memberService = Context.getService(CohortMemberService.class);

		CohortM list = cohortService.getCohortM(flag.getName());
		if (list == null) {
			list = createList(cohortService, flag);
		}

		Set<Integer> flagged = flaggedPatientIds(flag);
		Map<Integer, CohortMember> active = activeMembers(memberService, list);

		int added = 0;
		for (Integer patientId : flagged) {
			if (!active.containsKey(patientId)) {
				CohortMember member = new CohortMember();
				member.setCohort(list);
				member.setPatient(Context.getPatientService().getPatient(patientId));
				member.setStartDate(new Date());
				memberService.saveCohortMember(member);
				added++;
			}
		}

		// End-dating rather than voiding: the module counts a voided row when it rejects a
		// duplicate, so a voided member could never rejoin the list.
		int removed = 0;
		for (Map.Entry<Integer, CohortMember> entry : active.entrySet()) {
			if (!flagged.contains(entry.getKey())) {
				CohortMember member = entry.getValue();
				member.setEndDate(new Date());
				memberService.saveCohortMember(member);
				removed++;
			}
		}

		if (added > 0 || removed > 0) {
			log.info("List '{}': {} added, {} ended", flag.getName(), added, removed);
		}
	}

	private CohortM createList(CohortService cohortService, Flag flag) {
		CohortM list = new CohortM();
		list.setName(flag.getName());
		list.setDescription("Patients currently flagged: " + flag.getName());
		list.setGroupCohort(Boolean.FALSE);
		list.setCohortType(cohortType());
		log.info("Creating list '{}'", flag.getName());
		return cohortService.saveCohortM(list);
	}

	private CohortType cohortType() {
		String configured = Context.getAdministrationService().getGlobalProperty(COHORT_TYPE_PROPERTY);
		String wanted = (configured == null || configured.trim().isEmpty()) ? DEFAULT_COHORT_TYPE : configured.trim();
		CohortTypeService cohortTypeService = Context.getService(CohortTypeService.class);
		for (CohortType type : cohortTypeService.findAllCohortTypes()) {
			if (wanted.equalsIgnoreCase(type.getName())) {
				return type;
			}
		}
		throw new IllegalStateException("No cohort type named '" + wanted + "'");
	}

	private Map<Integer, CohortMember> activeMembers(CohortMemberService memberService, CohortM list) {
		Map<Integer, CohortMember> active = new HashMap<Integer, CohortMember>();
		Collection<CohortMember> members = memberService.findCohortMembersByCohortUuid(list.getUuid());
		if (members != null) {
			for (CohortMember member : members) {
				if (Boolean.TRUE.equals(member.getVoided()) || member.getEndDate() != null) {
					continue;
				}
				Patient patient = member.getPatient();
				if (patient != null) {
					active.put(patient.getPatientId(), member);
				}
			}
		}
		return active;
	}

	private Set<Integer> flaggedPatientIds(Flag flag) {
		Set<Integer> patientIds = new HashSet<Integer>();
		List<List<Object>> rows = Context.getAdministrationService().executeSQL(
		    "select patient_id from patientflags_patient_flag where flag_id = " + flag.getFlagId(), true);
		if (rows != null) {
			for (List<Object> row : rows) {
				if (row != null && !row.isEmpty() && row.get(0) != null) {
					patientIds.add(((Number) row.get(0)).intValue());
				}
			}
		}
		return patientIds;
	}
}
