package io.corpscope.financial;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.math.BigDecimal;

/** Query model. Bulk writes use JDBC and the Flyway constraints. */
@Entity
@Table(name = "financial_fact")
public class FinancialFact {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long companyId;

    @Column(nullable = false)
    private int fiscalYear;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private ReportType reportType;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 3)
    private StatementType statementType;

    @Column(nullable = false, length = 3)
    private String statementDivision;

    @Column(nullable = false, length = 64)
    private String accountKey;

    @Column(length = 300)
    private String accountCode;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 30)
    private StandardAccount standardAccount;

    @Column(nullable = false, length = 500)
    private String rawAccountName;

    @Column(nullable = false, columnDefinition = "text")
    private String accountDetail;

    @Column(precision = 38, scale = 2)
    private BigDecimal amount;

    @Column(precision = 38, scale = 2)
    private BigDecimal cumulativeAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false, length = 14)
    private String receiptNumber;

    @Column(nullable = false, length = 30)
    private String source;

    @Column(nullable = false)
    private Long rawIngestionId;

    protected FinancialFact() {}
    public Long getId() { return id; }
    public Long getCompanyId() { return companyId; }
    public int getFiscalYear() { return fiscalYear; }
    public ReportType getReportType() { return reportType; }
    public StatementType getStatementType() { return statementType; }
    public String getStatementDivision() { return statementDivision; }
    public String getAccountKey() { return accountKey; }
    public String getAccountCode() { return accountCode; }
    public StandardAccount getStandardAccount() { return standardAccount; }
    public String getRawAccountName() { return rawAccountName; }
    public String getAccountDetail() { return accountDetail; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getCumulativeAmount() { return cumulativeAmount; }
    public String getCurrency() { return currency; }
    public String getReceiptNumber() { return receiptNumber; }
    public String getSource() { return source; }
    public Long getRawIngestionId() { return rawIngestionId; }
}

