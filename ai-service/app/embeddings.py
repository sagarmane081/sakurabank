from abc import ABC, abstractmethod
from collections.abc import Sequence

from openai import OpenAI
from sentence_transformers import SentenceTransformer


class EmbeddingProvider(ABC):
    """Provider-agnostic interface for generating text embeddings."""

    @property
    @abstractmethod
    def dimensions(self) -> int:
        """Return the embedding dimensionality."""
        raise NotImplementedError

    @abstractmethod
    def embed_document(self, text: str) -> list[float]:
        """Generate an embedding for a document/passsage."""
        raise NotImplementedError

    @abstractmethod
    def embed_query(self, text: str) -> list[float]:
        """Generate an embedding for a search query."""
        raise NotImplementedError


class FakeEmbeddingProvider(EmbeddingProvider):
    """Deterministic provider used by tests."""

    def __init__(self, dimensions: int = 8) -> None:
        if dimensions <= 0:
            raise ValueError("dimensions must be greater than zero")

        self._dimensions = dimensions

    @property
    def dimensions(self) -> int:
        return self._dimensions

    def _embed(self, text: str) -> list[float]:
        if not text or not text.strip():
            raise ValueError("text must not be blank")

        value = sum(text.encode("utf-8")) % 1000

        return [
            float((value + index) % 1000) / 1000.0
            for index in range(self._dimensions)
        ]

    def embed_document(self, text: str) -> list[float]:
        return self._embed(text)

    def embed_query(self, text: str) -> list[float]:
        return self._embed(text)


class OpenAIEmbeddingProvider(EmbeddingProvider):
    """Production embedding provider backed by the OpenAI API."""

    def __init__(
        self,
        client: OpenAI,
        model: str = "text-embedding-3-small",
        dimensions: int = 384,
    ) -> None:
        if not model.strip():
            raise ValueError("model must not be blank")

        if dimensions <= 0:
            raise ValueError("dimensions must be greater than zero")

        self.client = client
        self.model = model
        self._dimensions = dimensions

    @property
    def dimensions(self) -> int:
        return self._dimensions

    def _embed(self, text: str) -> list[float]:
        if not text or not text.strip():
            raise ValueError("text must not be blank")

        response = self.client.embeddings.create(
            model=self.model,
            input=text,
            dimensions=self._dimensions,
        )

        return validate_embedding(
            response.data[0].embedding,
            expected_dimensions=self._dimensions,
        )

    def embed_document(self, text: str) -> list[float]:
        return self._embed(text)

    def embed_query(self, text: str) -> list[float]:
        return self._embed(text)


class SentenceTransformerEmbeddingProvider(EmbeddingProvider):
    """Local multilingual E5 embedding provider."""

    MODEL_NAME = "intfloat/multilingual-e5-small"

    def __init__(
        self,
        model_name: str = MODEL_NAME,
    ) -> None:
        self.model = SentenceTransformer(model_name)

    @property
    def dimensions(self) -> int:
        return 384

    def embed_document(self, text: str) -> list[float]:
        return self._embed(f"passage: {text}")

    def embed_query(self, text: str) -> list[float]:
        return self._embed(f"query: {text}")

    def _embed(self, text: str) -> list[float]:
        if not text or not text.strip():
            raise ValueError("text must not be blank")

        embedding = self.model.encode(
            text,
            normalize_embeddings=True,
        )

        return validate_embedding(
            embedding.tolist(),
            expected_dimensions=self.dimensions,
        )


def validate_embedding(
    embedding: Sequence[float],
    expected_dimensions: int,
) -> list[float]:
    """Validate and normalize an embedding returned by a provider."""

    if expected_dimensions <= 0:
        raise ValueError(
            "expected_dimensions must be greater than zero"
        )

    if len(embedding) != expected_dimensions:
        raise ValueError(
            f"Expected embedding with {expected_dimensions} dimensions, "
            f"got {len(embedding)}"
        )

    return [float(value) for value in embedding]