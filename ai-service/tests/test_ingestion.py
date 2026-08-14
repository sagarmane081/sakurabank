from unittest.mock import Mock

from app.embeddings import FakeEmbeddingProvider
from app.ingestion import build_embedding_text, ingest_faqs
from app.models import FAQDocument


def test_build_embedding_text():
    result = build_embedding_text(
        "振込の上限はいくらですか？",
        "SakuraBankの振込上限は100万円です。",
    )

    assert result == (
        "振込の上限はいくらですか？\n"
        "SakuraBankの振込上限は100万円です。"
    )


def test_ingest_faqs_inserts_documents():
    session = Mock()
    session.scalar.return_value = None

    provider = FakeEmbeddingProvider(dimensions=1536)

    processed = ingest_faqs(session, provider)

    assert processed == 25
    assert session.add.call_count == 25
    session.commit.assert_called_once()

    first_document = session.add.call_args_list[0].args[0]

    assert isinstance(first_document, FAQDocument)
    assert first_document.embedding
    assert len(first_document.embedding) == 1536


def test_ingest_faqs_updates_existing_documents():
    session = Mock()

    existing = FAQDocument(
        faq_id="FAQ-001",
        question="古い質問",
        answer="古い回答",
        embedding=[0.0] * 1536,
    )

    session.scalar.return_value = existing

    provider = FakeEmbeddingProvider(dimensions=1536)

    processed = ingest_faqs(session, provider)

    assert processed == 25
    assert session.add.call_count == 0
    session.commit.assert_called_once()

    assert existing.question != "古い質問"
    assert existing.answer != "古い回答"
    assert len(existing.embedding) == 1536