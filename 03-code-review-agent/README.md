# Module 3: AI Agents (Agentic Systems)

## Goal:
Implement an **LLM-powered Code Review Agent** capable of performing structured analysis of source code using an **agentic workflow** that combines reasoning with tool usage.
The agent must analyze repository code, retrieve contextual information via tools, and produce evidence-based review results.

## Requirements:
- Implement an **LLM-powered Code Review Agent** capable of multi-step reasoning using tool interactions.
- Provide an interface (REST API or CLI) to submit a code review request containing a relative file or repository path.
- Validate input paths and restrict access to files within the repository root only.
- The agent MUST execute a tool-assisted workflow, including:
  - exploring repository content,
  - retrieving contextual information,
  - analyzing code,
  - generating review results.
- Implement tools covering at minimum:
  - file reading capability,
  - contextual data retrieval (e.g., conventions or guidance),
  - code analysis support (e.g., metrics, or language detection).
- Implement a **system prompt** defining agent behavior and enforcing tool-assisted reasoning.
- Agent conclusions MUST be evidence-based, relying on information retrieved via tools.
- Log incoming requests, agent execution steps, tool invocations, errors.
- Generate a human-readable review summary.
- Produce a machine-readable JSON output containing structured findings. Each finding MAY include:
  - file reference,
  - lines,
  - rule,
  - severity (e.g., blocker/high/medium/low/info),
  - explanation,
  - actionable recommendation.
- Create a Merge Request in your personal EPAM GitLab repository and ensure it is accessible to facilitators for review.
  - Merge Request Requirements / Template:
    - 🗒️ Key Takeaways (REQUIRED) - _Please summarize your key takeaways from this implementation, including any experiments performed and insights gained during the process._
    - 📸 Screenshots (REQUIRED) - _Please attach relevant screenshots that demonstrate the results of the task execution and experiments performed._
    - 🧪 Experiments & Edge Cases (REQUIRED) - _Pick one or two experiments per module, run them, and reflect the findings._
    - 🕵️ Quality check (REQUIRED)
      - [ ] I have verified that the functionality works properly
      - [ ] I have performed self-review of my code

## Subtask - Checking whether the model choice is justified for code review:

* Pick a real, non-trivial source file with a genuine mix of issues (take it from any source); run the same review prompt with each model (gpt-4o, gpt-4.1-nano-2025-04-14, gpt-5-mini-2025-08-07), twice in a row per model on the identical file.
* Ask for a structured review across these categories:
  * Naming — naming-convention violations.
  * Code structure — long methods, single-responsibility violations.
  * Best practices — exception handling, resource management.
  * Code quality — documentation, readability.
  * Common anti-patterns — magic numbers, naive string handling, deep nesting.
* Analyze the result:
  * Verify every cited line and claim against the actual file — don't accept a claim just because it sounds plausible.
  * Watch for generic, templated claims that sound true for almost any class but may not be true here (e.g. getters/setters, encapsulation, documentation) — verify these specifically.
  * Compare the two runs per model — any reversed verdict between them is a reliability flag on its own, regardless of which run was more accurate.
  * Note any real issue the model missed, to build a list of blind spots over time.
  * Score each run: pass = net-accurate findings; fail = a new false claim not present in the model's other run.

## Experiments & Edge Cases

> The suggested experiments are for reference purpose only, feel free to suggest any alternative experiment you came up with

| # | Experiment | How to trigger | What to observe |
|---|------------|----------------|-----------------|
| 1 | **Tool overload** | Register several extra/redundant or dummy tools alongside the real ones | Wrong tool selection, wasted reasoning steps, slower runs |
| 2 | **Ambiguous tool descriptions** | Blank out or make vague a tool's `@Tool` description (e.g., `retrieveCodeConvention`) | Mis-selection and confusion — tool descriptions act as a hidden prompt |
| 3 | **Missing convention** | Review a file in a language with **no** loaded convention (e.g., Go, Kotlin) | Honest *"no convention found"* vs hallucinated rules presented as fact |
| 4 | **Evidence enforcement** | Make `readFile` return empty / point it at a non-existent file | Does the agent still fabricate findings without real evidence? |
| 5 | **Path traversal** | Submit `../../etc/passwd` or an absolute path outside the repo root | Input validation and the repository-root security boundary |
| 6 | **Large file / context overload** | Feed a very large source file | Truncation, degraded analysis quality, token-limit errors |
| 7 | **Loop non-termination** | Craft a request that encourages endless tool calling | Need for (and behavior of) a max-iteration guard in the ReAct loop |
| 8 | **Empty / non-code input** | Submit an empty file or a README/Markdown file | Graceful handling vs nonsense or hallucinated findings |

**Failure modes to watch for:** selecting irrelevant tools, fabricated findings when tools return nothing, infinite or excessive tool loops, accepting paths outside the repo root, and silent quality loss on oversized inputs.

