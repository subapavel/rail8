# Protecting the release branch on the git host

For the person who sets up the git host of a production pool. It shows how to protect, on GitLab,
GitHub and Azure DevOps, the branch that the pool's `RAIL8_GIT_REF` names, in every package repository.

## What Rail8 does and does not do

A production pool deploys only a commit that is reachable from its `RAIL8_GIT_REF` in that
repository. It never looks at whether the branch is protected, or who approved the pull request that
moved it. A repository whose branch is unprotected lets anything pushed to that branch be deployed.

Approval for production stays with the git host. The rule below must therefore hold in every package
repository of the pool, including the ones created later:

1. Nobody pushes to the branch directly.
2. Nobody force-pushes it or deletes it.
3. A pull request (merge request) into it needs an approval.

## Where the steps come from

Every step below was read on 2026-11-24 from the host's own documentation page named for it. Menu names,
plan limits and API fields change between versions: where a step does not work as written, the page named
for it is the reference. Where a page does not settle a point, the step says so and names what to check yourself.

## GitLab

Pages: [Protected branches](https://docs.gitlab.com/user/project/repository/branches/protected/),
[Protection rules](https://docs.gitlab.com/user/project/repository/branches/protection_rules/),
[Branch rules](https://docs.gitlab.com/user/project/repository/branches/branch_rules/),
[Group-level protected branches API](https://docs.gitlab.com/api/group_protected_branches/),
[Protected branches API](https://docs.gitlab.com/api/protected_branches/),
[Merge request approvals](https://docs.gitlab.com/user/project/merge_requests/approvals/),
[Merge request approval rules](https://docs.gitlab.com/user/project/merge_requests/approvals/rules/),
[Merge request approvals API](https://docs.gitlab.com/api/merge_request_approvals/),
[Merge request approval policies](https://docs.gitlab.com/user/application_security/policies/merge_request_approval_policies/),
[Groups API](https://docs.gitlab.com/api/groups/).

**Once for the group (Premium or Ultimate).**
- You need the Owner role on the group, and the group must be a top-level group; subgroups are not supported.
- Open the group, then Settings, Repository, and expand Protected branches. Select Add protected branch,
  enter the branch name (names are case-sensitive), set Allowed to merge to Maintainers (or Developers +
  Maintainers) and Allowed to push and merge to No one, leave Allowed to force push off, and select Protect.
- The page says these settings apply to all projects in the group and cannot be modified in a project. The
  group rule can be set through the API too: `POST /groups/:id/protected_branches` with `name`,
  `push_access_level=0` (No access) and `merge_access_level=40`; `allow_force_push` defaults to false.
- A project maintainer can still add a project rule for the same branch name. The rules are then evaluated
  together and, for most settings, the most permissive result applies, so a project rule can allow a push or a
  force push that the group rule forbids. The check below finds such projects.
- A protected branch can be deleted in the web interface or the API by a Maintainer or Owner (and not with
  local Git commands). Protection therefore does not stop those roles from deleting the branch. By default
  Maintainers and Owners can also unprotect it. The page describes an `unprotect_access_level` that only the API can set, to limit who
  can unprotect a branch.
- The documentation does not say whether a group rule for a branch that does not exist yet takes effect when
  the branch is created, or whether a project created later in the group gets the rule. Check one new project
  yourself before relying on it.

**Approval.**
- Free: approvals are optional and never block a merge, so the third rule cannot be met on Free.
- Premium and Ultimate: per project, Settings, Merge requests, Merge request approvals, Approval rules, Add
  approval rule. Set Approvals required above 0 (a value of 0 makes the rule optional), add the approvers, and
  set Target branch to the branch or to All protected branches. The documentation describes no group-wide
  setting for required approvals; group merge request approval settings are tracked in an open epic.
- Ultimate: a merge request approval policy created for the group applies to every project in it, unless its
  policy scope narrows that. Its rule applies to protected target branches, by name in `branches` or by
  `branch_type: protected`. Create it in the group under Secure, Policies. When a protected branch is created
  or deleted, the policy's approval rules synchronise with a delay of one minute.

**Repository by repository (Free, or a subgroup).** In each project: Settings, Repository, expand Branch
rules, Add branch rule, Branch name or pattern, choose the branch, Create branch rule. On the rule's page set
Allowed to merge to Maintainers and Allowed to push and merge to No one (a new rule starts with Maintainers
allowed to push), and leave Allowed to force push off. Then add the approval rule as above (Premium or
Ultimate). The default-branch protection covers only a project's default branch, so it protects `release`
only when that is the default branch. Project Settings, Repository, Protected branches is being removed in favour of Branch rules.

**A repository created later.** With the group rule, see the sentence above on new projects. Without it (Free,
subgroups), add the rule by hand to each new project; the check finds the ones that were missed.

**Check every repository.** With a token that can read the group's projects:
1. `GET /groups/:id/projects?include_subgroups=true` lists the projects (follow the pagination).
2. For each project, `GET /projects/:id/protected_branches?search=release`. The project must be in the list
   with `allow_force_push` false and every entry of `push_access_levels` at `access_level` 0. A group rule
   shows `inherited: true` (Premium and Ultimate). A project that is not listed has no rule.
3. Premium and Ultimate: `GET /projects/:id/approval_rules` must show a rule with `approvals_required` above 0
   whose `protected_branches` includes the branch or whose `applies_to_all_protected_branches` is true.
In the web interface the same is under each project's Settings, Repository, Branch rules, View details.

## GitHub

Pages: [About rulesets](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/about-rulesets),
[Available rules for rulesets](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets),
[Creating rulesets for a repository](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/creating-rulesets-for-a-repository),
[Creating rulesets for repositories in your organization](https://docs.github.com/en/organizations/managing-organization-settings/creating-rulesets-for-repositories-in-your-organization),
[About protected branches](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches),
[REST API endpoints for rules](https://docs.github.com/en/rest/repos/rules),
[REST API endpoints for organization rules](https://docs.github.com/en/rest/orgs/rules),
[REST API endpoints for repositories](https://docs.github.com/en/rest/repos/repos).

**Once for the organisation (GitHub Team or GitHub Enterprise).**
- Organisation owners create organisation rulesets. Organisation Settings, under Code, planning, and
  automation, Repository, Rulesets, New ruleset, New branch ruleset.
- Name the ruleset and set the enforcement status to Active (the other status the page lists is Disabled).
- Under Target repositories, Repository targeting criteria, select All repositories. Under Target branches,
  Add a target, include the branch by pattern (fnmatch syntax, for example `release`).
- Under Branch protections switch on: Restrict deletions (on by default), Block force pushes (on by
  default), and Require a pull request before merging, with Required approvals at 1 or more. Optionally
  switch on Require approval of the most recent reviewable push, which needs an approval from someone other than
  the last person to push. The page says that, with required reviews, collaborators can only push changes to
  the branch through an approved pull request. Leave the bypass list empty.
- Up to 75 organisation rulesets and 75 rulesets per repository are allowed. Rulesets, and the protected-branch rules, layer: all of them apply, and where
  the same rule differs the most restrictive version applies. A repository administrator can add rulesets to a
  repository that an organisation ruleset targets, but only make the result more restrictive.
- Repository targeting by name pattern or by property filter is described as a dynamic list. The
  page does not say whether All repositories also covers repositories created later; check one new
  repository yourself.

**Repository by repository.** Repository rulesets work in public repositories on GitHub Free, and in
public and private repositories on GitHub Pro, Team and Enterprise Cloud. Protected branches (the older branch
protection rules) are available in the same repositories. Neither is available for a private repository
owned by a Free organisation, so such a repository cannot be protected and needs a paid plan. In each
repository: Settings, under Code, planning, and automation, Rulesets, New ruleset, New branch ruleset, then the
same name, enforcement, target branch and rules as above.

**A repository created later.** With a repository ruleset, add it by hand to each new repository; the check
finds the ones that were missed. For an organisation ruleset, see the sentence above.

**Check every repository.** The endpoint `GET /repos/{owner}/{repo}/rules/branches/{branch}` returns every
active rule that applies to the branch, from repository and organisation rulesets alike. The branch does not
need to exist. Rulesets set to Disabled or Evaluate are not returned. For each repository from
`GET /orgs/{org}/repos` (paginated), the answer must contain the rule types `deletion`, `non_fast_forward`
and `pull_request`, and the `pull_request` rule's parameters must show `required_approving_review_count` of 1
or more. It needs a token with read access to the repository's metadata.

## Azure DevOps

Pages: [Set and manage branch policies](https://learn.microsoft.com/en-us/azure/devops/repos/git/branch-policies?view=azure-devops),
[Set Git Repository Settings](https://learn.microsoft.com/en-us/azure/devops/repos/git/repository-settings?view=azure-devops),
[Set Git branch security and permissions](https://learn.microsoft.com/en-us/azure/devops/repos/git/branch-permissions?view=azure-devops),
[Set Git repository permissions](https://learn.microsoft.com/en-us/azure/devops/repos/git/set-git-repository-permissions?view=azure-devops),
[Secure repositories and pull requests](https://learn.microsoft.com/en-us/azure/devops/repos/git/secure-repositories-pull-requests?view=azure-devops).

**Once for the project.**
- You need membership in Project Administrators, or the Edit policies permission.
- Project settings, Repositories, All Repositories, Policies tab; under Branch Policies select the plus sign.
  On Add branch protection select Protect current and future branches matching a specified pattern, and enter the branch
  name (case-sensitive; the page says the branch does not have to exist yet). Select Create.
- On the Cross-Repository policies page for the branch, set Require a minimum number of reviewers to On with
  at least 1 reviewer (Microsoft recommends two for main and release branches), leave Allow requestors to
  approve their own changes off, and select Require at least one approval on the last iteration. Prohibit the
  most recent pusher from approving their own changes closes the case of a pusher approving their own change.
- The page says a branch with required policies cannot be deleted and all changes to it must go through pull
  requests. Only a user with Bypass policies when pushing can push directly, and only a user with Bypass
  policies when completing pull requests can override the policies on a pull request. Both are set under
  Project settings, Repositories, All Repositories, Security (or one repository's Security) or under the
  branch's Branch security; allow them only for a small admin group. Force push, which rewrites history,
  needs its own permission: deny it too.
- A user who creates a branch gets, on that branch, explicit Contribute, Force push, Manage permissions and
  Remove others' locks, which override what the repository gives. Review these on `release` after it is created.
- The page says a policy at project level for All Repositories is the default for repositories added later, and the
  Add branch protection step above covers future branches by pattern. It does not say in so many words that a
  repository created later is covered by the cross-repository branch policy; check one new repository yourself.

**Repository by repository.** Project settings, Repositories, select the repository, then Policies, Branch
Policies, select the branch (or Repos, Branches, More options, Branch policies), and turn on Require a
minimum number of reviewers as above. A policy at repository level affects only that repository. Set
permissions under the repository's Security. Settings made at both levels are honoured at the most restrictive.

**A repository created later.** See the last point under Once for the project.

**Check every repository.**
1. `az repos list --project <project> --output table` lists the repositories with their ids.
2. For each, `az repos policy list --repository-id <id> --branch release --output table` must show a
   Minimum number of reviewers policy with Is Blocking True and Is Enabled True. The command needs the
   Azure DevOps CLI extension; the page says its commands are not supported for Azure DevOps Server.
3. `az repos policy show --id <policy id>` shows `minimumApproverCount`. A cross-repository policy has
   `repositoryId` null in its `scope`. Run `az repos policy list --project <project>` without a repository
   to see all the project's policies.
4. Permissions: under each repository's Security, no one but the admin group has Allow for Bypass policies
   when pushing, Bypass policies when completing pull requests and Force push; check the branch's Branch security too.

## Checking, whatever the host

Besides the checks above, test the rule on a repository that was just created: try to push directly to the
branch, force-push it, delete it, and merge a pull request with no approval. Each must be refused.
