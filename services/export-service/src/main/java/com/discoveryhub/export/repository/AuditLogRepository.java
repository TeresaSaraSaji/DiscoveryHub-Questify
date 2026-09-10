package com.discoveryhub.export.repository;

import com.discoveryhub.export.domain.AuditLogEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, String> {

    /**
     * Every filter is optional (FR-7.4: viewable and filterable per case and across the system).
     * {@code correlationId} is what ties one user action together across all five services, so it
     * doubles as the closest thing to a "per case" filter until P4 exists to hand us a real case id.
     */
    // Every optional parameter is cast explicitly. Without it, a request that leaves every filter
    // unset sends Postgres a bind value that is null on every path — nothing in the query gives
    // the driver a type to infer for it, and it refuses the whole statement with "could not
    // determine data type of parameter" rather than guessing.
    @Query("""
            select a from AuditLogEntity a
            where (cast(:service as string) is null or a.service = :service)
              and (cast(:action as string) is null or a.action = :action)
              and (cast(:outcome as string) is null or a.outcome = :outcome)
              and (cast(:subjectType as string) is null or a.subjectType = :subjectType)
              and (cast(:subjectId as string) is null or a.subjectId = :subjectId)
              and (cast(:correlationId as string) is null or a.correlationId = :correlationId)
              and (cast(:from as java.time.Instant) is null or a.occurredAt >= :from)
              and (cast(:to as java.time.Instant) is null or a.occurredAt <= :to)
            """)
    Page<AuditLogEntity> search(@Param("service") String service,
                                @Param("action") String action,
                                @Param("outcome") String outcome,
                                @Param("subjectType") String subjectType,
                                @Param("subjectId") String subjectId,
                                @Param("correlationId") String correlationId,
                                @Param("from") Instant from,
                                @Param("to") Instant to,
                                Pageable pageable);
}
