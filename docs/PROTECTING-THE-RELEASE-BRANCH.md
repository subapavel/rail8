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

## How the steps were taken, and what is not confirmed

The steps come from each host's own documentation. In the session that wrote this guide the documentation
sites themselves could not be opened (the host name did not resolve), so each page could not be read in
full. What is written as a step or a limit below is what the documentation's search-result excerpts of the
named pages stated. Everything else is marked **Not confirmed**, with what was tried. Before relying on a
step, open the page named for it. Menu names and plan limits change between versions.

## GitLab

Pages: [Protected branches](https://docs.gitlab.com/user/project/repository/branches/protected/),
[Group-level protected branches API](https://docs.gitlab.com/api/group_protected_branches/),
[Default branch](https://docs.gitlab.com/user/project/repository/branches/default/),
[Merge request approval rules](https://docs.gitlab.com/user/project/merge_requests/approvals/rules/),
[Merge request approvals](https://docs.gitlab.com/user/project/merge_requests/approvals/).

**Once for the group.**
- You need the Owner role on the group, and the group must be a top-level group; subgroups are not
  supported. The tier named for this is Premium or Ultimate.
- Open the group, then Settings, Repository, and expand Protected branches. Enter the branch name, choose the
  roles allowed to push and to merge, and select Add protected branch. Allow "No one" to push.
- The page says these settings apply to all projects in the group and cannot be modified in a project.
- Force pushes are blocked unless `allow_force_push` is set; its documented default is false.
- Not confirmed: that a rule for a branch that does not exist yet takes effect when the branch is created,
  and that a project created later in the group gets the rule. The excerpts say the rule applies to all projects in
  the group, nothing more.
- Not confirmed: whether a protected branch can be deleted. The excerpts did not say.

**Approval.** Per project, under Settings, Merge requests, Merge request approvals: add an approval rule
with the number of approvals required (above 0 is a required rule) and a target branch, "All protected
branches" or one branch. Required rules need Premium or Ultimate; on Free approvals are optional. Not
confirmed: any group-wide way to require approvals on every project. Merge request approval policies
(Ultimate) can be enforced on protected target branches; the page was not read in full.

**Repository by repository (Free, or a subgroup).** Protect the branch in each project, and add the
approval rule as above. The Default branch page says the default-branch setting covers only a project's
default branch, so it does not protect `release` unless that is the default branch. Not confirmed: the
exact project-level menu, because the excerpts say project protected-branch settings are moving to
Settings, Repository, Branch rules.

**A repository created later.** Not confirmed. The excerpts say only that the rule applies to "all projects in
the group". On Free, the rule has to be added by hand to each new project, and the check finds the ones that
were missed.

**Check every repository.** Not confirmed: the endpoints. The Protected branches API page and the
Groups API page were named in the search results but not read. The Group-level protected branches API
page exists for the group rule.

## GitHub

Pages: [About rulesets](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/about-rulesets),
[Available rules for rulesets](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets),
[Creating rulesets for repositories in your organization](https://docs.github.com/en/organizations/managing-organization-settings/creating-rulesets-for-repositories-in-your-organization),
[REST API endpoints for rules](https://docs.github.com/en/rest/orgs/rules).

**Once for the organisation.**
- Use an organisation ruleset. The documentation presents organisation-wide rulesets as a Team and
  Enterprise feature. Rulesets work in public repositories on Free, and in public and private
  repositories on Pro, Team and Enterprise Cloud. Up to 75 organisation rulesets are allowed.
- In the ruleset's Target repositories section choose All repositories. The Target branches section is
  not confirmed in detail; target the branch `RAIL8_GIT_REF` names.
- Rules to switch on: restrict deletions and block force pushes (both on by default per the rules page);
  require a pull request before merging with the number of required approvals; leave the bypass list empty.
  The rules page lists "require approval of the most recent reviewable push" (an approval from someone other
  than the last person to push), which closes the case of a pusher approving their own change.
- Not confirmed: the exact name of the rule that stops direct pushes (the required pull request rule is
  the one that does it in the pages read); the ruleset's enforcement status names; whether a ruleset
  for a branch that does not exist yet applies when the branch is created.
- Rules from several sources add up, and the most restrictive version of a rule applies.

**A repository created later.** The documentation page on organisation rulesets names All repositories as a
targeting option; a rollout guide for another feature says the choice covers existing and future
repositories. Not confirmed on the rulesets page itself.

**Repository by repository (Free organisations, private repositories).** Not confirmed: the repository ruleset
or branch protection rule steps. The repository ruleset page and the branch protection rule page were named
by search but not read. The 75 limit per repository is documented.

**Check every repository.** Not confirmed. The REST page for rules exists; the "rules for a branch"
endpoint was not read.

## Azure DevOps

Pages: [Set and manage branch policies](https://learn.microsoft.com/en-us/azure/devops/repos/git/branch-policies?view=azure-devops),
[Set Git Repository Settings](https://learn.microsoft.com/en-us/azure/devops/repos/git/repository-settings?view=azure-devops),
[Set Git branch security and permissions](https://learn.microsoft.com/en-us/azure/devops/repos/git/branch-permissions?view=azure-devops),
[Set Git repository permissions](https://learn.microsoft.com/en-us/azure/devops/repos/git/set-git-repository-permissions?view=azure-devops),
[Secure repositories and pull requests](https://learn.microsoft.com/en-us/azure/devops/repos/git/secure-repositories-pull-requests?view=azure-devops).

**Once for the project.**
- You need the Edit policies permission, or Project Administrators membership.
- Project settings, Repositories, the All Repositories page, the Policies tab; under Branch Policies select
  the plus sign. On Add branch protection choose to protect current and future branches that match a
  pattern, and enter the branch name. A policy at project level affects all repositories in the project.
- Turn on Minimum number of reviewers there. Microsoft recommends two reviewers for main and release
  branches, no self-approval, and an approval on the last iteration.
- Direct pushes and deletion are governed by permissions, not by policies. Force push is the permission
  that allows deleting a branch and rewriting its history; deny it. Contribute allows pushing new commits.
  Deny "Bypass policies when pushing" and "Bypass policies when completing pull requests" to everyone but a small admin group.
  A branch creator gets explicit permissions on the branch, including Force push and both bypass permissions,
  which override the repository-level setting.
- Not confirmed: where in the UI to set these permissions for the whole project, and whether a branch
  policy alone blocks a direct push by someone who has not been denied Contribute.

**Repository by repository.** Project settings, Repositories, select the repository, select the branch,
and turn on the policy under Policies. A policy at repository level affects only that repository.

**A repository created later.** Not confirmed. The excerpts say only that a policy at project level
"affects all repositories in the project"; whether a repository created later falls under it was not stated.

**Check every repository.** `az repos policy` sets policies from the command line, and a policy whose
`repositoryId` is null with a `refName` applies to all repositories in the project. The Policy
Configurations REST pages exist. Not confirmed: the list command and the policy type id.

## Checking, whatever the host

Until the host-specific checks above are confirmed, test the rule on every repository: try to push
directly to the branch, force-push it, delete it, and merge a pull request with no approval. Each must be
refused.
