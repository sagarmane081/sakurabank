from pathlib import Path

import pytest

from app.corpus import load_faq_corpus
from app.schemas import FaqEntry


def test_loads_sakurabank_faq_corpus() -> None:
    entries = load_faq_corpus()

    assert len(entries) == 25
    assert all(isinstance(entry, FaqEntry) for entry in entries)


def test_every_faq_has_required_fields() -> None:
    entries = load_faq_corpus()

    for entry in entries:
        assert entry.id
        assert entry.question
        assert entry.answer


def test_faq_ids_are_unique() -> None:
    entries = load_faq_corpus()

    ids = [entry.id for entry in entries]

    assert len(ids) == len(set(ids))


def test_faq_corpus_rejects_non_array(
    tmp_path: Path,
) -> None:
    path = tmp_path / "invalid.json"
    path.write_text(
        '{"id": "faq-001"}',
        encoding="utf-8",
    )

    with pytest.raises(
        ValueError,
        match="FAQ corpus must be a JSON array",
    ):
        load_faq_corpus(path)


def test_faq_corpus_rejects_empty_array(
    tmp_path: Path,
) -> None:
    path = tmp_path / "empty.json"
    path.write_text(
        "[]",
        encoding="utf-8",
    )

    with pytest.raises(
        ValueError,
        match="FAQ corpus must not be empty",
    ):
        load_faq_corpus(path)