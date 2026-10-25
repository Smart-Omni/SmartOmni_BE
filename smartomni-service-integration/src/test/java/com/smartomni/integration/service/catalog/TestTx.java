package com.smartomni.integration.service.catalog;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A TransactionTemplate that runs the callback directly (no database). */
final class TestTx {

    private TestTx() {
    }

    static TransactionTemplate template() {
        PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new TransactionTemplate(manager);
    }
}
