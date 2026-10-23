package io.corpscope.ingestion;

import io.corpscope.financial.StandardAccount;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class AccountStandardizer {
    private static final Map<String, StandardAccount> CODES = Map.of(
            "ifrs-full_Revenue", StandardAccount.REVENUE,
            // Earlier OpenDART taxonomies used this exact total; component codes remain unmapped.
            "ifrs_Revenue", StandardAccount.REVENUE,
            "dart_OperatingIncomeLoss", StandardAccount.OPERATING_PROFIT,
            "ifrs-full_ProfitLoss", StandardAccount.NET_INCOME,
            "ifrs-full_Assets", StandardAccount.ASSETS,
            "ifrs-full_Liabilities", StandardAccount.LIABILITIES,
            "ifrs-full_Equity", StandardAccount.EQUITY,
            "ifrs_Equity", StandardAccount.EQUITY);
    private static final Map<String, StandardAccount> NAMES = Map.ofEntries(
            Map.entry("매출", StandardAccount.REVENUE), Map.entry("매출액", StandardAccount.REVENUE),
            Map.entry("영업수익", StandardAccount.REVENUE), Map.entry("revenue", StandardAccount.REVENUE),
            Map.entry("영업이익", StandardAccount.OPERATING_PROFIT),
            Map.entry("영업이익(손실)", StandardAccount.OPERATING_PROFIT),
            Map.entry("당기순이익", StandardAccount.NET_INCOME),
            Map.entry("당기순이익(손실)", StandardAccount.NET_INCOME),
            // Historical Hyundai Motor totals reconcile to both pre-tax minus tax and ownership totals.
            Map.entry("연결당기순이익", StandardAccount.NET_INCOME),
            Map.entry("연결분기순이익", StandardAccount.NET_INCOME),
            Map.entry("연결반기순이익", StandardAccount.NET_INCOME),
            Map.entry("분기순이익(손실)", StandardAccount.NET_INCOME),
            Map.entry("자산총계", StandardAccount.ASSETS), Map.entry("부채총계", StandardAccount.LIABILITIES),
            Map.entry("자본총계", StandardAccount.EQUITY));

    public StandardAccount map(String code, String name, String division) {
        StandardAccount account = code == null ? null : CODES.get(code);
        if (account == null) account = NAMES.get(normalize(name));
        if (account == null) return StandardAccount.UNMAPPED;
        boolean balance = account == StandardAccount.ASSETS || account == StandardAccount.LIABILITIES
                || account == StandardAccount.EQUITY;
        // Identical labels in CF/SCE must not be used as income/balance-sheet totals.
        return (balance ? "BS".equals(division) : "IS".equals(division) || "CIS".equals(division))
                ? account : StandardAccount.UNMAPPED;
    }

    private String normalize(String name) {
        return name == null ? "" : name.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
