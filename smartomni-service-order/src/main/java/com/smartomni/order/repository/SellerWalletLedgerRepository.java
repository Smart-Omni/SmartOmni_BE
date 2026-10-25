package com.smartomni.order.repository;

import com.smartomni.order.entity.SellerWalletLedger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Transactional(readOnly = true)
public interface SellerWalletLedgerRepository extends JpaRepository<SellerWalletLedger, Long> {

    Optional<SellerWalletLedger> findByOrderId(Long orderId);

    boolean existsByOrderId(Long orderId);
}
