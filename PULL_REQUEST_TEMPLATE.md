<!-- Comment:
A great PR typically begins with the line below.
-->

<!-- in case you works on Jira issue, replace XXXXX with the numeric part of the issue ID you created in Jira -->
See [JENKINS-XXXXX](https://issues.jenkins.io/browse/JENKINS-XXXXX).
<!-- in case you works on github issue -->
See #XXXXX
<!-- in case this PR will complete Github issue use close #### ->

<!-- Comment:
If the issue is not fully described in Jira / Github, add more information here (justification, pull request links, etc.).

 * We do not require Jira / Github issues for minor improvements.
 * Bug fixes should have a Jira / Github issue to facilitate the backporting process.
 * Major new features should have a Jira / Github issue.
-->

### Testing done

<!-- Comment:
Provide a clear description of how this change was tested.
At minimum this should include proof that a computer has executed the changed lines.
Ideally this should include an automated test or an explanation as to why this change has no tests.
Note that automated test coverage is less than complete, so a successful PR build does not necessarily imply that a computer has executed the changed lines.
If automated test coverage does not exist for the lines you are changing, **you must describe** the scenario(s) in which you manually tested the change.
For frontend changes, include screenshots of the relevant page(s) before and after the change.
For refactoring and code cleanup changes, exercise the code before and after the change and verify the behavior remains the same.
-->

### Proposed changelog entries

- Entry 1: Issue, human-readable text
- […]

<!-- Comment:
The changelog entry should be in the imperative mood; e.g., write "do this"/"return that" rather than "does this"/"returns that".
For examples, see: https://www.jenkins.io/changelog/
-->

### Proposed upgrade guidelines

N/A

### Localizations

<!-- Comment:
To translate this plugin are used awesome tool named Crowdin. At the moment there are limited users permitted to use it.
Be sure any localizations are moved ot *.properties files.
Please describe here which language has been translated by you.
English text's are mandatory
-->

- [ ] English
- […]

### Submitter checklist

- [ ] The Jira / Github issue, if it exists, is well-described.
- [ ] The changelog entries and upgrade guidelines are appropriate for the audience affected by the change (users or developers, depending on the change) and are in the imperative mood (see [examples](https://github.com/jenkins-infra/jenkins.io/blob/master/content/_data/changelogs/weekly.yml)).
  - Fill in the **Proposed upgrade guidelines** section only if there are breaking changes or changes that may require extra steps from users during upgrade.
- [ ] There is automated testing or an explanation as to why this change has no tests.
- [ ] New public classes, fields, and methods are annotated with `@Restricted` or have `@since TODO` Javadocs, as appropriate.
- [ ] New deprecations are annotated with `@Deprecated(since = "TODO")` or `@Deprecated(forRemoval = true, since = "TODO")`, if applicable.
- [ ] New or substantially changed JavaScript is not defined inline and does not call `eval` to ease future introduction of Content Security Policy (CSP) directives (see [documentation](https://www.jenkins.io/doc/developer/security/csp/)).
- [ ] For dependency updates, there are links to external changelogs and, if possible, full differentials.
- [ ] For new APIs and extension points, there is a link to at least one consumer.
- [ ] Any localizations are transfered to *.properties files.

### Maintainer checklist

Before the changes are marked as `ready-for-merge`:

- [ ] There is at least one (1) approval for the pull request and no outstanding requests for change.
- [ ] Conversations in the pull request are over, or it is explicit that a reviewer is not blocking the change.
- [ ] Changelog entries in the pull request title and/or **Proposed changelog entries** are accurate, human-readable, and in the imperative mood.
- [ ] Proper changelog labels are set so that the changelog can be generated automatically.
- [ ] If the change needs additional upgrade steps from users, the `upgrade-guide-needed` label is set and there is a **Proposed upgrade guidelines** section in the pull request title (see [example](https://github.com/jenkinsci/jenkins/pull/4387)).
- [ ] java code changes are tested by automated test.
