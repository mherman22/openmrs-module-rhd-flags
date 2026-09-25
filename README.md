# OpenMRS RHD Flags module

[![Build with Maven](https://github.com/mherman22/openmrs-module-rhd-flags/actions/workflows/build.yml/badge.svg)](https://github.com/mherman22/openmrs-module-rhd-flags/actions/workflows/build.yml)

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

## What it does

**RHD Patient Flag Refresh** runs once a day. It re-evaluates every enabled flag, writes only the
rows that changed, and mirrors each flag into a patient list of the same name. It registers itself
with the scheduler on first start, because Initializer has no domain for `scheduler_task_config`.

Writing only the difference means this task resets a row's `date_created` only when the row's
message has changed. patientflags itself resets it far more often: its AOP advice deletes and
re-inserts a patient's rows on every clinical write, and saving a flag rebuilds all of that flag's
rows. So `date_created` is not a record of how long a flag has been raised, and this task alone
cannot make it one. Fixes for that are proposed upstream; see below.

### Flag rows

A row whose message has changed, such as a SQL flag whose `${n}` placeholders now evaluate to
other values, is rewritten. The task therefore evaluates the message of every matching patient on
each run, which for a SQL flag with placeholders is one query per patient.

Voided patient flag rows are ignored: a patient counts as flagged only through a live row.

### Lists

Membership comes from the live flag rows rather than from re-running the criteria, so a list and
the patient chart never disagree. Removal from a list that is kept end-dates a membership rather
than voiding it, because the cohort module's REST resource counts a voided row when it rejects a
duplicate.

Each list carries its flag's uuid, so a rename renames the list, and a cohort someone made by hand
under the same name is left alone (the cohort module rejects the duplicate name, so that flag gets
no list until one of the two is renamed). A rename onto such a name keeps the list's old name. When
flags swap or rotate names, one list steps aside to a temporary name so the others can move: a two-
flag swap settles within two runs, and a longer rotation takes more.

A flag that is disabled, retired or no longer tagged keeps its list with every membership ended. A
flag that is deleted has its list voided, memberships included; a `Source patient flag` cohort
attribute holding the flag's uuid marks the lists this module made, so a hand-made cohort is never
voided. A flag recreated under the deleted flag's uuid, as Initializer does, gets that list back
with the flag's current patients, unless another cohort already holds the flag's name. A list
someone voided stays voided.

### Upstream

Two of the reasons this module exists are defects in patientflags rather than facts of life, and
both have fixes proposed against it: a schedulable task, and generation that reconciles instead of
deleting and rebuilding. If those land, this module keeps the list syncing and sheds most of the
reconciliation logic.

## Requirements

OpenMRS platform 2.4.0 or later, patientflags 3.0.10, cohort 3.7.3.

## Installing

Build the module, then either drop the omod into the running instance:

    cp omod/target/rhdflags-omod-*.omod /openmrs/data/modules/

or, in a distribution, mount it alongside the other modules and restart the backend. Once it
starts it registers its own scheduled task, so there is nothing to configure to get it running.

Flags whose criteria have become true show up on the patient chart as usual, and each flag also
appears under **Patient lists** as a list of the patients currently carrying it.

## Configuration

| global property | default | meaning |
| --- | --- | --- |
| `rhdflags.listFlagTag` | empty | only give a list to flags carrying this tag; empty means all |
| `rhdflags.listCohortType` | `System List` | cohort type for lists this module creates; created if missing, unless a voided type has that name |

The task runs once a day. The scheduler owns the interval once the task exists, so change it in
**Administration > Manage Scheduler** rather than here.

## Building

    mvn clean install

The module is `omod/target/rhdflags-omod-*.omod`.

## License

[MPL 2.0 with the OpenMRS Healthcare Disclaimer](LICENSE).
