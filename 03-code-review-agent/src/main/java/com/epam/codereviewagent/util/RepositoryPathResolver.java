package com.epam.codereviewagent.util;

import com.epam.codereviewagent.exception.FileNotFoundInRepositoryException;
import com.epam.codereviewagent.exception.PathSecurityViolationException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lombok.Getter;

/**
 * Enforces a hard repository-root security boundary for every file/directory access requested
 * by a relative path. Replaces the previous multi-candidate path-guessing approach: exactly one
 * deterministic rule decides whether an input is safe, per {@code context/PLAN.md} Architecture
 * Note A1:
 * <ol>
 *   <li>Reject blank input and any input containing a NUL character.</li>
 *   <li>Reject any absolute or drive-qualified path outright (Windows drive-absolute, Windows
 *   drive-relative, UNC, POSIX leading {@code /}) &mdash; the ticket requires a <em>relative</em>
 *   path, so absolute input is a security violation, not merely "outside root".</li>
 *   <li>Resolve {@code root.resolve(input).normalize()} and reject if the result is not the root
 *   itself or under it.</li>
 *   <li>Steps 1-3 always run and reject before any filesystem-existence check, so a rejected path
 *   never distinguishes "exists but forbidden" from "does not exist".</li>
 *   <li>If the path passes security but does not exist / is not the expected kind, a different
 *   exception ({@link FileNotFoundInRepositoryException}) is thrown than a security violation
 *   ({@link PathSecurityViolationException}).</li>
 *   <li>If the path exists, it is canonicalized with
 *   {@link Path#toRealPath(java.nio.file.LinkOption...)} and re-checked against the root's own
 *   real path, to defeat symlink escape.</li>
 * </ol>
 */
@Getter
public final class RepositoryPathResolver {

  /**
   * Matches a leading single-letter drive designator followed by a colon (e.g., {@code C:} or
   * {@code C:foo} or {@code C:\Windows}), which on Windows is treated by {@code java.nio.file.Path}
   * as either fully absolute or "drive-relative" (relative to that drive's current directory) - a
   * traversal vector that {@link Path#isAbsolute()} does not reliably flag by itself.
   */
  private static final Pattern DRIVE_LETTER_PREFIX = Pattern.compile("^[A-Za-z]:.*");

  /**
   * The canonical (real, symlink-resolved) repository root this resolver is bound to.
   */
  private final Path root;

  /**
   * Constructs a resolver bound to {@code repositoryRoot}, canonicalizing it once. Fails fast
   * if the configured root does not exist or is not a directory.
   *
   * @param repositoryRoot the configured repository root, absolute or relative to the process
   *                        working directory
   * @throws IllegalStateException if the configured root does not exist, is not a directory, or
   *                                cannot be canonicalized
   */
  public RepositoryPathResolver(String repositoryRoot) {
    Objects.requireNonNull(repositoryRoot, "repositoryRoot must not be null");
    Path configuredRoot = Path.of(repositoryRoot).toAbsolutePath().normalize();
    if (!Files.isDirectory(configuredRoot)) {
      throw new IllegalStateException(
        "Configured repository root does not exist or is not a directory: " + configuredRoot);
    }
    try {
      this.root = configuredRoot.toRealPath();
    } catch (IOException e) {
      throw new IllegalStateException(
        "Configured repository root could not be canonicalized: " + configuredRoot, e);
    }
  }

  /**
   * Resolves {@code relativePath} to an existing regular file strictly within the repository root.
   *
   * @param relativePath a path relative to the repository root
   * @return the canonical (real) path of the resolved file
   * @throws PathSecurityViolationException   if {@code relativePath} fails the security boundary
   * @throws FileNotFoundInRepositoryException if {@code relativePath} passes the security boundary
   *                                            but does not resolve to an existing regular file
   */
  public Path resolveFile(String relativePath) {
    Path candidate = validateAndResolve(relativePath);
    // Files.isRegularFile already returns false for a non-existent path, so a separate Files.exists
    // check adds no additional rejection here.
    if (!Files.isRegularFile(candidate)) {
      throw new FileNotFoundInRepositoryException("File not found in repository: " + relativePath);
    }
    return canonicalizeAndVerifyContainment(candidate, relativePath);
  }

