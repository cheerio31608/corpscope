package io.corpscope.financial.vintage;

import io.corpscope.financial.StandardAccount;
import io.corpscope.ingestion.DataQualityException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class KepcoAnnualReportParserTest {
    static String fixture2023(boolean corrected) throws Exception {
        try(var in=KepcoAnnualReportParserTest.class.getResourceAsStream("/fixtures/kepco-2023-"+(corrected ? "corrected":"original")+"-totals.xml")) {
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    static String fixture(int year) throws Exception {
        try(var in=KepcoAnnualReportParserTest.class.getResourceAsStream("/fixtures/kepco-"+year+"-totals.xml")) {
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    static String fixture2022(String receipt) throws Exception {
        try(var in=KepcoAnnualReportParserTest.class.getResourceAsStream("/fixtures/kepco-2022-"+receipt+"-totals.xml")) {
            return new String(in.readAllBytes(),StandardCharsets.UTF_8);
        }
    }
    @ParameterizedTest @ValueSource(strings={"20230320001084","20230410003765","20230525000309"})
    void readsAll2022VersionsWithoutConflatingTheirIdentity(String receipt) throws Exception {
        var parsed=new KepcoAnnualReportParser().parse(receipt,Hyundai2016ReportParserTest.zip(receipt,fixture2022(receipt),StandardCharsets.UTF_8));
        assertThat(parsed.current().mainEntry()).isEqualTo(receipt+".xml");
        assertThat(parsed.current().accounts()).hasSize(6).filteredOn(a -> a.standardAccount()==StandardAccount.NET_INCOME)
            .allSatisfy(a -> assertThat(a.amount()).isEqualByComparingTo("-24429108000000"));
        assertThat(parsed.current().evidence().toString()).doesNotContain("ACODE=", "ACONTEXT=", "ifrs-full_");
    }
    @ParameterizedTest @ValueSource(strings={"meaning","column","unit","balance","attribute"})
    void rejects2022ScopeDrift(String kind) throws Exception {
        String receipt="20230525000309", original=fixture2022(receipt);
        String changed=switch(kind) {
            case "meaning" -> original.replace("당기순이익(손실)","총포괄손익");
            case "column" -> original.replace("제 61 기","제 60 기");
            case "unit" -> original.replace("백만원","원");
            case "balance" -> original.replace("234,804,994","234,804,995");
            default -> original.replace("<TD ALIGN=\"RIGHT\"","<TD ACONTEXT=\"CFY2022\" ALIGN=\"RIGHT\"");
        };
        assertThat(changed).isNotEqualTo(original);
        byte[] zip=Hyundai2016ReportParserTest.zip(receipt,changed,StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new KepcoAnnualReportParser().parse(receipt,zip)).isInstanceOf(DataQualityException.class);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void readsBoth2023PlainVersionsAndUsesTotalLoss(boolean corrected) throws Exception {
        String receipt=corrected ? "20240329002856":"20240318000954";
        var result=new KepcoAnnualReportParser().parse(receipt,Hyundai2016ReportParserTest.zip(receipt,fixture2023(corrected),StandardCharsets.UTF_8));
        assertThat(result.current().accounts()).hasSize(6).filteredOn(a -> a.standardAccount()==StandardAccount.NET_INCOME)
            .allSatisfy(a -> assertThat(a.amount()).isEqualByComparingTo("-4716144000000"));
        assertThat(result.comparativeTotals().get(StandardAccount.NET_INCOME)).isEqualByComparingTo("-24429108000000");
        assertThat(result.current().evidence().toString()).doesNotContain("ACODE=","ACONTEXT=","ifrs-full_");
    }
    @ParameterizedTest @ValueSource(strings={"company","unit","year","column","profit-meaning","missing","balance","attribute"})
    void rejectsWrong2023PlainScopeOrAmounts(String kind) throws Exception {
        String original=fixture2023(true);
        String changed=switch(kind) {
            case "company" -> original.replace("00159193","00126380");
            case "unit" -> original.replace("백만원","천원");
            case "year" -> original.replace("2023.12.31","2022.12.31");
            case "column" -> original.replace("제 62 기","제 61 기");
            case "profit-meaning" -> original.replace("당기순손실","지배기업 소유주 순손실");
            case "missing" -> original.replace("88,219,461","");
            case "balance" -> original.replace("239,714,965","239,714,966");
            default -> original.replace("<TE ALIGN=\"RIGHT\"","<TE ACODE=\"ifrs-full_Revenue\" ALIGN=\"RIGHT\"");
        };
        assertThat(changed).isNotEqualTo(original);
        byte[] zip=Hyundai2016ReportParserTest.zip("20240329002856",changed,StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new KepcoAnnualReportParser().parse("20240329002856",zip)).isInstanceOf(DataQualityException.class);
    }

    @ParameterizedTest @ValueSource(ints={2024,2025})
    void readsConsolidatedTotalProfitAndPreservesNegativeComparative(int year) throws Exception {
        String receipt=year==2024 ? "20250318000747":"20260317000686";
        var parsed=new KepcoAnnualReportParser().parse(receipt,Hyundai2016ReportParserTest.zip(receipt,fixture(year),StandardCharsets.UTF_8));
        assertThat(parsed.current().accounts()).hasSize(6).filteredOn(a -> a.standardAccount()==StandardAccount.NET_INCOME)
            .allSatisfy(a -> assertThat(a.amount()).isEqualByComparingTo(year==2024 ? "3621968000000":"8666656000000"));
        assertThat(parsed.comparativeTotals().get(StandardAccount.OPERATING_PROFIT))
            .isEqualByComparingTo(year==2024 ? "-4541648000000":"8364710000000");
        assertThat(parsed.current().profile()).isEqualTo("KEPCO_"+year+"_ANNUAL_CFS_SIX_TOTALS_V1");
    }

    @ParameterizedTest @ValueSource(strings={"company","unit","currency","context","separate","group","owner-profit",
        "balance","previous-balance","scale","negated","missing","comparative-header","older-context"})
    void rejectsWrongCompanyMeaningPeriodUnitOrIncompleteAmount(String kind) throws Exception {
        String original=fixture(2025);
        String changed=switch(kind) {
            case "company" -> original.replace("00159193","00164779");
            case "unit" -> original.replace("백만원","천원");
            case "currency" -> original.replace("million, KRW","million, USD");
            case "context" -> original.replace("CFY2025","PFY2024");
            case "separate" -> original.replace("_ConsolidatedMember","_SeparateMember");
            case "group" -> original.replace("{XBRL}IS_C1","{XBRL}IS_C2");
            case "owner-profit" -> original.replace("ifrs-full_ProfitLoss\"","ifrs-full_ProfitLossAttributableToOwnersOfParent\"");
            case "balance" -> original.replace("254,927,457","254,927,458");
            case "previous-balance" -> original.replace("246,807,795","246,807,796");
            case "scale" -> original.replace("ADECIMAL=\"-6\"","ADECIMAL=\"-3\"");
            case "negated" -> original.replace("ANEGATED=\"N\"","ANEGATED=\"Y\"");
            case "missing" -> original.replace("97,429,346","");
            case "comparative-header" -> original.replace("제 64 기          2024.12.31 현재","제 63 기          2023.12.31 현재");
            default -> original.replace("BPFY2023","BPFY2022");
        };
        assertThat(changed).isNotEqualTo(original);
        byte[] zip=Hyundai2016ReportParserTest.zip("20260317000686",changed,StandardCharsets.UTF_8);
        assertThatThrownBy(() -> new KepcoAnnualReportParser().parse("20260317000686",zip)).isInstanceOf(DataQualityException.class);
    }

    @Test void rejectsUnreviewedReceiptWithoutGeneralizingToOtherYears() {
        assertThatThrownBy(() -> new KepcoAnnualReportParser().parse("20240319000000",new byte[0]))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
