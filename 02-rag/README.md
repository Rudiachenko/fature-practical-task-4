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
    - 🕵️ Quality check (REQUIRED)
      - [ ] I have verified that the functionality works properly
      - [ ] I have performed self-review of my code

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
  "conversationId": "demo-session",
  "topK": 4,
  "similarityThreshold": 0.3
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
