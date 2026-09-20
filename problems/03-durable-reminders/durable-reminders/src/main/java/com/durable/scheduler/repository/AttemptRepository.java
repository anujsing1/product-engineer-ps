package com.durable.scheduler.repository;

import com.durable.scheduler.domain.DeliveryAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttemptRepository extends JpaRepository<DeliveryAttempt, String> {

    long countByReminderId(String reminderId);
}
