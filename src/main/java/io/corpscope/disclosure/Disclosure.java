package io.corpscope.disclosure;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.math.BigDecimal;

/** Query model. Bulk writes use JDBC and the Flyway constraints. */
@Entity
@Table(name = "disclosure")
public class Disclosure {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long companyId;

    @Column(nullable = false, length = 14, unique = true)
    private String receiptNumber;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(nullable = false, length = 30)
    private String disclosureType;

    @Column(nullable = false)
    private LocalDate receiptDate;

    @Column(length = 5)
    private String reportCode;

    @Column(nullable = false, length = 500)
    private String originalUrl;

    protected Disclosure() {}
    public Long getId() { return id; }
    public Long getCompanyId() { return companyId; }
    public String getReceiptNumber() { return receiptNumber; }
    public String getTitle() { return title; }
    public String getDisclosureType() { return disclosureType; }
    public LocalDate getReceiptDate() { return receiptDate; }
    public String getReportCode() { return reportCode; }
    public String getOriginalUrl() { return originalUrl; }
}

