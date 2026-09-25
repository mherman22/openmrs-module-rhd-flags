package org.openmrs.module.rhdflags.task;

import java.util.Collection;
import java.util.Collections;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mirrors each patient flag into a patient list of the same name.
 *
 * Cohort membership is static and nothing recomputes it, so a list only keeps matching its flag
 * if something keeps the two in step. Membership is taken from the live flag rows rather than
 * from the criteria.
 *
 * A list shares its flag's uuid, so it follows the flag through a rename and a cohort someone
 * made by hand under the same name is never touched. A flag that is disabled, retired or missing
 * the rhdflags.listFlagTag tag keeps its list, emptied.
 */
public class FlagListSync {

	public static final String TAG_PROPERTY = "rhdflags.listFlagTag";

	public static final String COHORT_TYPE_PROPERTY = "rhdflags.listCohortType";

	private static final String DEFAULT_COHORT_TYPE = "System List";

	private static final Logger log = LoggerFactory.getLogger(FlagListSync.class);

	public void syncAll() {
		FlagService flagService = Context.getService(FlagService.class);
		String requiredTag = Context.getAdministrationService().getGlobalProperty(TAG_PROPERTY);

		for (Flag flag : flagService.getAllFlags()) {
			boolean listed = Boolean.TRUE.equals(flag.getEnabled()) && !Boolean.TRUE.equals(flag.getRetired())
			        && carriesTag(flag, requiredTag);
			try {
				sync(flag, listed);
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

	void sync(Flag flag, boolean listed) {
		CohortService cohortService = Context.getService(CohortService.class);
		CohortMemberService memberService = Context.getService(CohortMemberService.class);

		CohortM list = cohortService.getCohortMByUuid(flag.getUuid());
		if (list == null) {
			// Recreating a list someone voided would collide with its uuid, and would undo their choice.
			if (!listed || listWasVoided(flag)) {
				return;
			}
			list = createList(cohortService, flag);
		} else if (!flag.getName().equals(list.getName())) {
			// Checked before touching the list: a rejected save leaves the new name dirty in the
			// session, and the next commit writes it anyway.
			CohortM holder = cohortService.getCohortM(flag.getName());
			if (holder == null || holder.getUuid().equals(list.getUuid())) {
				list.setName(flag.getName());
				list.setDescription(description(flag));
				cohortService.saveCohortM(list);
			} else if (inRenameCycle(cohortService, holder, list)) {
				// Flags that swap or rotate names block each other's lists for good unless one of
				// them steps aside, freeing its old name for the next in the cycle.
				list.setName(flag.getName() + " (" + flag.getUuid() + ")");
				cohortService.saveCohortM(list);
			} else {
				log.warn("List '{}' keeps its name: another cohort is already called '{}'", list.getName(),
				    flag.getName());
			}
		}

		Set<Integer> flagged = listed ? PatientFlagRefreshTask.flaggedPatientIds(flag) : Collections.<Integer> emptySet();
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

		// End-dating rather than voiding: the cohort module's REST resource counts a voided row
		// when it rejects a duplicate.
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

	/**
	 * Whether following each list in the way to the name its flag now has leads back to this list.
	 */
	private boolean inRenameCycle(CohortService cohortService, CohortM holder, CohortM list) {
		FlagService flagService = Context.getService(FlagService.class);
		Set<String> seen = new HashSet<String>();
		while (seen.add(holder.getUuid())) {
			Flag owner = flagService.getFlagByUuid(holder.getUuid());
			if (owner == null) {
				return false;
			}
			CohortM next = cohortService.getCohortM(owner.getName());
			if (next == null) {
				return false;
			}
			if (next.getUuid().equals(list.getUuid())) {
				return true;
			}
			holder = next;
		}
		return false;
	}

	private CohortM createList(CohortService cohortService, Flag flag) {
		CohortM list = new CohortM();
		list.setUuid(flag.getUuid());
		list.setName(flag.getName());
		list.setDescription(description(flag));
		list.setGroupCohort(Boolean.FALSE);
		list.setCohortType(cohortType());
		log.info("Creating list '{}'", flag.getName());
		return cohortService.saveCohortM(list);
	}

	private String description(Flag flag) {
		return "Patients currently flagged: " + flag.getName();
	}

	/**
	 * The cohort module's CohortService only finds unvoided cohorts by uuid.
	 */
	private boolean listWasVoided(Flag flag) {
		List<List<Object>> rows = Context.getAdministrationService().executeSQL(
		    "select count(*) from cohort where uuid = '" + flag.getUuid().replace("'", "''") + "'", true);
		return ((Number) rows.get(0).get(0)).intValue() > 0;
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
		if (cohortTypeService.getCohortTypeByName(wanted, true) != null) {
			throw new IllegalStateException("The cohort type '" + wanted + "' has been voided");
		}
		CohortType type = new CohortType();
		type.setName(wanted);
		type.setDescription("Patient lists kept in step with patient flags");
		log.info("Creating cohort type '{}'", wanted);
		return cohortTypeService.saveCohortType(type);
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
}
