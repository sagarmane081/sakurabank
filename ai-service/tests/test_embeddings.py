from unittest.mock import Mock

import pytest

from app.embeddings import (
    EmbeddingProvider,
    FakeEmbeddingProvider,
    OpenAIEmbeddingProvider,
    validate_embedding,
)


def test_fake_embedding_provider_implements_provider_interface() -> None:
    provider = FakeEmbeddingProvider(dimensions=8)

    assert isinstance(provider, EmbeddingProvider)


def test_fake_embedding_has_expected_dimensions() -> None:
    provider = FakeEmbeddingProvider(dimensions=8)

    embedding = provider.embed_document("口座を開設するには何が必要ですか？")

    assert len(embedding) == 8
    assert all(isinstance(value, float) for value in embedding)


def test_same_text_produces_same_embedding() -> None:
    provider = FakeEmbeddingProvider(dimensions=8)

    first = provider.embed_document("KYCについて教えてください")
    second = provider.embed_document("KYCについて教えてください")

    assert first == second


def test_different_text_can_produce_different_embedding() -> None:
    provider = FakeEmbeddingProvider(dimensions=8)

    first = provider.embed_document("口座開設")
    second = provider.embed_document("送金")

    assert first != second


def test_blank_text_is_rejected() -> None:
    provider = FakeEmbeddingProvider(dimensions=8)

    with pytest.raises(
        ValueError,
        match="text must not be blank",
    ):
        provider.embed_document("   ")


def test_invalid_dimensions_are_rejected() -> None:
    with pytest.raises(
        ValueError,
        match="dimensions must be greater than zero",
    ):
        FakeEmbeddingProvider(dimensions=0)


def test_validate_embedding_accepts_expected_dimensions() -> None:
    embedding = validate_embedding(
        [0.1, 0.2, 0.3, 0.4],
        expected_dimensions=4,
    )

    assert embedding == [0.1, 0.2, 0.3, 0.4]


def test_validate_embedding_rejects_wrong_dimensions() -> None:
    with pytest.raises(
        ValueError,
        match="Expected embedding with 4 dimensions, got 3",
    ):
        validate_embedding(
            [0.1, 0.2, 0.3],
            expected_dimensions=4,
        )


def test_validate_embedding_normalizes_numeric_values() -> None:
    embedding = validate_embedding(
        [1, 2, 3],
        expected_dimensions=3,
    )

    assert embedding == [1.0, 2.0, 3.0]


def test_openai_embedding_provider_implements_provider_interface() -> None:
    client = Mock()

    provider = OpenAIEmbeddingProvider(
        client=client,
        model="text-embedding-3-small",
        dimensions=4,
    )

    assert isinstance(provider, EmbeddingProvider)


def test_openai_embedding_provider_calls_embeddings_api() -> None:
    client = Mock()

    client.embeddings.create.return_value = Mock(
        data=[
            Mock(
                embedding=[
                    0.1,
                    0.2,
                    0.3,
                    0.4,
                ]
            )
        ]
    )

    provider = OpenAIEmbeddingProvider(
        client=client,
        model="text-embedding-3-small",
        dimensions=4,
    )

    result = provider.embed_document("KYCについて教えてください")

    assert result == [0.1, 0.2, 0.3, 0.4]

    client.embeddings.create.assert_called_once_with(
        model="text-embedding-3-small",
        input="KYCについて教えてください",
        dimensions=4,
    )


def test_openai_embedding_provider_rejects_blank_text() -> None:
    client = Mock()

    provider = OpenAIEmbeddingProvider(
        client=client,
        dimensions=4,
    )

    with pytest.raises(
        ValueError,
        match="text must not be blank",
    ):
        provider.embed_document("")

    client.embeddings.create.assert_not_called()


def test_openai_embedding_provider_rejects_wrong_dimensions() -> None:
    client = Mock()

    client.embeddings.create.return_value = Mock(
        data=[
            Mock(
                embedding=[
                    0.1,
                    0.2,
                    0.3,
                ]
            )
        ]
    )

    provider = OpenAIEmbeddingProvider(
        client=client,
        dimensions=4,
    )

    with pytest.raises(
        ValueError,
        match="Expected embedding with 4 dimensions, got 3",
    ):
        provider.embed_document("送金について教えてください")