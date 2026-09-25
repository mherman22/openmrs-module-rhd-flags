package org.openmrs.module.rhdflags.task;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.GlobalProperty;
import org.openmrs.api.context.Context;
import org.openmrs.module.cohort.CohortM;
import org.openmrs.module.cohort.CohortMember;
import org.openmrs.module.cohort.CohortType;
import org.openmrs.module.cohort.api.CohortMemberService;
import org.openmrs.module.cohort.api.CohortService;
import org.openmrs.module.cohort.api.CohortTypeService;
import org.openmrs.module.patientflags.Flag;
import org.openmrs.module.patientflags.PatientFlag;
import org.openmrs.module.patientflags.Tag;
import org.openmrs.module.patientflags.api.FlagService;
import org.openmrs.module.patientflags.evaluator.SQLFlagEvaluator;
import org.openmrs.test.BaseModuleContextSensitiveTest;

public class PatientFlagRefreshTaskContextTest extends BaseModuleContextSensitiveTest {

	private static final int MATCHING_PATIENT = 7;

	private static final int OTHER_PATIENT = 2;

	private static final String MATCHES_ONE = "select patient_id from patient where patient_id = " + MATCHING_PATIENT;

	private static final String MATCHES_NOBODY = "select patient_id from patient where patient_id = -1";

	private FlagService flagService;

	@Before
	public void setUp() {
		flagService = Context.getService(FlagService.class);
		saveCohortType("System List");
	}

	@Test
	public void keepsAPatientWhoseFlagWasVoidedOffTheList() {
		Flag flag = saveFlag("dismissed", MATCHES_NOBODY);
		voidedFlagFor(flag);

		runScheduledWork();

		assertEquals(1, count("select count(*) from patientflags_patient_flag where flag_id = " + flag.getFlagId()
		        + " and voided = true"));
		assertEquals(1, listsNamed("dismissed"));
		assertEquals(0, activeMembers("dismissed", MATCHING_PATIENT));
	}

	@Test
	public void raisesAFlagAgainWhenItsOnlyRowWasVoidedAndThePatientStillMatches() {
		Flag flag = saveFlag("still matching", MATCHES_ONE);
		voidedFlagFor(flag);

		runScheduledWork();

		assertEquals(1, count("select count(*) from patientflags_patient_flag where flag_id = " + flag.getFlagId()
		        + " and patient_id = " + MATCHING_PATIENT + " and voided = false"));
	}

	@Test
	public void listsAFlagsPatientsAfterARun() {
		saveFlag("overdue", MATCHES_ONE);

		runScheduledWork();

		assertEquals(1, activeMembers("overdue", MATCHING_PATIENT));
	}

	@Test
	public void endsTheListOfAFlagThatHasBeenDisabled() {
		Flag flag = saveFlag("overdue", MATCHES_ONE);
		runScheduledWork();
		assertEquals(1, activeMembers("overdue", MATCHING_PATIENT));

		flag = flagService.getFlag(flag.getFlagId());
		flag.setEnabled(Boolean.FALSE);
		flagService.saveFlag(flag);
		runScheduledWork();

		assertEquals(0, activeMembers("overdue", MATCHING_PATIENT));
	}

	@Test
	public void endsTheListOfAFlagThatHasBeenRetired() {
		Flag flag = saveFlag("overdue", MATCHES_ONE);
		runScheduledWork();
		assertEquals(1, activeMembers("overdue", MATCHING_PATIENT));

		flag = flagService.getFlag(flag.getFlagId());
		flag.setRetired(Boolean.TRUE);
		flag.setRetireReason("replaced");
		flagService.saveFlag(flag);
		runScheduledWork();

		assertEquals(0, activeMembers("overdue", MATCHING_PATIENT));
	}

