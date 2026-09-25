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

## Tasks

**RHD Patient Flag Refresh** re-evaluates every enabled flag and writes only the difference.

This matters beyond scheduling. Both of patientflags' own generation paths delete a flag's rows
before rebuilding them, so `date_created` on a row whose patient never stopped matching is reset
on every evaluation, including on ordinary clinical writes. Anything reporting how long a flag
has been raised is therefore wrong. This task adds and removes only what changed, so a row's
`date_created` keeps meaning the time that patient started matching.

**RHD Flag List Sync** mirrors each flag into a patient list of the same name, creating the list
if it is missing. Membership comes from the flags already evaluated rather than from re-running
the criteria, so a list and the patient chart never disagree.

Removal end-dates a membership rather than voiding it. The cohort module counts a voided row when
it rejects a duplicate, so a voided member could never rejoin the list.

Both tasks register themselves with the scheduler on first start, because Initializer has no
domain for `scheduler_task_config`.

## Configuration

| global property | default | meaning |
| --- | --- | --- |
| `rhdflags.refreshIntervalSeconds` | `86400` | how often both tasks run |
| `rhdflags.listFlagTag` | empty | only give a list to flags carrying this tag; empty means all |
| `rhdflags.listCohortType` | `System List` | cohort type for lists this module creates |

The interval is read when a task is first registered. Change it afterwards in
**Administration > Manage Scheduler**.

## Requirements

OpenMRS platform 2.4.0 or later, patientflags 3.0.10, cohort 3.7.3.

## Building

    mvn clean install

The module is `omod/target/rhdflags-omod-*.omod`.
