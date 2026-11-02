package com.gen3.recommenderagent.application.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gen3.shared.readhistory.port.ReadHistoryRepository;
import java.io.IOException;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ReadHistoryExclusionsTest {
  @Test
  void absentUserAndDisabledAdapterHaveNoExclusions() throws Exception {
    assertThat(new ReadHistoryExclusions(Optional.empty(), 10).load("u", "r")).isEmpty();
    var repository = mock(ReadHistoryRepository.class);
    when(repository.findReadBookIds("u", 11)).thenReturn(Set.of());
    assertThat(new ReadHistoryExclusions(Optional.of(repository), 10).load("u", "r")).isEmpty();
    verify(repository).findReadBookIds("u", 11);
  }

  @Test
  void maximumIsAcceptedButOversizedHistoryIsNotTruncated(CapturedOutput output) throws Exception {
    var repository = mock(ReadHistoryRepository.class);
    var service = new ReadHistoryExclusions(Optional.of(repository), 2);
    when(repository.findReadBookIds("u", 3)).thenReturn(Set.of("a", "b"), Set.of("a", "b", "c"));
    assertThat(service.load("u", "first")).containsExactlyInAnyOrder("a", "b");
    assertThat(service.load("u", "second")).isEmpty();
    assertThat(output).contains("requestId=second", "reason=HISTORY_LIMIT_EXCEEDED");
  }

  @Test
  void outageFailsOpenWithRequestWarningWithoutLoggingDatabaseSecrets(CapturedOutput output)
      throws Exception {
    var repository = mock(ReadHistoryRepository.class);
    when(repository.findReadBookIds("u", 3)).thenThrow(new IOException("secret-password"));
    assertThat(new ReadHistoryExclusions(Optional.of(repository), 2).load("u", "request-123"))
        .isEmpty();
    assertThat(output)
        .contains("requestId=request-123", "reason=STORE_UNAVAILABLE")
        .doesNotContain("secret-password");
  }
}
