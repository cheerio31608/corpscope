package io.corpscope.financial;

public enum ReportType {
    Q1("11013"), HALF_YEAR("11012"), Q3("11014"), ANNUAL("11011");

    private final String dartCode;
    ReportType(String dartCode) { this.dartCode = dartCode; }
    public String dartCode() { return dartCode; }
}
