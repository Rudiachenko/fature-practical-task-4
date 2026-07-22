# Module 2: Retrieval-Augmented Generation (RAG)

## Goal:
Enhance the chatbot developed in Module 1 by implementing a Retrieval-Augmented Generation (RAG) pipeline that enables the system to answer user questions using information retrieved from indexed documents stored in a vector database.
The chatbot must retrieve relevant document fragments, provide context-aware answers, and include references to source documents in a structured response.

## Requirements:

- Implement an endpoint to ingest documents (*.txt, *.pdf, *.md, etc.) into the vector database. Processing Steps:
  - Extract text content.
  - Split document into chunks.
  - Generate embeddings for each chunk.
  - Store chunks in vector database.
- Extend the existing `/chat` endpoint to support RAG. Processing Pipeline:
  - Receive user request.
  - Generate embedding for user query.
  - Perform vector similarity search and retrieve top N relevant chunks (Please experiment with N number and Minimum similarity threshold).
  - Construct augmented prompt and send to LLM.
  - Return structured response.
- Context Injection Rules:
  - Retrieved content must be clearly separated from user input.
  - The prompt must instruct the LLM to use ONLY provided context when answering or, if context is insufficient - respond "I don't have enough information to answer this question."
- For each request, return a structured JSON response, including references to the source:
  ```json
  {
    "response": "LLM's generated response, containing information from augmented context.",
    "sources": [
      {
        "documentName": "string",
        "chunkId": "string"
      }
    ]
  }
  ```
- (Optional) Support document update/delete:
  - Implement endpoint to remove all indexed chunks belonging to document.
  - Implement endpoint to re-ingests updated document.

- Create a Merge Request in your personal EPAM GitLab repository and ensure it is accessible to facilitators for review.
  - Merge Request Requirements / Template:
    - 🗒️ Key Takeaways (REQUIRED) - _Please summarize your key takeaways from this implementation, including any experiments performed and insights gained during the process._
    - 📸 Screenshots (REQUIRED) - _Please attach relevant screenshots that demonstrate the results of the task execution and experiments performed._
    - 🧪 Experiments & Edge Cases (REQUIRED) - _Pick two or three experiments per module, run them, and reflect the findings._
    - 🕵️ Quality check (REQUIRED)
      - [ ] I have verified that the functionality works properly
      - [ ] I have performed self-review of my code

## Subtask - Checking whether the model choice is justified for RAG-grounded answers:

* Index a reference document once and keep the index/embedding model fixed; run the app in turn with each generator model (gpt-4o, gpt-4.1-nano-2025-04-14, gpt-5-mini-2025-08-07).
* Ask the following questions:
  * Simple question — a direct factual lookup, answerable from a single passage.
    * Example: "What is AgentWrite?"
  * Detail search — a multi-part fact that requires precise retrieval.
    * Example: "What are the two main stages of the AgentWrite framework, and what is the purpose of each stage?"
  * Information synthesis — an explanation that requires combining information spread across the document.
    * Example: "Why do the authors claim that simply increasing the context window is not enough to generate high-quality long-form text?"
  * Analysis/reasoning — an open-ended question that goes beyond the document, asking for the model's own extrapolation.
    * Example: "Based on the paper, if you were implementing AgentWrite in a production AI application, what challenges or limitations would you expect, and how would you address them?"
  * Complex summary — a fixed-length bullet summary of the document's key contributions.
    * Example: "Summarize the paper in five bullet points, where each bullet represents one key contribution of the work. Do not include implementation details or experimental results."
* Analyze the result:
  * Simple / detail-search / synthesis questions — check every concrete number, name, and claim against the actual source text; any unsupported or altered fact is a fail.
  * Analysis/reasoning question — not a faithfulness test; judge depth and structure, but flag speculative claims stated with more certainty than the source supports.
  * Summary question — check whether the bullets are specific and distinct, or vague restatements.
  * Repeat with each model and record pass/fail per question plus a 1–5 score per model.

## Experiments & Edge Cases

> The suggested experiments are for reference purpose only, feel free to suggest any alternative experiment you came up with