  /**
   * Security-only pre-check: runs exactly the same boundary rule as steps 1-3 of this class's
   * algorithm (blank/whitespace input, an embedded NUL character, any absolute or
   * drive-qualified form - Windows drive-absolute, Windows drive-relative, UNC, POSIX leading
   * {@code /} - an alternate-data-stream-shaped colon, and {@code ..} containment against the
   * canonical root once normalized) but, unlike {@link #resolveFile(String)}/
   * {@link #listImmediateEntries(String, int)}, never touches the filesystem: it does not
   * require {@code relativePath} to exist, and does not require it to be a regular file rather
   * than a directory.
   *
   * <p>Delegates directly to the same private {@link #validateAndResolve(String)} method those two
   * methods already call, so there is exactly one copy of the security rule in this class, never a
   * second copy that could silently drift from it.
   *
   * <p>Intended for an upfront, inexpensive rejection of an out-of-root or malformed
   * {@code userInput} - e.g., by {@code CodeReviewController}, before the request ever reaches
   * the agent/model - so a caller can observe the repository-root security boundary via a
   * deterministic HTTP status code alone, with no model call spent on input that is rejected by
   * construction. A syntactically valid, in-root path that does not (yet) exist, or that names a
   * directory rather than a file, deliberately passes this check without error: existence/kind
   * is intentionally out of scope here, since a missing file must still reach the agent so it
   * can honestly report "file not found" itself (see {@link FileNotFoundInRepositoryException}),
   * rather than being silently rejected upfront as if it were a security concern.
   *
   * @param relativePath a path relative to the repository root
   * @throws PathSecurityViolationException if {@code relativePath} fails the security boundary
   */
  public void validateSecurityBoundary(String relativePath) {
    validateAndResolve(relativePath);
  }

  /**
   * Lists the immediate (non-recursive) entries of an existing directory strictly within the
   * repository root, bounded to at most {@code maxEntries} entries, sorted by name.
   *
   * @param relativeDirectoryPath a directory path relative to the repository root
   * @param maxEntries            the maximum number of entries to return (must be positive)
   * @return an immutable, bounded, name-sorted list of immediate entries
   * @throws PathSecurityViolationException    if {@code relativeDirectoryPath} fails the
   *                                            security boundary
   * @throws FileNotFoundInRepositoryException if {@code relativeDirectoryPath} passes the
   *                                            security boundary but does not resolve to an
   *                                            existing directory
   */
  public List<RepositoryEntry> listImmediateEntries(String relativeDirectoryPath, int maxEntries) {
    if (maxEntries <= 0) {
      throw new IllegalArgumentException("maxEntries must be positive");
    }
    Path candidate = validateAndResolve(relativeDirectoryPath);
    // Files.isDirectory already returns false for a non-existent path, so a separate Files.exists
    // check adds no additional rejection here.
    if (!Files.isDirectory(candidate)) {
      throw new FileNotFoundInRepositoryException(
        "Directory not found in repository: " + relativeDirectoryPath);
    }
    Path real = canonicalizeAndVerifyContainment(candidate, relativeDirectoryPath);
    try (Stream<Path> entries = Files.list(real)) {
      // Deliberate trade-off: Files.list(...).sorted(...) materializes the full directory
      // listing before limit() applies, so this is O(n log n) in the directory's total entry
      // count regardless of maxEntries. Chosen anyway because deterministic, name-sorted output
      // is required for reproducible tool results; the fixture/repo scale here is small enough that
      // bounding before sorting (which would make the result order depend on filesystem
      // iteration order) is not worth the loss of determinism.
      return entries
        .sorted(Comparator.comparing(path -> path.getFileName().toString(),
          String.CASE_INSENSITIVE_ORDER))
        .limit(maxEntries)
        .map(path -> new RepositoryEntry(path.getFileName().toString(), Files.isDirectory(path)))
        .toList();
    } catch (IOException e) {
      throw new FileNotFoundInRepositoryException(
        "Directory could not be listed: " + relativeDirectoryPath, e);
    }
  }

