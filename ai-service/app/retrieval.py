from collections.abc import Sequence

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.embeddings import EmbeddingProvider
from app.models import FAQDocument


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
        .limit(limit)
    )

    return session.scalars(statement).all()