package com.durable.scheduler.repository;

import com.durable.scheduler.domain.Reminder;
import com.durable.scheduler.domain.ReminderState;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ReminderRepository extends JpaRepository<Reminder, String> {

    @Query("""
            SELECT r FROM Reminder r
            WHERE r.state = com.durable.scheduler.domain.ReminderState.SCHEDULED
              AND r.executionInstant <= :now
            ORDER BY r.executionInstant ASC
            """)
    List<Reminder> findDueWork(@Param("now") Instant now, Pageable pageable);

    long countByState(ReminderState state);
}
