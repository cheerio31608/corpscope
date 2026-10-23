package io.corpscope.financial.vintage;

import java.util.Map;

/** Exact 2025 Q3 CFS receipts and original labels checked against each official ZIP. */
final class ReviewedQ3Profiles {
    record Profile(String corpCode,String receipt,String incomeGroup,String incomeDivision,long displayUnit,
                   String revenueLabel,String operatingLabel,String netIncomeLabel) {}
    static final Map<String,Profile> BY_CORP=Map.of(
        "00106641",new Profile("00106641","20251114002676","{XBRL}IS_C2","IS",1_000_000,
            "매출액","영업이익","분기순이익"),
        "00145880",new Profile("00145880","20251114002160","{XBRL}IS_C1","CIS",1,
            "매출액 (주30,32)","영업이익(손실) (주32)","분기순이익(손실)"),
        "00159193",new Profile("00159193","20251114001180","{XBRL}IS_C1","CIS",1_000_000,
            "매출액","영업이익(손실)","당기순이익(손실)"),
        "00164742",new Profile("00164742","20251114002658","{XBRL}IS_C2","IS",1_000_000,
            "매출액","영업이익","연결분기순이익"),
        "00164779",new Profile("00164779","20251114001805","{XBRL}IS_C1","CIS",1_000_000,
            "매출액 (주4,21,28)","영업이익","분기순이익"),
        "00261285",new Profile("00261285","20251114000871","{XBRL}IS_C1","CIS",1_000_000,
            "수익(매출액) (주4,26,27,34)","영업이익","분기순이익(손실)"),
        "00266961",new Profile("00266961","20251114001436","{XBRL}IS_C1","CIS",1,
            "영업수익 (주5)","영업이익","분기순이익"),
        "01390344",new Profile("01390344","20251114001468","{XBRL}IS_C1","CIS",1_000,
            "매출액 (주24,29,30,38,42)","영업이익","분기순이익(손실)"),
        "01515323",new Profile("01515323","20251114000625","{XBRL}IS_C2","IS",1_000_000,
            "매출액","영업이익","분기순이익(손실)"));
    private ReviewedQ3Profiles() {}
}
