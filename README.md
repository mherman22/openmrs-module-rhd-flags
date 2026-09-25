# OpenMRS RHD Flags module

Scheduled maintenance for patient flags, and for the patient lists built from them.

## Why this exists

The patientflags module evaluates a flag when its definition is saved, and otherwise through
AOP advice on `EncounterService`, `ObsService`, `OrderService`, `PatientService`,
`ConditionService` and `ProgramWorkflowService`. That covers any flag whose criteria turn on a
data change. It does not cover a criterion that becomes true because time passed, such as an
injection that is now overdue for its regimen interval, or a patient who has not been seen for
210 days. Nothing is written to those records on the day they start matching, so the flag never
fires until somebody happens to touch the patient for an unrelated reason.

The module also ships a `PatientFlagTask`, but it is a `DaemonToken` runnable rather than an
`org.openmrs.scheduler.Task`, so it cannot be registered with the scheduler, and its admin
rebuild page sits behind CSRFGuard so it cannot be driven from a script.

## Task

**RHD Patient Flag Refresh** re-evaluates every enabled flag and writes only the difference, then
mirrors each flag into a patient list of the same name.

Writing only the difference means this task never resets a row's `date_created`. patientflags
itself still does: its AOP advice deletes and re-inserts a patient's rows on every clinical write,
and saving a flag rebuilds all of that flag's rows. So `date_created` is not a record of how long a
flag has been raised.

A row keeps the message it was written with while its patient keeps matching. A SQL flag whose
message uses `${n}` placeholders therefore shows the values from the day it was raised, until
patientflags rewrites the row on the patient's next clinical write.

Voided patient flag rows are ignored: a patient counts as flagged only through a live row.

Each list carries its flag's uuid, so a rename renames the list, and a cohort someone made by hand
under the same name is left alone (the cohort module rejects the duplicate name, so that flag gets
no list until one of the two is renamed). A rename onto such a name keeps the list's old name. When
two flags swap names, their lists catch up within two runs. A flag that is disabled, retired or no
longer tagged keeps its list with every membership ended. A list someone voided stays voided.
Membership comes from the flags already evaluated rather than from re-running the criteria, so a
list and the patient chart never disagree.

Removal end-dates a membership rather than voiding it. The cohort module counts a voided row when
it rejects a duplicate, so a voided member could never rejoin the list.

The task registers itself with the scheduler on first start, because Initializer has no domain for
`scheduler_task_config`.

## Configuration

| global property | default | meaning |
| --- | --- | --- |
| `rhdflags.refreshIntervalSeconds` | `86400` | how often the task runs |
| `rhdflags.listFlagTag` | empty | only give a list to flags carrying this tag; empty means all |
| `rhdflags.listCohortType` | `System List` | cohort type for lists this module creates; created if missing, unless a voided type has that name |

The interval is read when a task is first registered. Change it afterwards in
**Administration > Manage Scheduler**.

## Requirements

OpenMRS platform 2.4.0 or later, patientflags 3.0.10, cohort 3.7.3.

## Building

    mvn clean install

The module is `omod/target/rhdflags-omod-*.omod`.
