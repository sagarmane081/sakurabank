# SakuraBank — Definition of Done (Enterprise-Grade)

**Deadline for Tier 1: July 31, 2026.** Tier 1 = the app is live, secure, tested, documented, and demo-able. Do not start any Tier 2 item until every Tier 1 box is checked. Tier 2 runs in August in parallel with job applications (max ~1–2 hrs/day).

**Golden rule:** every checkbox has a verification step. If you can't demonstrate it in 30 seconds during an interview, it's not done.

---

## TIER 1 — Interview-Ready Enterprise Baseline (must finish by July 31)

### 1. Core Banking (Spring Boot)

- [ ] Account lifecycle complete: open → active → frozen → closed, enforced as a state machine (invalid transitions rejected with proper error codes)
- [ ] **Double-entry ledger integrity**: every transaction writes balanced debit/credit entries; a scheduled or on-demand reconciliation job proves `SUM(debits) == SUM(credits)` across the ledger
- [ ] Transfers are **atomic and concurrency-safe**: optimistic or pessimistic locking on account balance; write a test with 50 parallel transfers against one account and prove no lost updates and no negative balance
- [ ] **Idempotency keys** on all money-movement endpoints (retry the same transfer request twice → exactly one ledger entry)
- [ ] Transaction history with pagination, filtering by date range and type
- [ ] Overdraft/insufficient-funds rejected at the service layer with a domain error, not a DB constraint crash
- [ ] Currency amounts stored as `BigDecimal`/integer minor units — **zero floating-point money anywhere** (grep the codebase for `double`/`float` near money to verify)

*Verify: run the concurrency test + reconciliation job live during a demo.*

### 2. KYC / AML (Compliance Layer)

- [ ] KYC status state machine: `UNVERIFIED → PENDING → VERIFIED / REJECTED`; unverified users blocked from transfers above a threshold
- [ ] AML rule engine with at least 3 real rules:
  - [ ] Threshold rule (single transaction > ¥1,000,000 → flag)
  - [ ] Velocity rule (N transactions within M minutes → flag)
  - [ ] Structuring detection (multiple just-under-threshold transfers in 24h → flag)
- [ ] Flagged transactions create a **suspicious activity record** reviewable via an admin endpoint/screen
- [ ] **Immutable audit log**: who did what, when, from where (user ID, action, timestamp, IP) — append-only, no update/delete API

*Verify: trigger each AML rule live with seeded transactions.*

### 3. AI Services (FastAPI + Claude API)

- [ ] Japanese RAG chatbot answers banking FAQ questions from your document corpus via pgvector similarity search
- [ ] **Grounding guardrail**: questions outside the corpus get a polite "cannot answer" in Japanese — no hallucinated banking advice (test with 5 off-topic questions)
- [ ] Claude API failure handling: timeout + retry with backoff + graceful degradation message; the app never 500s because the AI is down
- [ ] Response caching (Redis) for repeated questions — demonstrate a cache hit in logs
- [ ] Prompt injection check: user input that says "ignore your instructions" does not break persona or leak the system prompt
- [ ] PII never sent to the API unnecessarily: chatbot context excludes account numbers/balances unless explicitly required and masked

*Verify: kill the API key in a staging config and show graceful degradation.*

### 4. Security

- [ ] JWT auth with **refresh token rotation**; access tokens ≤ 15 min expiry
- [ ] RBAC enforced server-side: `CUSTOMER` / `ADMIN` / `COMPLIANCE_OFFICER` roles; test that a customer token on an admin endpoint returns 403
- [ ] Rate limiting on auth endpoints (login brute-force → 429)
- [ ] Input validation on every endpoint (Bean Validation / Pydantic); malformed payloads return 400 with safe messages — never stack traces
- [ ] **Zero secrets in the repo** — verify with `gitleaks` or `trufflehog` scan; secrets via env vars / AWS SSM Parameter Store
- [ ] PII masked in all logs (account numbers as `****1234`, no passwords/tokens ever logged)
- [ ] Passwords hashed with bcrypt/argon2; HTTPS on the deployed URL; CORS locked to the frontend origin
- [ ] Dependency vulnerability scan clean of criticals: `mvn dependency-check` or Snyk/Trivy in CI

*Verify: show the gitleaks scan output + a 403 test in the demo.*

### 5. Testing

- [ ] Unit tests: service layer (ledger, AML rules, state machines) ≥ 80% line coverage — enforce with JaCoCo/pytest-cov gate in CI
- [ ] Integration tests with **Testcontainers** (real PostgreSQL + Redis, not H2/mocks) for the transfer flow and AML pipeline
- [ ] API tests for every endpoint: happy path + auth failure + validation failure
- [ ] The concurrency test from §1 and the ledger reconciliation test are part of the suite
- [ ] One E2E happy path (register → KYC → deposit → transfer → chatbot query) — scripted or Playwright
- [ ] Entire suite runs green in CI on every push — no skipped/flaky tests committed

