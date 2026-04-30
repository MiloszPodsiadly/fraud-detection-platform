package com.frauddetection.alert.audit;

public class PostCommitEvidenceIncompleteException extends RuntimeException {
    public PostCommitEvidenceIncompleteException() {
        super("Mutation committed, but audit evidence is incomplete.");
    }
}
