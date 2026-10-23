package io.corpscope.company;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CompanyRepository extends JpaRepository<Company, Long> {
    @Query("select c from Company c where lower(c.name) like :pattern escape '\\' order by c.name, c.id")
    Page<Company> searchByNamePrefix(String pattern, Pageable pageable);
}
