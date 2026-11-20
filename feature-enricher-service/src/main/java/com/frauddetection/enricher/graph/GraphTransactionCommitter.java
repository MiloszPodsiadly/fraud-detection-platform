package com.frauddetection.enricher.graph;

import com.mongodb.client.ClientSession;

@FunctionalInterface
interface GraphTransactionCommitter {
    void commit(ClientSession session);
}
