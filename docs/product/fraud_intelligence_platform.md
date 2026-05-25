# Fraud Intelligence Platform

Status: FDP-82 contract foundation only; no runtime scoring behavior changes.

## Scope

The fraud intelligence platform is a bank-oriented, analyst-assisted multi-engine workbench for investigation
context. It is intended to help an analyst understand suspicious transactions and compare bounded signals from
different engines.

## Investigation Context Flow

The product context described by this contract is:

```text
transaction
-> features
-> multiple engines
-> risk intelligence
-> alert/case
-> analyst decision
-> feedback
-> model/rules evaluation
```

This flow defines investigation vocabulary. FDP-82 adds contract and documentation foundations only; it does not
connect multiple engines into the running scoring flow.

## Questions The Platform Supports

The platform is intended to support investigation of:

- which transactions are suspicious;
- why they are suspicious;
- which engines agree;
- which engines disagree;
- what the analyst should review;
- how analyst feedback can be used when evaluating rules and ML.

## Decision Boundary

An engine result is not a final banking decision. The platform provides analyst investigation context; it does not
perform core banking authorization, final payment decisioning, automatic blocking, automatic `DECLINE`, or
automatic `APPROVE`.

ML is not a final decision source in this scope. Engine comparison, aggregation, and runtime evaluation policy are
outside this change.

## Foundation Delivered By FDP-82

FDP-82 introduces:

- product and architecture boundary documentation;
- the shared `FraudEngineResult` model and its bounded supporting types;
- JSON contract examples and compatibility tests.

It does not modify an existing scored transaction event, scoring engine implementation, projection, API, or user
interface.
