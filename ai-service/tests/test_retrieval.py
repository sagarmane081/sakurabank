from unittest.mock import Mock

import pytest

from app.embeddings import FakeEmbeddingProvider
from app.retrieval import retrieve_faqs


def test_blank_query_is_rejected() -> None:
    session = Mock()
    provider = FakeEmbeddingProvider(dimensions=384)

    with pytest.raises(
        ValueError,
        match="query must not be blank",
    ):
        retrieve_faqs(
            session=session,
            query="   ",
            embedding_provider=provider,
        )


def test_invalid_limit_is_rejected() -> None:
    session = Mock()
    provider = FakeEmbeddingProvider(dimensions=384)

    with pytest.raises(
        ValueError,
        match="limit must be greater than zero",
    ):
        retrieve_faqs(
            session=session,
            query="口座開設について教えてください",
            embedding_provider=provider,
            limit=0,
        )


def test_retrieve_faqs_uses_query_embedding() -> None:
    session = Mock()
    provider = Mock()
    provider.embed_query.return_value = [0.1, 0.2, 0.3]

    session.scalars.return_value.all.return_value = []

    result = retrieve_faqs(
        session=session,
        query="振込について教えてください",
        embedding_provider=provider,
        limit=3,
    )

    assert result == []

    provider.embed_query.assert_called_once_with(
        "振込について教えてください"
    )

    session.scalars.assert_called_once()