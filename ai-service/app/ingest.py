from app.database import SessionLocal
from app.embeddings import SentenceTransformerEmbeddingProvider
from app.ingestion import ingest_faqs


def main() -> None:
    provider = SentenceTransformerEmbeddingProvider()

    with SessionLocal() as session:
        processed = ingest_faqs(
            session=session,
            embedding_provider=provider,
        )

    print(f"Ingested {processed} FAQ entries.")


if __name__ == "__main__":
    main()