	@Test
	public void endsTheListOfAFlagThatNoLongerCarriesTheListTag() {
		Tag tag = new Tag();
		tag.setName("worklist");
		flagService.saveTag(tag);
		Context.getAdministrationService().saveGlobalProperty(new GlobalProperty("rhdflags.listFlagTag", "worklist"));
		Flag flag = saveFlag("overdue", MATCHES_ONE);
		flag.setTags(new HashSet<Tag>(Collections.singleton(tag)));
		flagService.saveFlag(flag);
		runScheduledWork();
		assertEquals(1, activeMembers("overdue", MATCHING_PATIENT));

		flag = flagService.getFlag(flag.getFlagId());
		flag.getTags().clear();
		flagService.saveFlag(flag);
		runScheduledWork();

		assertEquals(0, activeMembers("overdue", MATCHING_PATIENT));
	}

	@Test
	public void followsAFlagThroughARenameWithoutASecondList() {
		Flag flag = saveFlag("overdue", MATCHES_ONE);
		runScheduledWork();

		flag = flagService.getFlag(flag.getFlagId());
		flag.setName("prophylaxis overdue");
		flagService.saveFlag(flag);
		runScheduledWork();

		assertEquals(0, listsNamed("overdue"));
		assertEquals(1, listsNamed("prophylaxis overdue"));
		assertEquals(1, activeMembers("prophylaxis overdue", MATCHING_PATIENT));
	}

	@Test
	public void listsFollowTwoFlagsThatSwapNames() {
		Flag first = saveFlag("overdue", MATCHES_ONE);
		Flag second = saveFlag("lost to follow-up", "select patient_id from patient where patient_id = " + OTHER_PATIENT);
		runScheduledWork();

		rename(first, "swapping");
		rename(second, "overdue");
		rename(first, "lost to follow-up");
		runScheduledWork();
		runScheduledWork();

		assertEquals(1, listsNamed("overdue"));
		assertEquals(1, activeMembers("overdue", OTHER_PATIENT));
		assertEquals(0, activeMembers("overdue", MATCHING_PATIENT));
		assertEquals(1, listsNamed("lost to follow-up"));
		assertEquals(1, activeMembers("lost to follow-up", MATCHING_PATIENT));
		assertEquals(0, activeMembers("lost to follow-up", OTHER_PATIENT));
	}

	@Test
	public void keepsItsNameWhenTheListInItsWayIsStuckBehindAHandMadeOne() {
		saveHandMadeList("lost to follow-up", OTHER_PATIENT);
		Flag first = saveFlag("overdue", MATCHES_ONE);
		Flag second = saveFlag("follow-up due", MATCHES_ONE);
		runScheduledWork();

		rename(second, "lost to follow-up");
		rename(first, "follow-up due");
		first = flagService.getFlag(first.getFlagId());
		first.setCriteria("select patient_id from patient where patient_id in (" + MATCHING_PATIENT + ", "
		        + OTHER_PATIENT + ")");
		flagService.saveFlag(first);
		runScheduledWork();
		runScheduledWork();

		assertEquals(1, listsNamed("overdue"));
		assertEquals(1, activeMembers("overdue", MATCHING_PATIENT));
		assertEquals(1, activeMembers("overdue", OTHER_PATIENT));
	}

	@Test
	public void leavesAHandMadeListWithTheFlagsNameAlone() {
		saveHandMadeList("overdue", OTHER_PATIENT);
		saveFlag("overdue", MATCHES_ONE);

		runScheduledWork();

		assertEquals(1, listsNamed("overdue"));
		assertEquals(1, activeMembers("overdue", OTHER_PATIENT));
		assertEquals(0, activeMembers("overdue", MATCHING_PATIENT));
	}

	@Test
	public void keepsTheOldNameWhenARenameWouldTakeAHandMadeListsName() {
		saveHandMadeList("prophylaxis overdue", OTHER_PATIENT);
		Flag flag = saveFlag("overdue", MATCHES_ONE);
		runScheduledWork();

		flag = flagService.getFlag(flag.getFlagId());
		flag.setName("prophylaxis overdue");
		flagService.saveFlag(flag);
		runScheduledWork();

		assertEquals(1, listsNamed("prophylaxis overdue"));
		assertEquals(1, activeMembers("prophylaxis overdue", OTHER_PATIENT));
		assertEquals(0, activeMembers("prophylaxis overdue", MATCHING_PATIENT));
		assertEquals(1, activeMembers("overdue", MATCHING_PATIENT));
	}

