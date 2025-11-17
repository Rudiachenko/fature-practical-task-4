# Module 1: Prompting & LLMs

## Goal:
Design and implement a RESTful Chatbot API that integrates with Azure OpenAI Large Language Model, supports configurable prompt parameters, maintains conversational memory per session, and returns structured JSON response including tone classification.

## Requirements:

- Expose POST `/chat` endpoint:
  - Request Body:
  ```json
    {
      "conversationId": "conversation_identifier",
      "message": "I'm feeling sad, however I have a big hope to feel better.",
      "temperature": 0.7,
      "topP": 0.9,
      "maxTokens": 500
    }
  ```
  - Response Format: Response must always follow JSON schema, `tone` must be a strict enum - no free-text tone values allowed
  ```json
    {
      "conversationId": "conversation_identifier",
      "response": "It sounds like you're feeling down, but it's great to hear that you have hope for better days ahead.",
      "tone": "POSITIVE | NEGATIVE | NEUTRAL"
    }
  ```
- Configure Prompt Settings per request: `temperature` or `topP` (sampling control), `maxTokens`.
  - Only one sampling parameter may be active per request.
  - If `topP` is unsupported by the selected model/provider, fall back to `temperature`.
- Support setting System Prompt, which must instruct the model to:
  - Provide helpful answers
  - Return tone classification of a user message
  - Respond in structured JSON format
- Implement Conversational Memory, maintained per `conversationId`.
- Integrate with Azure OpenAI API, using Spring AI or LangChain4j framework.
- Log conversationId, user message, LLM response and applied temperature, topP and maxTokens.
- For each request, return a structured JSON response containing:
  - `conversationId` - conversational history identifier
  - `response` - the LLM-generated response text
  - `tone` - a strict enum value `POSITIVE`, `NEGATIVE` or `NEUTRAL` detected by LLM and representing the tone of the user message.
- Create a Merge Request in your personal EPAM GitLab repository and ensure it is accessible to facilitators for review.
  - Merge Request Requirements / Template:
    - 🗒️ Key Takeaways (REQUIRED) - _Please summarize your key takeaways from this implementation, including any experiments performed and insights gained during the process._
    - 📸 Screenshots (REQUIRED) - _Please attach relevant screenshots that demonstrate the results of the task execution and experiments performed._
    - 🕵️ Quality check (REQUIRED)
      - [ ] I have verified that the functionality works properly
      - [ ] I have performed self-review of my code

## Architecture

- **Spring Boot 4.0.2** with Java 21+
- **Spring AI** for Azure OpenAI integration
- **Chat Memory** using `MessageWindowChatMemory`
- **Validation** using Jakarta Bean Validation
- **Lombok** for reduced boilerplate code

## Features

- **REST API** for processing prompts
- **Azure OpenAI GPT integration** using Spring AI
- **Chat Memory** - Maintains conversation context across multiple interactions
- **Configurable temperature** settings per request
- **Conversation ID** tracking for multi-turn conversations
- **Error handling and validation**

### Chat Memory Features

- **Conversation Context**: Maintains memory across multiple interactions
- **Message Window**: Keeps last 20 messages per conversation
- **In-Memory Storage**: Fast access for active conversations
- **Automatic Cleanup**: Older messages removed when window limit exceeded

### Error Handling

The application includes comprehensive error handling:

- Validation errors for invalid input
- Azure OpenAI API errors
- Network connectivity issues
- Chat memory management errors

## Setup

### Prerequisites

- Java 21+
- Maven 3.9+ (or use the provided wrapper `./mvnw`)
- Azure OpenAI API key, endpoint and deployment name
- EPAM VPN – for access to DIAL API

### Configuration

Set environment variables (macOS/Linux syntax shown):

```bash
export AZURE_OPEN_AI_KEY=dial-key
export AZURE_OPEN_AI_ENDPOINT=https://ai-proxy.lab.epam.com
export AZURE_OPEN_AI_DEPLOYMENT_NAME=gpt-4o
```

`application.yml` uses these values automatically.

### Running the Application

```bash
./mvnw -pl 01-prompting-llm spring-boot:run
```

The service listens on port 8080.

### Testing

Run tests with:

```cmd
.\mvnw.cmd test
```

### Building for Production

Create a production JAR:

```cmd
.\mvnw.cmd clean package
```

The JAR file will be created in the `target/` directory.

## API Usage

### Answer a user's message

**POST** `/chat`

Request example:

```http
POST /chat
Content-Type: application/json

{
  "input": "Hello, my name is John",
  "conversationId": "user-123-session",
  "temperature": 0.7
}
```

Response example:

```json
{
  "response": "Hello John! Nice to meet you. How can I help you today?",
  "tone": "POSITIVE"
}
```

### Request Parameters

- `input` (required): The user's prompt/question
- `conversationId` (optional): Unique identifier for conversation context
- `temperature` (optional): Controls randomness (0.0 = deterministic, 1.0 = very creative)

### Chat Memory Example

```json
// First message
POST /chat
{
  "input": "Hello, my name is John",
  "conversationId": "user-123"
}

// Second message in same conversation
POST /chat
{
  "input": "What is my name?",
  "conversationId": "user-123"
}
// Response: "Your name is John"
```

## Reference Materials:
**Prompt Engineering**
- **Basics:** [**Prompt tips**](https://www.ibm.com/docs/en/watsonx/saas?topic=prompts-prompt-tips)
- [**Prompt Engineering Guide**](https://www.promptingguide.ai/)

**LLM parameters**
- **Basics:** [**Model parameters for prompting**](https://www.ibm.com/docs/en/watsonx/saas?topic=prompts-model-parameters-prompting)
- [**LLM parameters**](https://www.ibm.com/think/topics/llm-parameters#692473877)
- [**Complete Guide to Prompt Engineering with Temperature and Top-p**](https://promptengineering.org/prompt-engineering-with-temperature-and-top-p/)

**Chat memory**
- **LangChain4j:** [**Chat memory**](https://docs.langchain4j.dev/tutorials/chat-memory/)
- **Spring AI:** [**Chat memory**](https://docs.spring.io/spring-ai/reference/api/chat-memory.html)

**Structured outputs**
- **LangChain4j:** [**Structured outputs**](https://docs.langchain4j.dev/tutorials/structured-outputs/)
- **Spring AI:** [**Structured outputs**](https://docs.spring.io/spring-ai/reference/api/structured-output-converter.html)

**DIAL Core API**
- [**DIAL Core API**](https://dialx.ai/dial_api)