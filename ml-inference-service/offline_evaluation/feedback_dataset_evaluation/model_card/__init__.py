"""Canonical exact-artifact ML Model Card contract.

The card reports bounded offline evidence for one exact model artifact. It is
diagnostic evidence only and never grants production or promotion authority.
"""

from offline_evaluation.feedback_dataset_evaluation.model_card.schema import (
    MODEL_CARD_SCHEMA_VERSION,
    MODEL_CARD_TYPE,
    ModelCardValidationError,
    validate_model_card,
)

__all__ = [
    "MODEL_CARD_SCHEMA_VERSION",
    "MODEL_CARD_TYPE",
    "ModelCardValidationError",
    "validate_model_card",
]
