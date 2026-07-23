"""Offline-only evaluation foundations."""

from offline_evaluation.dataset_reader import read_fdp102_jsonl
from offline_evaluation.evaluation_runner import build_evaluation_report

__all__ = ["build_evaluation_report", "read_fdp102_jsonl"]
