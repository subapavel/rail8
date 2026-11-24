package dev.rail8.runtime.deploy;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What {@link Deployments#deploy} did: the package as deployed now and, by qualified flow id, sorted, the flows it
 * started for the first time ({@code added}), those it stopped and started again on the new version ({@code swapped}) and those it
 * stopped because the new version no longer has them ({@code removed}). A flow in none of the three runs on as it was: its
 * effective hash did not change. A first deploy adds every flow.
 *
 * @param pkg the package deployed
 * @param added flows new to the package
 * @param swapped flows whose effective hash changed
 * @param removed flows the new version no longer has
 * @param replaces for a deploy from git and its check, the commit of the pool's record of the package that the deploy replaced,
 *                 or {@link Deployments#NONE}; empty for a deploy with no origin
 * @since 0.1
 */
public record DeployResult(DeployedPackage pkg, List<String> added, List<String> swapped, List<String> removed, Optional<String> replaces) {
    public DeployResult {
        Objects.requireNonNull(pkg, "pkg");
        added = added.stream().sorted().toList();
        swapped = swapped.stream().sorted().toList();
        removed = removed.stream().sorted().toList();
        Objects.requireNonNull(replaces, "replaces");
    }
}
