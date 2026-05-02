package com.frauddetection.alert.regulated;

import com.mongodb.client.MongoClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
class MongoTransactionConfiguration {

    @Bean
    @ConditionalOnMissingBean(PlatformTransactionManager.class)
    MongoTransactionManager mongoTransactionManager(MongoDatabaseFactory databaseFactory, MongoClient ignoredClient) {
        return new MongoTransactionManager(databaseFactory);
    }
}
