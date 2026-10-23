package io.corpscope.disclosure;

import java.time.LocalDate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DisclosureRepository extends JpaRepository<Disclosure, Long> {
    @Query("""
            select d from Disclosure d where d.companyId = :companyId
              and (cast(:fromDate as date) is null or d.receiptDate >= :fromDate)
              and (cast(:toDate as date) is null or d.receiptDate <= :toDate)
            order by d.receiptDate desc, d.id desc
            """)
    Page<Disclosure> search(Long companyId, LocalDate fromDate, LocalDate toDate, Pageable pageable);
}
