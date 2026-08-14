from pydantic import BaseModel, ConfigDict


class FaqEntry(BaseModel):
    model_config = ConfigDict(frozen=True)

    id: str
    question: str
    answer: str