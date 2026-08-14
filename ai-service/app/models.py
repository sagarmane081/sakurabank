from pgvector.sqlalchemy import Vector
from sqlalchemy import String, Text
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class FAQDocument(Base):
    __tablename__ = "faq_documents"
    __table_args__ = {"schema": "ai"}

    id: Mapped[int] = mapped_column(primary_key=True)
    faq_id: Mapped[str] = mapped_column(String(100), unique=True, nullable=False)
    question: Mapped[str] = mapped_column(Text, nullable=False)
    answer: Mapped[str] = mapped_column(Text, nullable=False)
    embedding: Mapped[list[float]] = mapped_column(Vector(384), nullable=False)