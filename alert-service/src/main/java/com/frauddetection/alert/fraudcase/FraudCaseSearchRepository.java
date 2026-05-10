package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.persistence.FraudCaseDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface FraudCaseSearchRepository {

    Page<FraudCaseDocument> search(FraudCaseSearchCriteria criteria, Pageable pageable);
}
