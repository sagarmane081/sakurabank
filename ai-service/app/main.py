"""SakuraBank ai-service — Japanese banking RAG chatbot (FastAPI).

Phase 0: skeleton with health endpoint only.
Phase 3 adds: Claude API client, pgvector retrieval, Redis caching,
guardrails, and the LLM evaluation harness.
"""

from fastapi import FastAPI

app = FastAPI(
    title="SakuraBank AI Service",
    description="Japanese banking FAQ chatbot (RAG over pgvector, Claude API)",
    version="0.1.0",
)


@app.get("/health")
def health() -> dict[str, str]:
    """Readiness/liveness check used by docker-compose and deployment."""
    return {"status": "UP", "service": "ai-service"}
