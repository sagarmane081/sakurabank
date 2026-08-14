from collections.abc import Sequence

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.corpus import load_faq_corpus
from app.embeddings import EmbeddingProvider
from app.models import FAQDocument


def build_embedding_text(question: str, answer: str) -> str:
    return f"{question}\n{answer}"


def ingest_faqs(
    session: Session,
    embedding_provider: EmbeddingProvider,
) -> int:
    entries = load_faq_corpus()
    processed = 0

    for entry in entries:
        embedding_text = build_embedding_text(
            entry.question,
            entry.answer,
        )

        embedding = embedding_provider.embed_document(embedding_text)

        existing = session.scalar(
            select(FAQDocument).where(
                FAQDocument.faq_id == entry.id
            )
        )

        if existing is None:
            document = FAQDocument(
                faq_id=entry.id,
                question=entry.question,
                answer=entry.answer,
                embedding=embedding,
            )
            session.add(document)
        else:
            existing.question = entry.question
            existing.answer = entry.answer
            existing.embedding = embedding

        processed += 1

    session.commit()

    return processed