package io.corpscope.financial.vintage;

import io.corpscope.ingestion.DataQualityException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

class ReportArchiveReaderTest {
    private static final String RECEIPT="20260317000686";
    private byte[] zip(int mainMiB,int attachmentMiB) throws Exception {
        var output=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(RECEIPT+".xml"));
            zip.write(" ".repeat(mainMiB*1024*1024).getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            if(attachmentMiB>0) {
                zip.putNextEntry(new ZipEntry(RECEIPT+"_00761.xml"));
                zip.write(" ".repeat(attachmentMiB*1024*1024).getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
    @Test void largerReviewedProfileDoesNotRelaxDefaultLimit() throws Exception {
        byte[] source=zip(13,0);
        assertThatThrownBy(() -> ReportArchiveReader.read(RECEIPT,source)).isInstanceOf(DataQualityException.class);
        assertThat(ReportArchiveReader.read(RECEIPT,source,24*1024*1024).text()).hasSize(13*1024*1024);
    }
    @Test void optInStillRejectsOversizedEntry() throws Exception {
        byte[] source=zip(25,0);
        assertThatThrownBy(() -> ReportArchiveReader.read(RECEIPT,source,24*1024*1024)).isInstanceOf(DataQualityException.class);
    }
    @Test void optInKeepsTheThirtyMiBTotalExpansionLimit() throws Exception {
        byte[] source=zip(16,16);
        assertThatThrownBy(() -> ReportArchiveReader.read(RECEIPT,source,24*1024*1024)).isInstanceOf(DataQualityException.class);
    }
    @ParameterizedTest @ValueSource(ints={0,25165825,Integer.MAX_VALUE})
    void callerCannotDisableTheEntryBound(int limit) {
        assertThatThrownBy(() -> ReportArchiveReader.read(RECEIPT,new byte[0],limit)).isInstanceOf(DataQualityException.class);
    }
}
