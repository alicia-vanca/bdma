package com.app.common.modules.foldermanager.dtos;

import java.nio.file.Path;

/**
 * Result of path resolution that distinguishes among a successfully resolved
 * path,
 * a path that does not exist, and a resolution failure caused by an exception.
 * <p>
 * Instances are created through the static factory methods rather than by
 * calling the
 * constructor directly. This keeps result creation consistent and preserves the
 * intended
 * state combinations for found, not-found, and error outcomes.
 */
public class PathResolutionResult {
    /**
     * Resolved path when resolution succeeds; {@code null} for not-found and error
     * results.
     */
    private final Path path;

    /**
     * Indicates that resolution completed normally but the target path does not
     * exist.
     */
    private final boolean notFound;

    /**
     * Exception captured when path resolution fails unexpectedly; {@code null}
     * otherwise.
     */
    private final Exception error;

    /**
     * Creates a new immutable resolution result.
     * <p>
     * The constructor is private so callers must use the named factory methods
     * {@link #found(Path)}, {@link #notFound()}, and {@link #error(Exception)} to
     * create
     * valid result instances with well-defined state.
     *
     * @param path     resolved path for a successful result, or {@code null}
     * @param notFound {@code true} when the target path was not found
     * @param error    captured exception for a failed resolution, or {@code null}
     */
    private PathResolutionResult(Path path, boolean notFound, Exception error) {
        this.path = path;
        this.notFound = notFound;
        this.error = error;
    }

    /**
     * Creates a result representing a successfully resolved path.
     *
     * @param path resolved path
     * @return result that reports {@link #isFound()} as {@code true}
     */
    public static PathResolutionResult found(Path path) {
        return new PathResolutionResult(path, false, null);
    }

    /**
     * Creates a result representing a path that was not found.
     *
     * @return result that reports {@link #isNotFound()} as {@code true}
     */
    public static PathResolutionResult notFound() {
        return new PathResolutionResult(null, true, null);
    }

    /**
     * Creates a result representing a resolution failure caused by an exception.
     *
     * @param error exception that prevented path resolution
     * @return result that reports {@link #isError()} as {@code true}
     */
    public static PathResolutionResult error(Exception error) {
        return new PathResolutionResult(null, false, error);
    }

    /**
     * Indicates whether path resolution succeeded.
     *
     * @return {@code true} when a resolved path is available
     */
    public boolean isFound() {
        return path != null;
    }

    /**
     * Indicates whether resolution completed without error but no path was found.
     *
     * @return {@code true} when the target path does not exist
     */
    public boolean isNotFound() {
        return notFound;
    }

    /**
     * Indicates whether resolution failed due to an exception.
     *
     * @return {@code true} when an error is available from {@link #getError()}
     */
    public boolean isError() {
        return error != null;
    }

    /**
     * Returns the resolved path for a successful result.
     *
     * @return resolved path, or {@code null} when the result is not successful
     */
    public Path getPath() {
        return path;
    }

    /**
     * Returns the exception that caused resolution to fail.
     *
     * @return captured exception, or {@code null} when no error occurred
     */
    public Exception getError() {
        return error;
    }
}