	@Test
	public void createsTheConfiguredCohortTypeWhenItIsMissing() {
		Context.getAdministrationService().saveGlobalProperty(
		    new GlobalProperty("rhdflags.listCohortType", "Flag Lists"));
		saveFlag("overdue", MATCHES_ONE);

		runScheduledWork();

		assertNotNull(Context.getService(CohortTypeService.class).getCohortTypeByName("Flag Lists"));
		assertEquals(1, activeMembers("overdue", MATCHING_PATIENT));
	}

	@Test
	public void doesNotRecreateACohortTypeThatWasVoided() {
		CohortTypeService cohortTypeService = Context.getService(CohortTypeService.class);
		cohortTypeService.voidCohortType(cohortTypeService.getCohortTypeByName("System List"), "not wanted");
		saveFlag("overdue", MATCHES_ONE);

		runScheduledWork();

		assertEquals(1, count("select count(*) from cohort_type where name = 'System List'"));
		assertEquals(0, listsNamed("overdue"));
	}

	@Test
	public void doesNotRecreateAListThatWasVoided() {
		saveFlag("overdue", MATCHES_ONE);
		runScheduledWork();
		CohortService cohortService = Context.getService(CohortService.class);
		cohortService.voidCohortM(cohortService.getCohortM("overdue"), "not wanted");

		runScheduledWork();

		assertEquals(1, count("select count(*) from cohort where name = 'overdue'"));
	}

	private void runScheduledWork() {
		Context.flushSession();
		new PatientFlagRefreshTask().execute();
	}

	private Flag saveFlag(String name, String criteria) {
		Flag flag = new Flag();
		flag.setName(name);
		flag.setCriteria(criteria);
		flag.setEvaluator(SQLFlagEvaluator.class.getName());
		flag.setMessage(name);
		flag.setEnabled(Boolean.TRUE);
		flagService.saveFlag(flag);
		return flag;
	}

	private void rename(Flag flag, String name) {
		Flag current = flagService.getFlag(flag.getFlagId());
		current.setName(name);
		flagService.saveFlag(current);
		Context.flushSession();
	}

	private void saveHandMadeList(String name, int patientId) {
		CohortM handMade = new CohortM();
		handMade.setName(name);
		handMade.setDescription("kept by hand");
		handMade.setCohortType(Context.getService(CohortTypeService.class).getCohortTypeByName("System List"));
		Context.getService(CohortService.class).saveCohortM(handMade);
		CohortMember member = new CohortMember();
		member.setCohort(handMade);
		member.setPatient(Context.getPatientService().getPatient(patientId));
		member.setStartDate(new Date());
		Context.getService(CohortMemberService.class).saveCohortMember(member);
	}

	private void voidedFlagFor(Flag flag) {
		PatientFlag patientFlag = new PatientFlag(Context.getPatientService().getPatient(MATCHING_PATIENT), flag,
		        flag.getMessage());
		flagService.savePatientFlag(patientFlag);
		flagService.voidPatientFlag(patientFlag, "dismissed");
	}

	private void saveCohortType(String name) {
		CohortType type = new CohortType();
		type.setName(name);
		type.setDescription(name);
		Context.getService(CohortTypeService.class).saveCohortType(type);
	}

	private long activeMembers(String listName, int patientId) {
		return count("select count(*) from cohort_member m join cohort c on c.cohort_id = m.cohort_id where c.name = '"
		        + listName + "' and c.voided = false and m.patient_id = " + patientId
		        + " and m.end_date is null and m.voided = false");
	}

	private long listsNamed(String name) {
		return count("select count(*) from cohort where voided = false and name = '" + name + "'");
	}

	private long count(String sql) {
		Context.flushSession();
		List<List<Object>> rows = Context.getAdministrationService().executeSQL(sql, true);
		return ((Number) rows.get(0).get(0)).longValue();
	}
}
