import json
from pathlib import Path

from app.schemas import FaqEntry

CORPUS_PATH = (
    Path(__file__).resolve().parent.parent / "corpus" / "faq.json"
)


def load_faq_corpus(
    corpus_path: Path = CORPUS_PATH,
) -> list[FaqEntry]:
    with corpus_path.open(
        "r",
        encoding="utf-8",
    ) as file:

        raw_entries = json.load(file)

    if not isinstance(raw_entries, list):
        raise ValueError("FAQ corpus must be a JSON array.")

    entries = [
        FaqEntry.model_validate(entry)
        for entry in raw_entries
    ]

    if not entries:
        raise ValueError("FAQ corpus must not be empty.")

    return entries