*This section is your QA-track showcase. In interviews, open the test report first.*

### 6. CI/CD & Infrastructure (your DevOps-track showcase)

- [ ] GitHub Actions pipeline with distinct stages: lint → build → unit tests → integration tests → security scan (Trivy on the Docker image + gitleaks) → Docker build & push → deploy
- [ ] Pipeline **fails on**: test failure, coverage below gate, critical CVE, secret detected
- [ ] Infrastructure defined in **CloudFormation (YAML)** — the exam-aligned choice you planned; one-command stack up/down
- [ ] Multi-stage Dockerfiles (small final images, non-root user)
- [ ] `docker compose up` brings the full stack locally in one command for reviewers
- [ ] DB migrations via Flyway (Spring) / Alembic (FastAPI) — schema never hand-edited
- [ ] Status badge (build passing) on the README

*Verify: show a live pipeline run + the CloudFormation template in the repo.*

### 7. Observability

- [ ] Structured JSON logging with a **correlation ID** propagated across Spring Boot → FastAPI calls (show one request traced through both services)
- [ ] Health endpoints: `/actuator/health` (Spring) and `/health` (FastAPI), used by the deployment as readiness checks
- [ ] Basic metrics exposed (Actuator/Prometheus format): request count, latency, error rate
- [ ] Errors logged with context, and a clean error-response contract (consistent JSON error body across both services)

### 8. Deployment

- [ ] **Live URL, publicly reachable, HTTPS** — AWS free-tier architecture per your cost plan
- [ ] Seed/demo data loads automatically so a recruiter can log in with documented demo credentials in under 60 seconds
- [ ] Demo accounts: one customer, one admin, one compliance officer (read-only-safe: demo users cannot break the deployment)
- [ ] App survives an instance restart (state in RDS/Redis, not in-memory)

### 9. Documentation (recruiters judge this in 3 minutes)

- [ ] README top section: one-paragraph pitch, live demo link, demo credentials, architecture diagram, tech stack badges — **in English with a Japanese summary section (日本語概要)**
- [ ] Architecture diagram showing all services, data stores, and the AI flow
- [ ] OpenAPI/Swagger UI live for both services
- [ ] 3–5 **ADRs** (Architecture Decision Records): why microservices, why double-entry design, why pgvector, why CloudFormation, AI guardrail strategy — this is what makes it read "enterprise," and gives you interview talking points
- [ ] Screenshots or a 2–3 min demo GIF/video in the README
- [ ] A `SECURITY.md` noting the security measures implemented

### 10. Frontend (React + TypeScript) — deliberately minimal

- [ ] Login, dashboard (balance + recent transactions), transfer form, chatbot UI, admin/compliance review screen
- [ ] Japanese/English language toggle (even partial — it's a differentiator for Japan hiring)
- [ ] No console errors; loading and error states on every API call
- [ ] Clean enough not to embarrass the backend — do **not** spend more than ~20% of total time here

---

## TIER 2 — Enterprise Hardening (August, parallel to applications)

Pick items opportunistically; each maps to a job-track talking point.

### DevOps deepening
- [ ] Kubernetes manifests (or EKS-compatible Helm chart) as an alternative deployment path — keep the cheap deployment live, K8s as documented capability
- [ ] Blue/green or rolling deployment demonstrated in the pipeline
- [ ] Prometheus + Grafana dashboard (screenshot it for the README even if not always running)
- [ ] Terraform variant of the infra in `pos-system-python` (per your original plan — keep Terraform there, CloudFormation here)

### Reliability & scale
- [ ] Load test with k6/Gatling: publish p95 latency + throughput numbers in the README ("handles X TPS on free-tier hardware" is a great line)
- [ ] Circuit breaker (Resilience4j) between core-service and ai-service
- [ ] Outbox pattern or event log for transaction events (talk-track for event-driven architecture questions)

### Compliance & product polish
- [ ] Admin dashboard for AML case management (assign/resolve suspicious activity)
- [ ] Scheduled statement generation (PDF monthly statement — reuse skills, very "bank")
- [ ] Data retention/anonymization job for closed accounts (GDPR/個人情報保護法 talking point)

### Visibility
- [ ] Qiita/Zenn article (Japanese): "Claude APIで日本語RAGチャットボットを銀行アプリに組み込んだ話"
- [ ] LinkedIn post with the demo GIF when Tier 1 ships

---

## Scope discipline rules

1. **No new modules in July.** If it's not on Tier 1, it goes to Tier 2 or the backlog.
2. If a Tier 1 item is running >2× its time estimate, simplify the implementation — don't extend the deadline.
3. "Enterprise-grade" is proven by tests, security scans, audit logs, and documentation — **not** by feature count.
4. Ship at 90%. A live, tested app on July 31 beats a perfect one on September 15 — your interviews start in August either way.
