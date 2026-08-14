# Testing Strategy

> **SakuraBank test suite: 217 Java tests + 33 AI-service tests**

## 1. Testing Philosophy

SakuraBank follows a test-first development approach:

> A test must fail if the behavior it describes is removed.

For defect and failure analysis, I reproduce the failure before modifying the implementation. This gives me a reliable baseline, prevents speculative fixes, and allows the final test to serve as regression protection.

The development cycle is:

**Red → Green → Refactor**

The goal is not to maximize coverage for its own sake. The goal is to make important failures visible early while keeping the test suite useful as a regression safety net.

---

## 2. Test Pyramid

The project uses multiple testing levels rather than relying on a single type of test.

### Current Test Summary

| Layer / Area | Tests | Purpose |
|---|---:|---|
| Core Java service | **217** | Domain, integration, service, API, security, and concurrency behavior |
| Concurrency | **2 key scenarios** | Lost-update and deadlock reproduction/regression |
| AI service | **33** | Embeddings, ingestion, models, database, retrieval, and API health |
| AI coverage | **82.32%** | 80% minimum enforced by pytest-cov |

The 217 Java tests span the domain, repository/integration, service, API, security, and concurrency layers. The exact package-level split is intentionally not estimated here without a direct test-package count.

### Domain Tests

Domain tests focus on business rules and domain objects without requiring the Spring application context.

Examples include:

- Account validation
- Constructor guards
- Invalid amounts
- Ledger entry rules
- State transitions
- Value-object behavior

These tests are intentionally small and fast.

The purpose is to verify business invariants independently from infrastructure.

---

### Repository / Integration Tests

Database-related behavior is tested against **real PostgreSQL** rather than H2.

This is deliberate.

SakuraBank relies on PostgreSQL-specific behavior including:

- Transactions
- Foreign-key constraints
- Database locking
- PostgreSQL transaction semantics
- Persistence behavior
- Vector storage in the AI service

Using H2 for these tests could allow behavior to pass in tests while failing against the actual database.

The AI service also uses PostgreSQL with `pgvector` for vector storage and similarity search.

---

### Service Tests

Two testing styles are used depending on the behavior being tested.

#### Mockito-based Service Tests

Mockito is used when testing service orchestration.

For example, a test can verify that:

- the correct dependency was called,
- a dependency was not called after validation failed,
- nothing was persisted when a business operation failed,
- the expected sequence of operations occurred.

For failure scenarios, assertions such as:

```java
verify(repository, never()).save(...)
```

are useful because they verify not only what happened, but also what **must not** happen.

#### Real-Database Integration Tests

For behavior involving:

- SQL
- transactions
- persistence
- database constraints
- locking
- concurrency

the test uses the real PostgreSQL database.

The principle is:

> **Mock the boundary, not the database.**

If the behavior being tested depends on PostgreSQL, I want PostgreSQL involved in the test.

---

### API and Security Tests

API endpoints are tested through the HTTP layer using MockMvc.

Tests cover:

- Successful requests
- Authentication failures
- Authorization failures
- Validation failures
- Error responses
- Expected response payloads

Security testing also covers negative paths around the authentication system, including:

- JWT authentication
- Refresh-token rotation
- Refresh-token reuse detection
- Account lockout
- Rate limiting

`@WithMockUser` is used where appropriate to exercise authenticated and authorized controller paths without coupling every API test to token-generation mechanics.

The security tests deliberately verify failure behavior as well as successful authentication. At one point, the `core.security` package achieved 100% branch coverage, reflecting the emphasis on negative security paths.

---

## 3. Concurrency Testing

Concurrency is one of the important parts of the SakuraBank test suite.

When a test exposes a concurrency failure, I reproduce the failure reliably before changing the implementation.

The workflow is:

**Failure → Reproduce → Isolate → Identify root cause → Fix → Regression test**

The lost-update and deadlock problems were both reproduced before being fixed.

### Lost Update

The transfer system was tested with **50 concurrent threads**.

The original implementation allowed a lost-update scenario where:

- all 50 operations reported success,
- but **¥450 disappeared** from the expected balance.

The failure was reproduced as a concurrency test before the implementation was changed.

The issue was fixed using **pessimistic locking** so that concurrent balance modifications could not overwrite each other's updates.

The test verifies the actual database behavior under concurrent execution rather than mocking the repository.

---

### Deadlock

A PostgreSQL deadlock was reproduced using concurrent bidirectional transfers.

Two transactions attempted to acquire resources in opposite orders.

PostgreSQL detected the deadlock and terminated one transaction as the victim.

The solution was to enforce a deterministic lock acquisition order using account UUID ordering.

The investigation followed the same process:

1. Reproduce the deadlock.
2. Confirm the database-level failure.
3. Identify the inconsistent lock ordering.
4. Implement deterministic ordering.
5. Run the concurrency test again.
6. Keep the test as regression protection.

