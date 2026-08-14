import re
from collections.abc import Sequence

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.embeddings import EmbeddingProvider
from app.models import FAQDocument

_AMOUNT_PATTERN = re.compile(
    r"(?:(\d+(?:,\d+)*)\s*万円|(\d+(?:,\d+)*)\s*円)"
)


def _extract_amounts(text: str) -> set[int]:
    amounts: set[int] = set()

    for man_amount, yen_amount in _AMOUNT_PATTERN.findall(text):
        if man_amount:
            amounts.add(int(man_amount.replace(",", "")) * 10_000)
        elif yen_amount:
            amounts.add(int(yen_amount.replace(",", "")))

    return amounts


def _rerank_by_numeric_match(
    query: str,
    documents: Sequence[FAQDocument],
) -> list[FAQDocument]:
    query_amounts = _extract_amounts(query)

    if not query_amounts:
        return list(documents)

    def score(document: FAQDocument) -> int:
        document_amounts = _extract_amounts(document.question)
        return 1 if query_amounts & document_amounts else 0

    return sorted(
        documents,
        key=score,
        reverse=True,
    )


def retrieve_faqs(
    session: Session,
    query: str,
    embedding_provider: EmbeddingProvider,
    limit: int = 3,
) -> Sequence[FAQDocument]:
    if not query or not query.strip():
        raise ValueError("query must not be blank")

    if limit <= 0:
        raise ValueError("limit must be greater than zero")

    query_embedding = embedding_provider.embed_query(query)

    statement = (
        select(FAQDocument)
        .where(FAQDocument.embedding.is_not(None))
        .order_by(
            FAQDocument.embedding.cosine_distance(query_embedding)
        )
        .limit(max(limit * 3, 10))
    )

    documents = session.scalars(statement).all()

    reranked = _rerank_by_numeric_match(
        query,
        documents,
    )

    return reranked[:limit]