package io.corpscope.financial;

import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FinancialFactRepository extends JpaRepository<FinancialFact, Long> {
    @Query("""
            select f from FinancialFact f where f.companyId = :companyId
              and (:year is null or f.fiscalYear = :year)
              and (:report is null or f.reportType = :report)
              and (:statement is null or f.statementType = :statement)
            order by f.fiscalYear desc, f.reportType, f.statementType, f.statementDivision, f.accountKey
            """)
    Page<FinancialFact> search(Long companyId, Integer year, ReportType report, StatementType statement, Pageable pageable);

    List<FinancialFact> findByCompanyIdAndFiscalYearAndReportTypeAndStatementType(
            Long companyId, int fiscalYear, ReportType reportType, StatementType statementType);
}
