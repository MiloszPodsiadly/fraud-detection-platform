package com.frauddetection.alert.governance.modelcard;

import java.util.Optional;

public interface ModelCardProvider {

    Optional<ModelCard> currentCard();
}
