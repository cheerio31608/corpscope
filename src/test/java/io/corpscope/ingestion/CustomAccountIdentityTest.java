package io.corpscope.ingestion;

import io.corpscope.external.dart.DartFinancialResponse;
import io.corpscope.financial.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CustomAccountIdentityTest {
    private final DartTransformer transformer = new DartTransformer(new AccountStandardizer());
    private final FinancialPeriod period = new FinancialPeriod(2024, ReportType.ANNUAL, StatementType.CFS);

    @Test void repeatedCustomLabelsKeepBothAmountsAndSnapshotOrdinalWithoutBeingSummed() {
        var first = row("9", "100");
        var second = row("21", "200");
        var rows = transformer.financials(List.of(first, second), "00000003", period);
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().amount()).isEqualByComparingTo("100");
        assertThat(rows.getLast().amount()).isEqualByComparingTo("200");
        assertThat(rows.getFirst().accountKey()).isNotEqualTo(rows.getLast().accountKey());
        assertThat(rows).allMatch(row -> row.standardAccount() == StandardAccount.UNMAPPED);
        assertThat(transformer.financials(List.of(second, first), "00000003", period))
                .containsExactly(rows.getLast(), rows.getFirst());
    }

    @Test void missingOrRepeatedOrdinalStillFails() {
        assertThatThrownBy(() -> transformer.financials(List.of(row(null, "100"), row("21", "200")), "00000003", period))
                .isInstanceOf(DataQualityException.class);
        assertThatThrownBy(() -> transformer.financials(List.of(row("9", "100"), row("9", "200")), "00000003", period))
                .isInstanceOf(DataQualityException.class);
    }

    @Test void uniqueExistingAccountIdentityIsUnchangedEvenWhenOrdinalIsPresent() {
        var raw = row("9", "100");
        assertThat(transformer.financials(List.of(raw), "00000003", period))
                .containsExactly(transformer.financial(raw, "00000003", period));
    }

    private DartFinancialResponse.Account row(String ordinal, String amount) {
        return new DartFinancialResponse.Account("00000003", "2024", "11011", "20250301000001", "BS",
                "-표준계정코드 미사용-", "금융업채권", "-", amount, "", "KRW", ordinal);
    }
}
