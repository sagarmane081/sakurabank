from pgvector.sqlalchemy import VECTOR

from app.models import FAQDocument


def test_faq_document_table_name():
    assert FAQDocument.__tablename__ == "faq_documents"


def test_faq_document_schema():
    assert FAQDocument.__table__.schema == "ai"


def test_faq_document_columns():
    columns = FAQDocument.__table__.columns

    assert columns["id"].primary_key
    assert not columns["id"].nullable

    assert not columns["faq_id"].nullable
    assert columns["faq_id"].unique

    assert not columns["question"].nullable
    assert not columns["answer"].nullable
    assert not columns["embedding"].nullable


def test_faq_document_embedding_dimension():
    embedding_type = FAQDocument.__table__.columns["embedding"].type

    assert isinstance(embedding_type, VECTOR)
    assert embedding_type.dim == 384