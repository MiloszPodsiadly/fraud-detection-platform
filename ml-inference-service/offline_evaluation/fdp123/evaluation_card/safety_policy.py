from __future__ import annotations


FORBIDDEN_FIELD_NAMES = {
    "transactionid",
    "feedbackid",
    "customerid",
    "correlationid",
    "createdby",
    "evaluationrecordid",
    "transactionreference",
    "notes",
    "rawnotes",
    "rawpayload",
    "rawmlrequest",
    "rawmlresponse",
    "rawfeaturevector",
    "rawevidence",
    "groundtruth",
    "traininglabel",
    "modeltraininglabel",
    "finaldecision",
    "paymentdecision",
    "paymentauthorization",
    "promotionrecommended",
    "thresholdrecommendation",
    "productionready",
    "certifiedforproduction",
    "bankcertified",
    "token",
    "secret",
    "password",
}

FORBIDDEN_VALUE_TERMS = FORBIDDEN_FIELD_NAMES | {
    "autodecline",
    "autoapprove",
    "autoblock",
    "champion",
    "productiondecisioning",
    "productionapproved",
    "promotionapproved",
    "promotionready",
}

FORBIDDEN_INPUT_COMPACT_TERMS = FORBIDDEN_FIELD_NAMES | {
    "decisionreasoncodes",
}

FORBIDDEN_OUTPUT_TERMS = FORBIDDEN_INPUT_COMPACT_TERMS | {
    "transactionid",
    "feedbackid",
    "customerid",
    "correlationid",
    "createdby",
    "decisionreasoncodes",
}
