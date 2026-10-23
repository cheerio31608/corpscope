package io.corpscope.financial.vintage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.corpscope.financial.ReportType;
import io.corpscope.financial.StandardAccount;
import io.corpscope.financial.StatementType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Narrow, reported three-month comparison. It is deliberately excluded from model exports. */
@Service
public class PeriodicReportedComparisonService {
    public record AccountEvidence(StandardAccount account,BigDecimal originalThreeMonth,
            BigDecimal comparativeThreeMonth,BigDecimal originalYtd,BigDecimal comparativeYtd,
            long originalDisplayUnit,long comparativeDisplayUnit,String comparison,boolean matches,
            BigDecimal current2025ThreeMonth,BigDecimal current2025Ytd) {}
    public record Pair(String corpCode,String reportType,String statementType,int currentYear,
            String currentReceipt,String currentZipSha256,int priorYear,String priorReceipt,
            String priorZipSha256,LocalDate comparativeEvidenceAvailableFromKstDate,
            String status,List<AccountEvidence> accounts) {}
    public record Manifest(String scope,List<Pair> comparisons) {}
    public record Response(long companyId,String corpCode,LocalDate asOf,String reportType,
            String currentReceipt,String currentZipSha256,String priorReceipt,String priorZipSha256,
            LocalDate evidenceAvailableFromKstDate,BigDecimal reportedThreeMonthRevenueGrowthPercent,
            String status,boolean forecastReady,List<String> warnings) {}

    private final PeriodicAsOfService periodic;
    private final Map<String,Pair> pairs;

