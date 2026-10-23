package io.corpscope.company;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.math.BigDecimal;

/** Query model. Bulk writes use JDBC and the Flyway constraints. */
@Entity
@Table(name = "company")
public class Company {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 8, unique = true)
    private String corpCode;

    @Column(nullable = false, length = 300)
    private String name;

    @Column(length = 6)
    private String stockCode;

    @Column(length = 13)
    private String corporateRegistrationNumber;

    @Column(length = 10)
    private String businessRegistrationNumber;

    @Column(length = 300)
    private String ceo;

    @Column(length = 10)
    private String industryCode;

    
    private LocalDate establishedDate;

    @Column(length = 500)
    private String address;

    @Column(length = 500)
    private String homepage;

    protected Company() {}
    public Long getId() { return id; }
    public String getCorpCode() { return corpCode; }
    public String getName() { return name; }
    public String getStockCode() { return stockCode; }
    public String getCorporateRegistrationNumber() { return corporateRegistrationNumber; }
    public String getBusinessRegistrationNumber() { return businessRegistrationNumber; }
    public String getCeo() { return ceo; }
    public String getIndustryCode() { return industryCode; }
    public LocalDate getEstablishedDate() { return establishedDate; }
    public String getAddress() { return address; }
    public String getHomepage() { return homepage; }
}

