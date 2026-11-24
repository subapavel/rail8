package dev.rail8.runtime.access;

import java.util.Objects;
import java.util.Optional;

/**
 * The package a permission check asks about. Every check takes one and ignores it for now: a role holds for the whole
 * installation.
 *
 * @param name the package's name, or empty for any package
 */
public record PackageRef(Optional<String> name) {

    /** Any package: the check of an act that names no package, or does not know it yet. */
    public static final PackageRef ANY = new PackageRef(Optional.empty());

    public PackageRef {
        Objects.requireNonNull(name, "name");
    }

    /** The package of a qualified flow id, {@code <package>/<flow>}: the part before the first slash; any package without one. */
    public static PackageRef ofFlow(String qualifiedFlow) {
        int slash = qualifiedFlow.indexOf('/');
        return slash > 0 ? new PackageRef(Optional.of(qualifiedFlow.substring(0, slash))) : ANY;
    }
}
