# Module 4: Model Context Protocol (MCP)

## Goal:
Extend the Code Review Agent developed in Module 3 to perform **automated GitHub Pull Request reviews** using **Model Context Protocol (MCP)** tools.
The agent MUST interact with a **remote MCP server** to retrieve Pull Request data, analyze changed code, and publish **line-specific review comments directly in GitHub**.

## Requirements:
- Implement an **MCP-enabled LLM Code Review Agent** capable of reviewing GitHub Pull Requests using tool-based interactions.
- Provide an interface (REST API or CLI) to submit a review request containing a GitHub Pull Request URL or Repository identifier and PR number
- Validate incoming request and ensure required Pull Request information can be extracted before execution.
- Integrate with a remote MCP server and dynamically discover available tools.
- Use MCP tools as the primary mechanism for interacting with GitHub (retrieval and commenting).
- The agent MUST execute a tool-assisted workflow, including:
  - retrieving Pull Request metadata,
  - obtaining changed files and diffs,
  - retrieving code evidence,
  - analyzing changes,
  - publishing review results.
- Perform code analysis only on files modified in the Pull Request.
- The agent MUST use local tools to identify programming language and retrieve coding conventions.
- Implement a **system prompt** that defines agent behavior, enforces MCP-based tool usage, and guides the agent to perform Pull Request analysis through structured, tool-assisted reasoning rather than unsupported assumptions.
- Ensure all findings are evidence-based, referencing retrieved code or diff data.
- Post **inline Pull Request review comments** anchored to specific files and line numbers using MCP tools.
- Submit an **overall Pull Request review summary** after analysis completion.
- Log incoming requests, MCP connectivity, agent reasoning steps, tool invocations, and errors.
- Create a Merge Request in your personal EPAM GitLab repository and ensure it is accessible to facilitators for review.
  - Merge Request Requirements / Template:
    - 🗒️ Key Takeaways (REQUIRED) - _Please summarize your key takeaways from this implementation, including any experiments performed and insights gained during the process._
    - 📸 Screenshots (REQUIRED) - _Please attach relevant screenshots that demonstrate the results of the task execution and experiments performed._
    - 🕵️ Quality check (REQUIRED)
      - [ ] I have verified that the functionality works properly
      - [ ] I have performed self-review of my code

## Architecture

A Spring Boot service that performs AI-assisted GitHub Pull Request reviews using:

- **Spring AI** for AI orchestration
- **GitHub MCP (Model Context Protocol)** for full GitHub integration via GitHub's **remote MCP endpoint** (`https://api.github.com/mcp`) — no local MCP server required
- **Direct convention loading** from markdown files
- **ReAct Agent** pattern for autonomous tool usage
- **Automated comment posting** - Acts like a real code reviewer!

### Components

- **CodeReviewReactAgent**: ReAct agent orchestrating the PR review process
- **GitHub MCP Integration**: remote HTTP-based GitHub tools via Model Context Protocol
    - Fetch PR information
    - Read file diffs
    - Get changed files
    - Post review comments
    - And many more GitHub operations
- **CodeReviewTools**: Custom tools for language detection and convention retrieval
- **ConventionService**: Loads and manages coding conventions from markdown files

## Features

- 🔍 Automated PR code review against language-specific coding conventions
- 💬 **Posts LINE-SPECIFIC review comments directly on GitHub PRs** - Appears inline in "Files changed" tab
- 🐙 Full GitHub MCP integration via remote endpoint (fetch PRs, read files, post comments, create reviews)
- 📚 Direct convention loading from markdown files (no vector store needed)
- 🤖 ReAct agent with tool calling for autonomous workflows
- 🎯 Reviews all changed files in PRs automatically
- ✅ Line-by-line feedback with exact line numbers and convention references

### Coding Conventions

Conventions are loaded directly on startup from:

- `src/main/resources/documents/java-convention.md`
- `src/main/resources/documents/python-convention.md`

To add more conventions:

1. Create a markdown file with naming pattern: `{language}-convention.md`
2. Place it in `src/main/resources/documents/`
3. Add the resource path to `application.yml`:
   ```yaml
   app:
     conventions:
       resources:
         - classpath:documents/java-convention.md
         - classpath:documents/python-convention.md
   ```
4. Restart the application - conventions are loaded on startup

## Setup

### Prerequisites

- Java 21+
- Azure OpenAI credentials
- GitHub Personal Access Token with repo access
- EPAM VPN – for access to DIAL API

### Configuration

Set the following environment variables before starting:

```bash
# Azure OpenAI Configuration
export AZURE_OPEN_AI_KEY=dial-key
export AZURE_OPEN_AI_ENDPOINT=https://ai-proxy.lab.epam.com

# GitHub Token — used as Bearer auth for the remote GitHub MCP endpoint
export GITHUB_TOKEN=your-github-personal-access-token
```