Both concurrency issues were **reproduced before being fixed**.

---

## 4. AI Retrieval Testing

The AI service has its own test suite because the retrieval pipeline has different failure modes from the core banking service.

The current AI retrieval pipeline contains:

- 25 Japanese FAQ documents
- Local `multilingual-e5-small` embeddings
- 384-dimensional vectors
- PostgreSQL + pgvector
- Cosine-distance retrieval

### Embedding Provider Abstraction

The embedding layer uses a provider abstraction with:

- `FakeEmbeddingProvider`
- `OpenAIEmbeddingProvider`
- `SentenceTransformerEmbeddingProvider`

This allows the implementation to be tested independently from external API availability.

### Fake Embedding Provider

A deterministic fake provider is used for unit tests.

It allows tests to verify:

- embedding dimensions
- deterministic behavior
- validation
- blank-input rejection
- provider interface behavior

The OpenAI provider is also tested with a mocked client, so an API key is not required to test the provider implementation.

### Real Semantic Retrieval

Unit tests alone cannot prove that semantic retrieval actually works.

Therefore, retrieval was also tested using:

**real `multilingual-e5-small` model → real PostgreSQL → real pgvector**

A paraphrased Japanese query:

```text
一度に100万円を超える金額を送ると、どう処理されますか？
```

correctly returned:

```text
#1 faq-017
1,000,000円を超える送金はどうなりますか？
distance = 0.1377
```

as the top result.

This validates the semantic retrieval path rather than only testing that vectors can be stored and queried.

A separate query exposed a genuine retrieval ambiguity:

```text
100万円以上の振込をするとどうなりますか？
```

The actual ranking was:

```text
#1 faq-019  distance = 0.1600
複数回に分けて大きな金額を送金するとどうなりますか？

#2 faq-009  distance = 0.1600
KYCがVERIFIEDの場合、100,000円を超える送金はできますか？

#3 faq-017  distance = 0.1650
1,000,000円を超える送金はどうなりますか？
```

This is being treated as a retrieval-quality issue to evaluate rather than being hidden behind a passing infrastructure test.

---

## 5. Security Testing and Defect Discovery

### Transfer Ownership Vulnerability

An automated security review identified a missing authorization boundary in the transfer flow:

> An authenticated user could potentially initiate a transfer from an account they did not own.

This was not merely documented as a security finding. The authorization logic was corrected and a regression test was added to verify that a user cannot transfer from another user's account.

This reinforced an important testing lesson:

> **Green tests only prove the behaviors we thought to test.**

Security testing and external review can expose incorrect assumptions that are invisible to an otherwise healthy test suite.

---

## 6. Quality Gates

The project uses automated quality gates in CI.

### Java Service

The core Java service currently has **217 tests**.

JaCoCo enforces a minimum **80% test coverage** for the configured service layer.

If coverage falls below the configured threshold, the build fails.

### AI Service

The Python AI service currently has:

- **33 tests**
- **33 passing**
- **82.32% total coverage**

The configured minimum coverage is **80%**.

### Secret Scanning

`gitleaks` runs as part of CI to detect accidentally committed secrets.

This is particularly important for the AI service because external LLM API keys must never be committed to the repository.

---

## 7. What Is Not Tested Yet

I deliberately distinguish between implemented functionality and functionality that is still pending.

### LLM Generation

The generation stage of the RAG pipeline is not yet implemented.

The current pipeline reaches:

```text
User Query
    ↓
Embedding
    ↓
pgvector Retrieval
    ↓
Relevant FAQ
```

The remaining generation stage is:

```text
Relevant FAQ
    ↓
LLM
    ↓
Grounded Natural-Language Answer
```

External LLM API access is currently unavailable, so generation, grounding behavior, and LLM-specific failure handling have not yet been validated.

### Retrieval Quality

The retrieval pipeline has already exposed an ambiguity case involving large transfer limits and AML-related FAQs.

This will be addressed through retrieval evaluation and regression tests rather than by assuming that every semantically related query will always return one predetermined document.

### Generation Security

Once generation is implemented, the following areas will be tested:

- Prompt injection resistance
- Grounding / unsupported-answer prevention
- LLM API failure handling
- Timeout handling
- Malformed model responses
- Appropriate fallback behavior

---

## 8. Testing Principles

The testing decisions in this project can be summarized as:

1. **Test behavior, not implementation details.**
2. **For failures and defects, reproduce before changing the implementation.**
3. **Use real PostgreSQL when PostgreSQL behavior matters.**
4. **Mock external boundaries, not the database.**
5. **Reproduce concurrency failures before fixing them.**
6. **Use deterministic fakes for unit tests where appropriate.**
7. **Use real models for semantic retrieval validation.**
8. **Keep known limitations visible instead of hiding them.**
9. **Make coverage a quality gate, not the definition of quality.**
