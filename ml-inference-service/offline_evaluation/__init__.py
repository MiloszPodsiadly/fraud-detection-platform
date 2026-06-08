"""Offline-only evaluation and model-card foundations for FDP-102/FDP-103 artifacts."""

from offline_evaluation.dataset_reader import read_fdp102_jsonl
from offline_evaluation.evaluation_runner import build_evaluation_report
from offline_evaluation.model_card_generator import build_model_card
from offline_evaluation.model_card_writer import model_card_json

__all__ = ["build_evaluation_report", "build_model_card", "model_card_json", "read_fdp102_jsonl"]