  /**
   * Runs the security boundary (steps 1-3) and returns the normalized, in-root candidate path.
   * Never performs a filesystem-existence check.
   */
  private Path validateAndResolve(String relativePath) {
    if (relativePath == null || relativePath.isBlank()) {
      throw new PathSecurityViolationException("Path must not be blank");
    }
    if (relativePath.indexOf(0) >= 0) {
      throw new PathSecurityViolationException("Path must not contain a NUL character");
    }
    if (looksAbsoluteOrDriveQualified(relativePath)) {
      throw new PathSecurityViolationException("Absolute or drive-qualified paths are not allowed: "
        + relativePath);
    }

    Path resolved;
    try {
      resolved = root.resolve(relativePath).normalize();
    } catch (InvalidPathException e) {
      throw new PathSecurityViolationException(
        "Path is not a valid file system path: " + relativePath, e);
    }
    if (escapesRoot(resolved)) {
      throw new PathSecurityViolationException("Path escapes the repository root: " + relativePath);
    }
    return resolved;
  }

  /**
   * Detects every absolute-or-drive-qualified form of input this resolver must reject before
   * ever consulting the filesystem: POSIX-style leading {@code /}, a leading {@code \} (Windows
   * root-without-drive, and the common prefix of a UNC path), any {@code <letter>:} drive
   * designator (covers both fully-absolute {@code C:\...} and drive-relative {@code C:foo},
   * since {@link Path#isAbsolute()} alone does not flag the drive-relative form on Windows), and
   * finally falls back to {@link Path#isAbsolute()} as a defense-in-depth catch-all. Also
   * converts a malformed path string (e.g., one containing a colon-delimited alternate-data-stream
   * suffix such as {@code notes.txt:hidden}) into a security violation instead of letting
   * {@link InvalidPathException} escape uncaught.
   */
  private boolean looksAbsoluteOrDriveQualified(String candidate) {
    if (candidate.startsWith("/") || candidate.startsWith("\\")) {
      return true;
    }
    if (DRIVE_LETTER_PREFIX.matcher(candidate).matches()) {
      return true;
    }
    try {
      return Path.of(candidate).isAbsolute();
    } catch (InvalidPathException e) {
      throw new PathSecurityViolationException(
        "Path contains characters that are not permitted: " + candidate, e);
    }
  }

  private boolean escapesRoot(Path candidate) {
    return !candidate.equals(root) && !candidate.startsWith(root);
  }

  /**
   * Step 6 of Architecture Note A1: canonicalizes an existing, already security-checked
   * candidate with {@code toRealPath()} and re-checks containment, defeating a symlink whose
   * target lies outside the repository root.
   */
  private Path canonicalizeAndVerifyContainment(Path candidate, String originalInput) {
    Path real;
    try {
      real = candidate.toRealPath();
    } catch (IOException e) {
      throw new FileNotFoundInRepositoryException(
        "File not found in repository: " + originalInput, e);
    }
    if (escapesRoot(real)) {
      throw new PathSecurityViolationException(
        "Path escapes the repository root via a symbolic link: " + originalInput);
    }
    return real;
  }

  /**
   * A single immediate directory entry as returned by {@link #listImmediateEntries(String, int)}.
   *
   * @param name      the entry's file name (no path components)
   * @param directory {@code true} if the entry is a directory
   */
  public record RepositoryEntry(String name, boolean directory) {
  }
}