    public PeriodicReportedComparisonService(PeriodicAsOfService periodic,ObjectMapper mapper) {
        this.periodic=periodic;
        try(var stream=new ClassPathResource("periodic-q3-comparative-audit.json").getInputStream()) {
            Manifest manifest=mapper.readValue(stream,Manifest.class);
            Map<String,Pair> indexed=new HashMap<>();
            for(Pair pair:manifest.comparisons()) {
                if(!"Q3".equals(pair.reportType()) || !"CFS".equals(pair.statementType())
                    || pair.currentYear()!=2025 || pair.priorYear()!=2024
                    || !pair.currentZipSha256().matches("[0-9a-f]{64}")
                    || !pair.priorZipSha256().matches("[0-9a-f]{64}")
                    || pair.accounts()==null || pair.accounts().size()!=3
                    || indexed.put(pair.corpCode(),pair)!=null)
                    throw new IllegalArgumentException("Invalid exact Q3 comparative pair");
            }
            if(indexed.size()!=10) throw new IllegalArgumentException("Incomplete Q3 comparative manifest");
            pairs=Map.copyOf(indexed);
        } catch(Exception ex) {throw new IllegalStateException("Invalid exact Q3 comparative manifest",ex);}
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ,
        noRollbackFor=FinancialAsOfUnavailableException.class)
    public Response find(long companyId,LocalDate asOf) {
        if(companyId<1 || asOf==null) throw new IllegalArgumentException("Company and KST as-of date required");
        QuarterlyAsOfResponse current=periodic.find(companyId,2025,ReportType.Q3,StatementType.CFS,asOf);
        Pair pair=pairs.get(current.corpCode());
        if(pair==null || asOf.isBefore(pair.comparativeEvidenceAvailableFromKstDate()))
            throw unavailable("No exact comparative review was available on this date");
        QuarterlyAsOfResponse previous=periodic.find(companyId,2024,ReportType.Q3,StatementType.CFS,asOf);
        if(!current.receiptNumber().equals(pair.currentReceipt())
            || !current.documentSha256().equals(pair.currentZipSha256())
            || !previous.receiptNumber().equals(pair.priorReceipt())
            || !previous.documentSha256().equals(pair.priorZipSha256()))
            throw unavailable("Latest report version differs from the reviewed comparative pair");
        Map<StandardAccount,QuarterlyAsOfResponse.Account> currentValues=index(current.accounts());
        Map<StandardAccount,QuarterlyAsOfResponse.Account> previousValues=index(previous.accounts());
        Set<StandardAccount> expected=EnumSet.of(StandardAccount.REVENUE,
            StandardAccount.OPERATING_PROFIT,StandardAccount.NET_INCOME);
        if(pair.accounts().stream().map(AccountEvidence::account).collect(java.util.stream.Collectors.toSet()).size()!=3
            || !pair.accounts().stream().allMatch(a -> expected.contains(a.account())))
            throw unavailable("Comparative account profile is incomplete");
        for(AccountEvidence evidence:pair.accounts()) {
            var now=currentValues.get(evidence.account());
            var before=previousValues.get(evidence.account());
            if(now==null || before==null || !evidence.matches()
                || !equal(now.amount(),evidence.current2025ThreeMonth())
                || !equal(now.cumulativeAmount(),evidence.current2025Ytd())
                || !equal(before.amount(),evidence.originalThreeMonth())
                || !equal(before.cumulativeAmount(),evidence.originalYtd())
                || before.displayUnit()!=evidence.originalDisplayUnit()
                || now.statementDivision().equals("BS") || before.statementDivision().equals("BS"))
                throw unavailable("Reviewed current/prior values differ from comparative evidence");
            BigDecimal quantum=BigDecimal.valueOf(evidence.comparativeDisplayUnit());
            BigDecimal compared="EXACT".equals(evidence.comparison()) ? before.amount()
                : "SOURCE_UNIT_ROUNDING".equals(evidence.comparison())
                    && evidence.originalDisplayUnit()<evidence.comparativeDisplayUnit()
                    ? roundedToUnit(before.amount(),quantum):null;
            BigDecimal comparedYtd="EXACT".equals(evidence.comparison()) ? before.cumulativeAmount()
                : "SOURCE_UNIT_ROUNDING".equals(evidence.comparison())
                    && evidence.originalDisplayUnit()<evidence.comparativeDisplayUnit()
                    ? roundedToUnit(before.cumulativeAmount(),quantum):null;
            if(!equal(compared,evidence.comparativeThreeMonth())
                || !equal(comparedYtd,evidence.comparativeYtd()))
                throw unavailable("Current report comparative cells differ from reviewed prior values");
        }
        BigDecimal currentRevenue=currentValues.get(StandardAccount.REVENUE).amount();
        BigDecimal priorRevenue=previousValues.get(StandardAccount.REVENUE).amount();
        BigDecimal growth=priorRevenue.signum()>0
            ? currentRevenue.subtract(priorRevenue).multiply(BigDecimal.valueOf(100))
                .divide(priorRevenue,4,RoundingMode.HALF_UP):null;
        return new Response(companyId,current.corpCode(),asOf,"Q3",current.receiptNumber(),
            current.documentSha256(),previous.receiptNumber(),previous.documentSha256(),
            pair.comparativeEvidenceAvailableFromKstDate(),growth,
            "REPORTED_THREE_MONTH_COMPARISON_NOT_MODEL_APPROVED",false,List.of(
                "REPORTED_CONSOLIDATED_TOTALS_NOT_ORGANIC_GROWTH",
                "BUSINESS_SCOPE_AND_FULL_ACCOUNTING_COMPARABILITY_NOT_REVIEWED",
                "EXCLUDED_FROM_FORECAST_FEATURE_EXPORT"));
    }

    private static Map<StandardAccount,QuarterlyAsOfResponse.Account> index(
            List<QuarterlyAsOfResponse.Account> accounts) {
        Map<StandardAccount,QuarterlyAsOfResponse.Account> indexed=new EnumMap<>(StandardAccount.class);
        for(var account:accounts) indexed.put(account.standardAccount(),account);
        return indexed;
    }
    private static BigDecimal roundedToUnit(BigDecimal value,BigDecimal unit) {
        return value.divide(unit,0,RoundingMode.HALF_UP).multiply(unit);
    }
    private static boolean equal(BigDecimal a,BigDecimal b) {
        return a!=null && b!=null && a.compareTo(b)==0;
    }
    private static FinancialAsOfUnavailableException unavailable(String detail) {
        return new FinancialAsOfUnavailableException(
            FinancialAsOfUnavailableException.Reason.COMPARISON_PAIR_NOT_VERIFIED,detail);
    }
}
