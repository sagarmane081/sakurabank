# ai-service — SakuraBank Japanese RAG chatbot

FastAPI / Python 3.12. Owns the Claude API integration, pgvector retrieval,
Redis caching, guardrails, and the LLM evaluation harness (Phase 3).

```bash
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"
ruff check . && pytest          # lint + tests + 80% coverage gate
uvicorn app.main:app --reload
```