| # | Experiment | How to trigger | What to observe |
|---|------------|----------------|-----------------|
| 1 | **Grounding / refusal** | Ask a question with **no** supporting document in the store | Whether it correctly responds *"I don't have enough information to answer this question."* |
| 2 | **Chunk size** | Re-ingest the same document with a small vs large chunk size | Retrieval granularity, answer completeness, number of relevant chunks returned |
| 3 | **Chunk overlap** | Overlapping vs non-overlapping chunks | Continuity of context across chunk boundaries; split-fact answers |
| 4 | **`topK` sweep** | Query with `topK` `1` vs `4` vs `20` | Precision vs recall; context overload; cost and latency |
| 5 | **Similarity threshold** | Query with `similarityThreshold` `0.1` vs `0.5` vs `0.8` | Over-retrieval (noise) vs under-retrieval (no answer found) |
| 6 | **Noisy corpus** | Ingest several irrelevant/off-topic documents alongside the relevant one | Distractor effect on retrieval and answer accuracy |
| 7 | **Query transformation/expansion toggle** | Enable/disable `app.rag.query-transformation.*` and `app.rag.query-expansion.*` | Recall vs added latency/cost; duplicate chunks before/after `ConcatenationDocumentJoiner` |
| 8 | **Source attribution** | Verify the returned `sources` against the actual answer content | Citation accuracy — are cited sources real and relevant, or hallucinated? |

**Failure modes to watch for:** answers built from irrelevant/noisy chunks, failure to refuse when context is missing, facts split across chunk boundaries, hallucinated or mismatched source references, and rising latency/cost as `topK` grows.

### Setup

## Prerequisites

- Java 21+
- Chroma vector database running locally (see repository root `README.md`)
- Azure OpenAI credentials for chat & embeddings
- EPAM VPN – for access to DIAL API

## Configuration

Set the following environment variables before starting:

```bash
export AZURE_OPEN_AI_KEY=dial-key
export AZURE_OPEN_AI_ENDPOINT=https://ai-proxy.lab.epam.com
export AZURE_OPEN_AI_DEPLOYMENT_NAME=gpt-4o
export AZURE_OPEN_AI_EMBEDDING_DEPLOYMENT_NAME=text-embedding-3-small-1
# Optional: override Chroma defaults
export CHROMA_BASE_URL=http://localhost:8000
export CHROMA_COLLECTION=doc-qa-collection

# Use next for subtask investigation of model choice:
#export AZURE_OPEN_AI_DEPLOYMENT_NAME=gpt-4.1-nano-2025-04-14
#export AZURE_OPEN_AI_DEPLOYMENT_NAME=gpt-5-mini-2025-08-07 
```


The defaults match the local `docker compose` stack (Chroma 1.0.0).

Start the application:

```bash
./mvnw -pl 02-rag spring-boot:run
```

## API Usage

### Ingest documents

```
POST /doc-qa/documents
Content-Type: application/json

{
  "resourceLocations": ["classpath:documents/llm_context_document.pdf"],
  "metadata": {"sourceType": "sample"}
}
```

### Ask a question

```
POST /doc-qa/chat
Content-Type: application/json

{
  "input": "What is context llm window?",
  "conversationId": "demo-session"
}
```

Response example:

```json
{
  "answer": "The context window of a large language model (LLM) refers to the amount of text or input the model can process at once, similar to working memory. Research has explored expanding this window to handle longer context tasks, enabling the model to process and understand larger amounts of content efficiently.",
  "sources": [
    "classpath:documents//llm_context_document.pdf"
  ]
}
```

### Delete documents

```
DELETE /doc-qa/documents?ids=<comma-separated-ids>
```

## Postman Collection

Import the following requests in Postman (replace host/port if needed):

1. **Doc QA - Ingest**
  - Method: POST
  - URL: `http://localhost:8080/doc-qa/documents`
  - Body: raw JSON (same as above)

2. **Doc QA - Chat**
  - Method: POST
  - URL: `http://localhost:8080/doc-qa/chat`
  - Body:
    ```json
    {
      "input": "Give me a TL;DR",
      "conversationId": "demo-session"
    }
    ```

3. **Doc QA - Delete**
  - Method: DELETE
  - URL: `http://localhost:8080/doc-qa/documents?ids=<id1,id2>`

## Reference Materials:

### Must-read
- [**Langchain4j RAG concepts**](https://docs.langchain4j.dev/tutorials/rag)
- [**Spring AI RAG concepts**](https://docs.spring.io/spring-ai/reference/concepts.html#concept-rag)

### Recommended
- [**Rerankers and Two-Stage Retrieval**](https://www.pinecone.io/learn/series/rag/rerankers/#Power-of-Rerankers)

### Optional
- [**Advanced RAG Techniques**](https://neo4j.com/blog/genai/advanced-rag-techniques/)
