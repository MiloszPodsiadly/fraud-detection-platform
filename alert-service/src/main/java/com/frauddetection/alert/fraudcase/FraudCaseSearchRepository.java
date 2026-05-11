package com.frauddetection.alert.fraudcase;

import com.frauddetection.alert.persistence.FraudCaseDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

public interface FraudCaseSearchRepository {

    Page<FraudCaseDocument> search(FraudCaseSearchCriteria criteria, Pageable pageable);

    Slice<FraudCaseDocument> searchSlice(FraudCaseSearchCriteria criteria, Pageable pageable);
}
