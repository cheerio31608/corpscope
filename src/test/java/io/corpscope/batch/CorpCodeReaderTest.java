package io.corpscope.batch;

import org.junit.jupiter.api.Test;
import org.springframework.batch.item.ExecutionContext;
import static org.assertj.core.api.Assertions.*;

class CorpCodeReaderTest {
    @Test void restartsFromCommittedCheckpoint() {
        var context = new ExecutionContext();
        var reader = new CorpCodeReader("00000002,00000001,00000001");
        reader.open(context);
        assertThat(reader.read()).isEqualTo("00000001");
        reader.update(context);
        assertThat(reader.read()).isEqualTo("00000002"); // Uncommitted read
        var restarted = new CorpCodeReader("00000002,00000001,00000001");
        restarted.open(context);
        assertThat(restarted.read()).isEqualTo("00000002");
        assertThat(restarted.read()).isNull();
    }

    @Test void refusesEmptyOrMalformedTargets() {
        assertThatThrownBy(() -> new CorpCodeReader("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CorpCodeReader("00126380,")).isInstanceOf(IllegalArgumentException.class);
    }
}