### GitHub Token

You'll need a GitHub Personal Access Token for the GitHub MCP server with **WRITE** permissions. This token must have:

- `repo` - **Full control of private repositories** (required for posting comments)
  - Includes: repo:status, repo_deployment, public_repo, repo:invite, security_events
- `read:org` - Read organization membership (for organization repositories)

**Note**: The agent posts comments directly on PRs, so repo write access is required!

Start the application:

```bash
./mvnw -pl 04-mcp-code-review-agent spring-boot:run
```



### MCP Configuration

The agent connects directly to GitHub's remote MCP endpoint over HTTP

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: true
        name: github-mcp-client
        version: 1.0.0
        request-timeout: 60s
        type: SYNC  # Synchronous client for blocking operations
        streamable-http:
          connections:
            github:
              url: https://api.github.com
              endpoint: /mcp
              headers:
                Authorization: Bearer ${GITHUB_TOKEN}
```

**Configuration Options:**

- `enabled`: Enable/disable MCP client
- `name`: Client identifier
- `version`: Client version
- `request-timeout`: Timeout for MCP requests (60s for PR reviews)
- `type`: `SYNC` for blocking, `ASYNC` for reactive applications
- `streamable-http.connections`: Streamable HTTP connections to remote MCP servers
  - `url`: Base URL of the remote MCP server
  - `endpoint`: MCP endpoint path suffix (default: `/mcp`)
  - `headers`: HTTP headers sent with every request (e.g., `Authorization: Bearer <token>`)

**Transport Types:**

**1. Streamable HTTP Transport** (HTTP-based — configured for PR review):

```yaml
streamable-http:
  connections:
    github:
      url: https://api.github.com
      endpoint: /mcp
      headers:
        Authorization: Bearer ${GITHUB_TOKEN}
```

**2. STDIO Transport** (Process-based — for local MCP servers):

```yaml
stdio:
  connections:
    local-server:
      command: npx
      args:
        - "-y"
        - "@modelcontextprotocol/server-filesystem"
      env:
        ALLOWED_PATHS: /path/to/directory
```

**Adding Multiple MCP Servers (Optional):**

You can mix remote streamable-http connections with local stdio servers:

```yaml
spring:
  ai:
    mcp:
      client:
        streamable-http:
          connections:
            github:
              url: https://api.github.com
              endpoint: /mcp
              headers:
                Authorization: Bearer ${GITHUB_TOKEN}
        stdio:
          connections:
            filesystem:  # Optional: For local file access
              command: npx
              args:
                - "-y"
                - "@modelcontextprotocol/server-filesystem"
              env:
                ALLOWED_PATHS: /path/to/directory
```

### Tool Flow

```
User Request → ReAct Agent → Tool Selection:
  ├── GitHub MCP Tools (PR operations via remote MCP)
  ├── retrieveCodeLanguage (language detection)
  └── retrieveCodeConvention (convention retrieval)
```

## API Usage

### Review Code

```
POST /code-review
Content-Type: application/json

{
  "userInput": "Review PR #123 in owner/repo"
}
```

The agent will:

1. Use GitHub MCP to fetch PR details
2. Analyze changed files
3. Identify programming languages
4. Retrieve relevant coding conventions from loaded files
5. Review code against conventions
6. Return structured feedback

Response example:

```json
{
  "review": "### PR Review Summary\n\n**Repository**: owner/repo\n**PR**: #123\n\n#### Violations Found:\n\n**File**: src/main/java/Example.java\n- Line 15: Missing Javadoc comment (Java Convention Section 2.1)\n- Line 23: Method name should be camelCase (Java Convention Section 3.2)\n\n**File**: app.py\n- Line 42: Line exceeds 79 characters (PEP 8 Convention)\n\n#### Recommendation:\nPlease update the code to comply with the conventions."
}
```

## GitHub MCP Tools Used by Agent

Based on the [GitHub MCP Server](https://github.com/github/github-mcp-server), GitHub's remote MCP endpoint
(`https://api.github.com/mcp`) automatically exposes all available toolsets

### Available Toolsets (provided automatically by the remote server)

**Pull Requests Toolset** (Primary - for PR operations):

- `get_pull_request`: Get PR details, status, commit SHA
- `list_pull_request_files`: List all changed files in PR
- `add_pull_request_review_comment`: Post comment on specific line
- `create_pull_request_review`: Submit complete review (APPROVE/REQUEST_CHANGES/COMMENT)
- `get_pull_request_diff`: Get raw diff of changes
- `list_pull_requests`: List PRs in repository

