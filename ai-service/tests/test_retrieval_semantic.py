from app.database import SessionLocal
from app.embeddings import SentenceTransformerEmbeddingProvider
from app.retrieval import retrieve_faqs


def test_japanese_semantic_retrieval_finds_transfer_limit_faq() -> None:
    provider = SentenceTransformerEmbeddingProvider()

    with SessionLocal() as session:
        results = retrieve_faqs(
            session=session,
            query="100万円以上の振込をするとどうなりますか？",
            embedding_provider=provider,
            limit=3,
        )

    assert len(results) == 3
    assert results[0].faq_id == "faq-017"