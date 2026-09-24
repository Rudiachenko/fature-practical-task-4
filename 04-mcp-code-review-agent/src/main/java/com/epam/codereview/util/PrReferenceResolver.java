package com.epam.codereview.util;

import com.epam.codereview.exception.PrReferenceNotFoundException;
import java.util.regex.Pattern;

/**
 * Enforces a request-boundary gate for the incoming {@code userInput} free text: rejects it only
 * when it carries <b>zero</b> PR-shaped signal at all. Mirrors {@code 03-code-review-agent}'s
 * {@code RepositoryPathResolver}: shape-only validation, never completeness, and never a reason to
 * throw other than "no signal at all" (see {@code context/PLAN.md}'s "PR-reference validation
 * boundary" Architecture Note).
 *
 * <p>{@link #validatePrReferencePresent(String)} recognizes three independent weak signals, any one
 * of which is enough to accept:
 * <ol>
 *   <li>A {@code github.com/{owner}/{repo}/pull/{number}} URL fragment (with or without a
 *   scheme/host prefix, e.g. {@code https://github.com/octocat/Hello-World/pull/42}).</li>
 *   <li>A bare or embedded {@code #<digits>} token, e.g. {@code #42} or {@code repo#123}.</li>
 *   <li>An {@code owner/repo}-shaped slug, e.g. {@code octocat/Hello-World}.</li>
 * </ol>
 *
 * <p>This class never attempts to extract a repository owner, name, or PR number from
 * {@code userInput}, and never reports which of the three signals (if any) matched - it is a pure
 * accept/reject gate, not a parser. An input that carries only a weak or incomplete signal (e.g. a
 * bare PR number with no repository context) is deliberately accepted rather than rejected: the
 * agent itself reasons about and reports what, if anything, is still missing, rather than this
 * class silently guessing or rejecting an otherwise-plausible request upfront.
 */
public final class PrReferenceResolver {

  /**
   * Matches a GitHub pull-request URL fragment, e.g. {@code github.com/owner/repo/pull/42}, with
   * or without a leading scheme/host prefix such as {@code https://}. Case-insensitive, since a
   * host name and the literal {@code pull} path segment are not case-sensitive in practice.
   */
  private static final Pattern GITHUB_PR_URL_FRAGMENT = Pattern.compile(
    "github\\.com/[\\w.-]+/[\\w.-]+/pull/\\d+", Pattern.CASE_INSENSITIVE);

  /**
   * Matches a bare or embedded {@code #<digits>} token, e.g. {@code #42} or {@code repo#123} - the
   * shorthand GitHub itself uses for a PR/issue reference.
   */
  private static final Pattern ISSUE_OR_PR_NUMBER_TOKEN = Pattern.compile("#\\d+");

  /**
   * Matches an {@code owner/repo}-shaped slug, e.g. {@code octocat/Hello-World}. Deliberately
   * permissive (a single {@code /}-separated pair of word/dot/hyphen segments anywhere in the
   * input) - a false-positive accept here is harmless per this class's own "accept anything with
   * even one weak signal" contract, while a false-negative reject is not.
   */
  private static final Pattern OWNER_REPO_SLUG = Pattern.compile("[\\w.-]+/[\\w.-]+");

  /**
   * Rejects {@code userInput} only when none of this class's three weak signals (see class
   * Javadoc) are present anywhere in it.
   *
   * @param userInput the free-text request body to check; {@code null} or blank is always rejected
   * @throws PrReferenceNotFoundException if {@code userInput} is blank or carries no PR-shaped
   *                                       signal at all
   */
  public void validatePrReferencePresent(String userInput) {
    if (userInput == null || userInput.isBlank()) {
      throw new PrReferenceNotFoundException("No PR reference found in request: input is blank");
    }
    if (GITHUB_PR_URL_FRAGMENT.matcher(userInput).find()
      || ISSUE_OR_PR_NUMBER_TOKEN.matcher(userInput).find()
      || OWNER_REPO_SLUG.matcher(userInput).find()) {
      return;
    }
    throw new PrReferenceNotFoundException("No PR reference found in request: expected a GitHub "
      + "PR URL, an owner/repo slug, or a #<number> reference");
  }
}