**Files Toolset** (for reading code):

- `get_file_contents`: Read file contents from repository
- `search_files`: Search for files in repository

**Search Toolset** (for code search if needed):

- `search_code`: Search code across repository
- `search_issues`: Search issues (may be useful for checking existing issues)

### Custom Convention Tools

- `retrieveCodeLanguage`: Identify programming language
- `retrieveCodeConvention`: Get coding standards (Java, Python)

### Why These Tools?

✅ **pull_requests** - Core functionality for PR review, posting comments, and submitting reviews  
✅ **files** - Essential for reading file contents to review code  
✅ **search** - Helpful for searching code patterns and related issues

The remote GitHub MCP server exposes all toolsets automatically. The ReAct agent selects only the tools it needs for the task, keeping execution efficient.

## Example Usage

### Simple: Just Paste the PR Link!

```
POST http://localhost:8080/code-review
Content-Type: application/json

{
  "userInput": "Review this PR: https://github.com/spring-projects/spring-boot/pull/12345"
}
```

Or even simpler, just the URL:

```
POST http://localhost:8080/code-review
Content-Type: application/json

{
  "userInput": "https://github.com/myorg/myrepo/pull/42"
}
```

**What the agent will do automatically:**

1. Parse the URL to extract owner, repo, and PR number
2. Get PR details using `get_pull_request` (including commit SHA)
3. Get list of changed files using `list_pull_request_files`
4. For each changed file:
   - Fetch content using `get_file_contents`
   - Identify programming language from file extension
   - Retrieve coding conventions for that language
   - Review code line-by-line against conventions
   - **Post LINE-SPECIFIC comments** on exact line numbers using `create_pull_request_review_comment`
5. Notify you that inline comments have been posted

**The agent posts comments on SPECIFIC LINES in the "Files changed" tab - exactly where the issues are!**

### Where Comments Appear

✅ **Line-specific comments** appear in the **"Files changed"** tab, directly on the code line  
❌ **NOT** in the "Conversation" tab as general comments

Example: If there's a Javadoc issue on line 23, the comment appears **on line 23** in the diff view.

### Alternative: Review Specific Files

```
POST /code-review
Content-Type: application/json

{
  "userInput": "Review the file src/main/java/Application.java in spring-projects/spring-boot"
}
```

## How It Works

### Agent Workflow

1. **Parse Request**: Extracts owner, repo, PR number from GitHub URL
2. **Get PR Info**: Calls `get_pull_request` to get PR details and commit SHA
3. **List Changed Files**: Calls `list_pull_request_files` to get all modified files
4. **For Each File**:
   - **Fetch Content**: `get_file_contents` from PR branch
   - **Detect Language**: Identifies from file extension (.java, .py)
   - **Get Conventions**: `retrieveCodeConvention` for the language
   - **Review Code**: Analyzes against convention rules
   - **Post Comment**: `add_pull_request_review_comment` for each violation
5. **Submit Review**: `create_pull_request_review` with overall summary
6. **Report Back**: Confirms comments posted to user

### Sample Output

```markdown
## PR Review Complete: spring-projects/spring-boot #12345

✅ **Posted review comments directly on PR**

### Files Reviewed: 3

#### ✅ src/main/java/config/ApplicationConfig.java
No convention violations. Clean code.

#### ❌ src/main/java/service/UserService.java
**3 comments posted:**
- Line 23: Missing Javadoc for public method
- Line 45-78: Method too long (78 lines)
- Line 89: Non-descriptive variable name

#### ⚠️ scripts/deploy.py
**2 comments posted:**
- Line 12: Line length exceeds PEP 8 limit
- Line 34: Missing function docstring

### Total Comments Posted: 5

### Actions Taken:
1. ✅ Reviewed 3 files against coding conventions
2. ✅ Posted 5 inline comments on specific lines
3. ✅ Submitted overall review with REQUEST_CHANGES status

### What Happens Next:
- PR author will receive GitHub notification
- All comments are visible in the PR "Files changed" tab
- Author can respond to each comment and make fixes

**Check the PR on GitHub to see all posted comments!**
```

### What Gets Posted on GitHub

For each violation, the agent posts **inline comments on specific lines** in the "Files changed" tab:

**Example: On Line 23 of src/main/java/UserService.java**

The comment appears directly on line 23 in the diff:

```java
20|   }
21|
22|   // Process user data
23|   public void processUser(UserData data) {  ← 💬 Comment appears here
24|       validateUser(data);
```

**Comment text:**