## Features

### ReAct Agent Features

- **Iterative Reasoning**: The agent continuously reasons about code quality by analyzing structure, patterns, and
  potential violations
- **Dynamic Action Taking**: Interacts with the convention store to retrieve relevant coding conventions based on
  identified patterns
- **Context-Aware Analysis**: Uses retrieved coding standards to refine its understanding and provide targeted feedback
- **Self-Correcting Behavior**: Iteratively improves its analysis by combining multiple data sources and reasoning steps

### Technical Features

- **Convention-Based Analysis**: Uses pre-loaded Java and Python coding conventions stored in the convention store
- **RAG-Powered Reviews**: Retrieves relevant coding standards and applies them to analyze provided code files
- **Detailed Feedback**: Provides specific violation details with suggestions for improvement
- **File Path Input**: Accepts file paths to analyze Java source code files

### How the ReAct Pattern Works

The Code Review Agent implements the ReAct pattern through the following workflow:

1. **Initial Reasoning**: Analyzes the provided Java file path and understands the code structure
2. **Action - Information Retrieval**: Queries the convention store to retrieve relevant coding conventions
3. **Reasoning - Pattern Matching**: Compares the code against retrieved standards to identify violations
4. **Action - Detailed Analysis**: Performs deeper analysis on identified issues
5. **Final Reasoning**: Synthesizes findings into a comprehensive review with specific recommendations

### Coding Conventions

The service uses coding convention documents:

- `java-convention.md`: Java-specific coding standards and best practices
- `python-convention.md`: Python-specific coding standards (for future extension)

## Setup

### Prerequisites

- Java 21+
- Azure OpenAI credentials for chat
- EPAM VPN – for access to DIAL API

### Configuration

Set the following environment variables before starting:

```bash
export AZURE_OPEN_AI_KEY=dial-key
export AZURE_OPEN_AI_ENDPOINT=https://ai-proxy.lab.epam.com
export AZURE_OPEN_AI_DEPLOYMENT_NAME=gpt-4o

# Use next for subtask investigation of model choice:
#export AZURE_OPEN_AI_DEPLOYMENT_NAME=gpt-4.1-nano-2025-04-14
#export AZURE_OPEN_AI_DEPLOYMENT_NAME=gpt-5-mini-2025-08-07 
```

Start the application:

```bash
./mvnw -pl 03-code-review-agent spring-boot:run
```

## API Usage

### Analyze Java Code

```
POST /code-review
Content-Type: application/json

  {
    "userInput": "03-code-review-agent/src/main/java/com/epam/codereviewagent/service/CodeReviewReactAgent.java"
  }
```

Response example:

```json
{
  "review": "### Code Review for `CodeReviewReactAgent.java`\n\n#### Violations Identified:\n\n1. **Naming Conventions**\n   - Rule: \"Never use prefix 'Code' for classes.\"\n     - The class name `CodeReviewReactAgent` violates this rule since it uses the prefix \"Code\".\n     - Suggest renaming the class to `ReviewReactAgent` or similar.\n\n2. **Class Organization**\n   - Rule: \"Import statements should be organized and no wildcards.\"\n     - The import statements do not contain wildcards, so this is compliant.\n     - However, ensure imports are grouped logically (e.g., third-party libraries vs. application-specific ones).\n\n3. **Comments and Documentation**\n   - Rule: \"Use JavaDoc for all public classes, interfaces, and methods.\"\n     - The `CodeReviewReactAgent` class and its `interact` method lack JavaDoc comments. Add JavaDoc that includes `@param`, `@return`, and any relevant `@throws` tags.\n\n### Summary:\nViolations were found in naming conventions, class organization, comments/documentation, and method design. Address these points to ensure compliance with Java coding conventions."
}
```

## Reference Materials:

### Must-read
- **Basics:** [**AI Agents, Clearly Explained**](https://www.youtube.com/watch?v=FwOTs4UxQS4)
- [**Key Features of AI Agents/ How AI Agents Work**](https://www.geeksforgeeks.org/artificial-intelligence/agents-artificial-intelligence/)

### Recommended
- [**Guide from the Spring team on creating AI agents.**](https://docs.spring.io/spring-ai/reference/api/effective-agents.html)
- [**Agentic Patterns**](https://medium.com/nxtplus/building-intelligent-ai-agents-with-spring-ai-agentic-patterns-explained-part-0-a9d4ac387016)
- [**Building Java AI Agents with Spring AI**](https://www.youtube.com/watch?v=NplxRLwKp34)
- [**Building Java AI Agents with LangChain4j**](https://dev.to/haraf/part-5-ai-agents-with-langchain4j-tool-integration-2e99)

### Optional
- [**Building Effective Agents with Spring AI**](https://www.baeldung.com/spring-ai-building-effective-agents)
