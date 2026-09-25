# AI Engineer Program - Spring AI

This repository is a Maven reactor (parent POM) that aggregates several modules:

- `01-prompting-llm`
- `02-rag`
- `03-code-review-agent`
- `04-mcp-code-review-agent`

Each module is a standard Java project (JAR packaging). The parent POM coordinates builds across modules.

The project includes a Docker Compose file to bring up required infrastructure locally.

## High-level description of the practical tasks (detailed requirements are available in each module's README.md).

### Module 1: Prompting & LLMs (`01-prompting-llm`)

A Spring Boot application that integrates with Azure OpenAI using Spring AI to process user prompts and return AI-generated responses.

### Module 2: Retrieval-Augmented Generation (RAG) (`02-rag`)

A Spring Boot service demonstrating Retrieval-Augmented Generation (RAG) using a Chroma vector database to retrieve relevant document context for user queries.

### Module 3: AI Agents (Agentic Systems) (`03-code-review-agent`)
Spring Boot service that demonstrates an intelligent code review agent using the **ReAct (Reasoning and Acting) pattern**.  
The agent combines reasoning with tool usage by interacting with external tools and data sources to analyze repository code, retrieve contextual information, and produce evidence-based review results.

### Module 4: Model Context Protocol (MCP) (`04-mcp-code-review-agent`)
A Spring Boot service that performs AI-assisted GitHub Pull Request reviews by connecting to a remote MCP server to retrieve PR metadata, analyze changed code, and post evidence-based review comments.

### Experiments & Edge Cases (all modules)

Beyond the basic implementation, every module's README includes `Experiments & Edge Cases` section. These experiments are lightweight: mostly configuration, input, or prompt changes (not new code) and exist to help you experience how AI systems behave under non-ideal conditions: overloaded or conflicting prompts, too many or poorly-described tools, varied chunk sizes and retrieval settings, noisy or contradictory documents, malformed structured outputs, and context overload.

Pick two or three experiments per module, run them, and reflect the findings into your Merge Request **Key Takeaways**.

## Setup

### Prerequisites

- Docker & Docker Compose
- JDK 21+
- Maven 3.9+

### Start supporting services

```bash
docker compose up -d
```

This starts a local [Chroma](https://www.trychroma.com/) vector database (
image `ghcr.io/chroma-core/chroma:1.0.0`) on `http://localhost:8000`. Data is persisted in
the `chroma-data` volume so you can stop/start the containers without losing ingested documents.

### Environment variables

Set the following before running the services (sample values for local dev):

```bash
export AZURE_OPEN_AI_KEY=dial-key
export AZURE_OPEN_AI_ENDPOINT=https://ai-proxy.lab.epam.com
export AZURE_OPEN_AI_DEPLOYMENT_NAME=gpt-4o
export AZURE_OPEN_AI_EMBEDDING_DEPLOYMENT_NAME=text-embedding-3-small-1
# GitHub Personal Access Token with PR read+write scope, required by 04-mcp-code-review-agent to
# authenticate against GitHub's remote MCP server
export GITHUB_TOKEN=your-github-personal-access-token
# Optional overrides for Chroma defaults
export CHROMA_BASE_URL=http://localhost:8000
export CHROMA_COLLECTION=doc-qa-collection
```

### Run the applications

In separate terminals:

```bash
./mvnw -pl 01-prompting-llm spring-boot:run
./mvnw -pl 02-rag spring-boot:run
./mvnw -pl 03-code-review-agent spring-boot:run
./mvnw -pl 04-mcp-code-review-agent spring-boot:run
```

All services read their configuration from the `application.yml` files and pick up the environment
variables above. Module-specific notes:

- `01-prompting-llm`: exposes `/chat` endpoint for prompt processing
- `02-rag`: exposes `/doc-qa/chat`, `/doc-qa/documents` endpoints (see `02-rag/RUNBOOK.md` for payloads; `02-rag/README.md` is the original assignment)
- `03-code-review-agent`: exposes `/code-review` endpoint for Java code review
- `04-mcp-code-review-agent`: exposes `POST /code-review` for MCP-based GitHub PR review


### Shutdown

```bash
docker compose down
```

Add `--volumes` if you want to wipe the persisted vector store data.