```
❌ **Missing Javadoc for public method**

**Convention**: Java Coding Standards Section 2.1 - All public methods must have Javadoc comments.

**Issue**: The method `processUser` lacks documentation explaining its purpose, parameters, and return value.

**Fix**: Add Javadoc comment:
\`\`\`java
/**
 * Processes user data and returns validated result.
 * @param userData the raw user data to process
 * @return processed and validated user data
 * @throws ValidationException if data is invalid
 */
\`\`\`
```

**Result:** The PR author sees the comment inline while reviewing the code, making it easy to understand exactly what
needs to be fixed and where.

## Customization

### Adding New Coding Conventions

1. Create a markdown file with your conventions (e.g., `kotlin-convention.md`)
2. Place it in `src/main/resources/documents/`
3. Update `application.yml`:
   ```yaml
   app:
     conventions:
       resources:
         - classpath:documents/java-convention.md
         - classpath:documents/python-convention.md
         - classpath:documents/kotlin-convention.md  # Add new
   ```
4. Restart the application

### Modifying the Agent Prompt

Edit `src/main/resources/prompts/pr-review-system-prompt.md` to customize:

- Review criteria and focus areas
- Output format and structure
- Tool usage priorities and workflow
- Error handling behavior

### Extending GitHub MCP Tools

The agent automatically discovers all available GitHub MCP tools. Common tools include:

- `get_file_contents` - Read files
- `list_commits` - Get commit history
- `create_issue` - Create issues for violations
- `search_code` - Search code in repositories
- `list_pull_requests` - List PRs

## Troubleshooting

### Agent Can't Post Comments

**Problem**: "Access denied" or "403" when trying to post comments

**Solutions**:

1. **Verify token has `repo` scope** (not just `public_repo`)
- Go to GitHub Settings → Developer settings → Personal access tokens
- Token needs full `repo` access to post comments
2. Check repository permissions (must have write access)
3. For organization repos, check if token has org access
4. Verify token hasn't expired

### Agent Can't Find Files

**Problem**: "File not found" errors

**Solutions**:

1. Verify repository name format in URL
2. Check if PR exists and is accessible
3. Ensure repository is public or token has read access
4. Verify PR number is correct

### Comments Not Appearing on Specific Lines

**Problem**: Agent says it posted comments but they appear in "Conversation" tab, not on specific lines

**Solutions**:

1. **Check the "Files changed" tab** - Line-specific comments appear there, not in "Conversation"
2. Look for speech bubble icons (💬) next to specific line numbers in the diff
3. Refresh the GitHub page
4. Check if comments are in "Pending" state (need to be submitted)

**If comments are general instead of line-specific:**

- Agent didn't include `line` parameter in the tool call
- Check logs to see which tool parameters were used
- Restart and try again - the prompt now emphasizes line-specific comments

### Remote MCP Connection Issues

**Problem**: Application fails to connect to `https://api.github.com/mcp` at startup

**Solutions**:

1. **Verify `GITHUB_TOKEN` is set** and the token has `repo` and `read:org` scopes
2. **Check network access** — the host must be able to reach `api.github.com` (proxy settings may be required)
3. **Inspect startup logs** for `SseHttpClientTransportException` or HTTP 401/403 errors
4. **Validate the token** manually: `curl -H "Authorization: Bearer $GITHUB_TOKEN" https://api.github.com/user`

### Agent Not Using Tools

**Problem**: Agent provides generic responses without using tools

**Solutions**:

1. Be explicit in requests: "Use GitHub to review file X in owner/repo"
2. Provide complete information: repository owner, name, and file path
3. Check agent logs for tool execution attempts
4. Verify the remote MCP connection succeeded

## Reference Materials:

### Must-read
- [**MCP Specification**](https://modelcontextprotocol.io/specification/2025-03-26)
- [**MCP Intro / Core Docs**](https://modelcontextprotocol.io/)
- [**Spring AI MCP Overview**](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-overview.html)
- [**LangChain4j MCP API Docs**](https://docs.langchain4j.dev/tutorials/mcp/)

### Optional

#### Recommended
- [**MCP directory**](https://mcp.so/)
- [**MCP GitHub Org**](https://github.com/modelcontextprotocol)
- [**MCP Servers Repo**](https://github.com/modelcontextprotocol/servers)
- [**MCP Example Servers**](https://modelcontextprotocol.io/examples)

#### Advanced
- [**MCP Architecture**](https://dysnix.com/blog/mcp-architecture)
- [**MCP Advanced Server Capabilities**](https://blog.fka.dev/blog/2025-06-11-diving-into-mcp-advanced-server-capabilities/)

#### Examples
- [**Agentic AI Demo - Smart Business Platform**](https://github.com/Ramzus/spring-ai-mcp-server-demo)
- [**Proposal Agent with MCP**](https://github.com/lucasdengcn/langchain4j-ai-example)


