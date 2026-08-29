package com.epam.codereviewagent.util;

import com.epam.codereviewagent.exception.FileNotFoundInRepositoryException;
import com.epam.codereviewagent.exception.PathSecurityViolationException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RepositoryPathResolverTest {

  private static final String FIXTURE_ROOT = "src/test/resources/fixtures/repo-root";

  private RepositoryPathResolver resolver;

  @BeforeEach
  void setUp() {
    resolver = new RepositoryPathResolver(FIXTURE_ROOT);
  }

  // --- Construction -------------------------------------------------------------------------

  @Test
  void shouldThrowIllegalStateException_whenConfiguredRootDoesNotExist(@TempDir Path tempDir) {
    Path missingRoot = tempDir.resolve("does-not-exist");

    assertThatThrownBy(() -> new RepositoryPathResolver(missingRoot.toString()))
      .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void shouldThrowIllegalStateException_whenConfiguredRootIsARegularFile() throws IOException {
    Path fileNotDirectory = Path.of(FIXTURE_ROOT, "top-level.txt");
    assertThat(Files.isRegularFile(fileNotDirectory)).isTrue();

    assertThatThrownBy(() -> new RepositoryPathResolver(fileNotDirectory.toString()))
      .isInstanceOf(IllegalStateException.class);
  }

  // --- Security rejections (resolveFile) -----------------------------------------------------

  @Test
  void shouldRejectPathTraversal_whenRelativePathEscapesRootWithDotDot() {
    assertSecurityViolation("../../etc/passwd");
  }

  @Test
  void shouldRejectWindowsAbsolutePath_whenPathHasDriveLetterAndBackslash() {
    assertSecurityViolation("C:\\Windows\\System32\\drivers\\etc\\hosts");
  }

  @Test
  void shouldRejectUncPath_whenPathIsNetworkShare() {
    assertSecurityViolation("\\\\server\\share\\x");
  }

  @Test
  void shouldRejectPosixAbsolutePath_whenPathHasLeadingSlash() {
    assertSecurityViolation("/etc/passwd");
  }

  @Test
  void shouldRejectBlankPath_whenInputIsEmptyString() {
    assertSecurityViolation("");
  }

  @Test
  void shouldRejectWhitespaceOnlyPath_whenInputIsBlank() {
    assertSecurityViolation("   ");
  }

  @Test
  void shouldRejectPathContainingNulCharacter_whenInputHasEmbeddedNul() {
    String pathWithNul = "evil" + (char) 0 + ".txt";

    assertSecurityViolation(pathWithNul);
  }

  @Test
  void shouldRejectDriveRelativePath_whenPathOmitsBackslashAfterDriveLetter() {
    // "C:foo" is neither absolute per java.nio.file.Path.isAbsolute() nor traversal-based, but is a
    // Windows drive-qualified path that must still be rejected as a security violation, not silently
    // resolved relative to the drive's own current directory.
    assertSecurityViolation("C:foo");
  }

  @Test
  void shouldRejectPathWithIllegalCharacters_whenPathContainsAlternateDataStreamColon() {
    // On Windows, java.nio.file.Path.of(...) itself rejects an embedded ':' outside the drive-letter
    // position (verified directly: throws InvalidPathException) - this proves that failure is
    // converted into a PathSecurityViolationException rather than escaping as a raw NIO exception.
    assertSecurityViolation("notes.txt:hidden-stream");
  }

  @Test
  void shouldRejectSecurityViolationBeforeExistenceCheck_whenAbsolutePathPointsToAnExistingFileOutsideRoot() {
    Path realFileOutsideRoot = Path.of("pom.xml").toAbsolutePath().normalize();
    // Sanity check: prove the target genuinely exists, so a security violation here can only be
    // explained by rejection happening before any existence check - not by the file being missing.
    assertThat(Files.exists(realFileOutsideRoot)).isTrue();

    assertThatThrownBy(() -> resolver.resolveFile(realFileOutsideRoot.toString()))
      .isInstanceOf(PathSecurityViolationException.class)
      .isNotInstanceOf(FileNotFoundInRepositoryException.class);
  }

  // --- Positive / not-found (resolveFile) ------------------------------------------------------

  @Test
  void shouldAcceptValidNestedRelativePath_whenPathIsInsideRoot() {
    Path resolved = resolver.resolveFile("nested/nested-file.txt");

    assertThat(resolved).startsWith(resolver.getRoot());
    assertThat(Files.isRegularFile(resolved)).isTrue();
  }

  @Test
  void shouldThrowFileNotFoundInRepository_whenPathIsSyntacticallyValidButDoesNotExist() {
    assertThatThrownBy(() -> resolver.resolveFile("nested/does-not-exist.txt"))
      .isInstanceOf(FileNotFoundInRepositoryException.class);
  }

  @Test
  void shouldThrowFileNotFoundInRepository_whenPathPointsAtDirectoryNotRegularFile() {
    assertThatThrownBy(() -> resolver.resolveFile("nested"))
      .isInstanceOf(FileNotFoundInRepositoryException.class);
  }

  // --- Reserved Windows device names (measured, not assumed) -------------------------------------
  //
  // The plan's code review flagged that a prior claim about Windows reserved-device-name handling
  // (CON, NUL, COM1, ...) was reasoned from first principles rather than measured. These tests
  // measure the actual behavior of resolveFile(...) against this fixture tree on this host
  // (JDK corretto-21, Windows 11) instead of asserting an assumption. Measured directly, for every
  // form below: Files.isRegularFile(candidate) returns false for a reserved device name resolved
  // under an ordinary directory (the JDK/NTFS never treats it as an existing regular file at that
  // path), so resolveFile(...) throws FileNotFoundInRepositoryException with message
  // "File not found in repository: <input>" - the same not-found path as any other nonexistent
  // filename, before canonicalizeAndVerifyContainment/toRealPath is ever reached. No device name in
  // this set is accepted, so no separate reserved-name rejection was needed in
  // RepositoryPathResolver.

  @Test
  void shouldThrowFileNotFoundInRepository_whenPathIsBareReservedDeviceNameCon() {
    assertThatThrownBy(() -> resolver.resolveFile("CON"))
      .isInstanceOf(FileNotFoundInRepositoryException.class)
      .isNotInstanceOf(PathSecurityViolationException.class)
      .hasMessage("File not found in repository: CON");
  }

  @Test
  void shouldThrowFileNotFoundInRepository_whenPathIsBareReservedDeviceNameNul() {
    assertThatThrownBy(() -> resolver.resolveFile("NUL"))
      .isInstanceOf(FileNotFoundInRepositoryException.class)
      .isNotInstanceOf(PathSecurityViolationException.class)
      .hasMessage("File not found in repository: NUL");
  }

  @Test
  void shouldThrowFileNotFoundInRepository_whenPathIsReservedCommunicationsPortNameCom1() {
    assertThatThrownBy(() -> resolver.resolveFile("COM1"))
      .isInstanceOf(FileNotFoundInRepositoryException.class)
      .isNotInstanceOf(PathSecurityViolationException.class)
      .hasMessage("File not found in repository: COM1");
  }

  @Test
  void shouldThrowFileNotFoundInRepository_whenReservedDeviceNameHasFileExtension() {
    assertThatThrownBy(() -> resolver.resolveFile("CON.txt"))
      .isInstanceOf(FileNotFoundInRepositoryException.class)
      .isNotInstanceOf(PathSecurityViolationException.class)
      .hasMessage("File not found in repository: CON.txt");
  }

  @Test
  void shouldThrowFileNotFoundInRepository_whenReservedDeviceNameIsNestedInASubdirectory() {
    assertThatThrownBy(() -> resolver.resolveFile("nested/CON"))
      .isInstanceOf(FileNotFoundInRepositoryException.class)
      .isNotInstanceOf(PathSecurityViolationException.class)
      .hasMessage("File not found in repository: nested/CON");
  }

  // --- validateSecurityBoundary (security-only pre-check, retry 1 High finding) -----------------
  //
  // Delegates directly to the same private validateAndResolve(...) resolveFile/listImmediateEntries
  // already use, so these tests exist to prove the public entry point's own contract - not to
  // re-derive the underlying security rule, which is already exhaustively covered above.

  @Test
  void shouldRejectPathTraversal_whenValidateSecurityBoundaryIsCalledWithDotDotEscape() {
    assertThatThrownBy(() -> resolver.validateSecurityBoundary("../../etc/passwd"))
      .isInstanceOf(PathSecurityViolationException.class);
  }

  @Test
  void shouldRejectAbsolutePath_whenValidateSecurityBoundaryIsCalledWithAWindowsAbsolutePath() {
    assertThatThrownBy(
      () -> resolver.validateSecurityBoundary("C:\\Windows\\System32\\drivers\\etc\\hosts"))
      .isInstanceOf(PathSecurityViolationException.class);
  }

  @Test
  void shouldRejectBlankInput_whenValidateSecurityBoundaryIsCalledWithAnEmptyString() {
    assertThatThrownBy(() -> resolver.validateSecurityBoundary(""))
      .isInstanceOf(PathSecurityViolationException.class);
  }

  @Test
  void shouldRejectPathContainingNulCharacter_whenValidateSecurityBoundaryIsCalledWithEmbeddedNul() {
    String pathWithNul = "evil" + (char) 0 + ".txt";

    assertThatThrownBy(() -> resolver.validateSecurityBoundary(pathWithNul))
      .isInstanceOf(PathSecurityViolationException.class);
  }

  @Test
  void shouldNotThrow_whenValidateSecurityBoundaryIsCalledWithAnOrdinaryInRootRelativePath() {
    // Neither existence nor "file vs directory" is checked by this method - proved separately below -
    // but an ordinary, unremarkable in-root path must not throw either.
    assertThatCode(() -> resolver.validateSecurityBoundary("nested/nested-file.txt"))
      .doesNotThrowAnyException();
  }

  @Test
  void shouldNotThrow_whenValidateSecurityBoundaryIsCalledWithAnInRootDirectoryPath() {
    // A directory (not a regular file) must be accepted - the ticket's own "a relative file or
    // repository path" wording covers directories too, and this pre-check must not reject the
    // repository-exploration use case.
    assertThatCode(() -> resolver.validateSecurityBoundary("nested"))
      .doesNotThrowAnyException();
  }

  @Test
  void shouldNotThrow_whenValidateSecurityBoundaryIsCalledWithASyntacticallyValidButNonExistentPath() {
    // Existence is deliberately out of scope for this security-only check - a missing file must still
    // reach the agent so it can honestly report "file not found" itself (Experiment #4's own
    // dependency on this, per the coordinator's decision).
    assertThatCode(() -> resolver.validateSecurityBoundary("nested/does-not-exist.txt"))
      .doesNotThrowAnyException();
  }

  // --- Symlink escape (availability-gated) -----------------------------------------------------

  @Test
  void shouldRejectSymlinkEscape_whenLinkTargetsFileOutsideRepositoryRoot(@TempDir Path tempDir)
    throws IOException {
    Path allowedRoot = Files.createDirectory(tempDir.resolve("allowed"));
    Path outsideFile = Files.writeString(tempDir.resolve("outside.txt"), "secret content");
    Path link = allowedRoot.resolve("escape-link.txt");

    try {
      Files.createSymbolicLink(link, outsideFile);
    } catch (UnsupportedOperationException | IOException | SecurityException e) {
      Assumptions.assumeTrue(false,
        "Symbolic-link creation is unavailable on this host: " + e.getClass().getSimpleName());
      return;
    }

    RepositoryPathResolver symlinkAwareResolver = new RepositoryPathResolver(allowedRoot.toString());

    assertThatThrownBy(() -> symlinkAwareResolver.resolveFile("escape-link.txt"))
      .isInstanceOf(PathSecurityViolationException.class);
  }

  // --- listImmediateEntries ---------------------------------------------------------------------

  @Test
  void shouldListImmediateEntries_whenDirectoryIsValid() {
    List<RepositoryPathResolver.RepositoryEntry> entries = resolver.listImmediateEntries(".", 10);

    assertThat(entries).extracting(RepositoryPathResolver.RepositoryEntry::name)
      .containsExactlyInAnyOrder("top-level.txt", "empty.txt", "nested");
    assertThat(entries)
      .filteredOn(entry -> entry.name().equals("nested"))
      .extracting(RepositoryPathResolver.RepositoryEntry::directory)
      .containsExactly(true);
    assertThat(entries)
      .filteredOn(entry -> entry.name().equals("top-level.txt"))
      .extracting(RepositoryPathResolver.RepositoryEntry::directory)
      .containsExactly(false);
  }

  @Test
  void shouldBoundListingToMaxEntries_whenLimitIsSmallerThanEntryCount() {
    List<RepositoryPathResolver.RepositoryEntry> entries = resolver.listImmediateEntries(".", 2);

    assertThat(entries).hasSize(2);
  }

  @Test
  void shouldThrowFileNotFoundInRepository_whenListedDirectoryDoesNotExist() {
    assertThatThrownBy(() -> resolver.listImmediateEntries("does-not-exist-dir", 10))
      .isInstanceOf(FileNotFoundInRepositoryException.class);
  }

  @Test
  void shouldThrowFileNotFoundInRepository_whenListedPathIsARegularFileNotDirectory() {
    assertThatThrownBy(() -> resolver.listImmediateEntries("top-level.txt", 10))
      .isInstanceOf(FileNotFoundInRepositoryException.class);
  }

  @Test
  void shouldRejectPathSecurityViolation_whenListedDirectoryPathEscapesRoot() {
    assertThatThrownBy(() -> resolver.listImmediateEntries("../..", 10))
      .isInstanceOf(PathSecurityViolationException.class);
  }

  @Test
  void shouldThrowIllegalArgumentException_whenMaxEntriesIsZero() {
    assertThatThrownBy(() -> resolver.listImmediateEntries(".", 0))
      .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldThrowIllegalArgumentException_whenMaxEntriesIsNegative() {
    assertThatThrownBy(() -> resolver.listImmediateEntries(".", -1))
      .isInstanceOf(IllegalArgumentException.class);
  }

  private void assertSecurityViolation(String input) {
    assertThatThrownBy(() -> resolver.resolveFile(input))
      .isInstanceOf(PathSecurityViolationException.class);
  }
}
