package io.corpscope.operations;

import io.corpscope.financial.StatementType;
import io.corpscope.financial.vintage.FinancialFeatureService;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FinancialFeatureExportServiceTest {
    @ParameterizedTest @ValueSource(strings={"empty","duplicate","null-date","too-many","null-list"})
    void rejectsInvalidSamplingRequestsBeforeAnyDatabaseAccess(String kind) {
        var jdbc=mock(JdbcTemplate.class);
        var features=mock(FinancialFeatureService.class);
        LocalDate day=LocalDate.of(2018,1,1);
        List<LocalDate> dates=switch(kind) {
            case "empty" -> List.of();
            case "duplicate" -> List.of(day,day);
            case "null-date" -> Arrays.asList(day,null);
            case "null-list" -> null;
            default -> java.util.stream.IntStream.range(0,101).mapToObj(day::plusDays).toList();
        };
        assertThatThrownBy(() -> new FinancialFeatureExportService(jdbc,features).export("00164742",2016,StatementType.CFS,dates))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc,features);
    }
}
