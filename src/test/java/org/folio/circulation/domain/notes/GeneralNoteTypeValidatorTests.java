package org.folio.circulation.domain.notes;

import static api.support.matchers.FailureMatcher.hasValidationFailure;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import java.util.Optional;
import java.util.UUID;

import org.folio.circulation.support.Result;
import org.junit.Test;

import lombok.val;

public class GeneralNoteTypeValidatorTests {
  @Test
  public void allowSingleNoteType() {
    val validator = new GeneralNoteTypeValidator();

    final NoteType noteType = generateNoteType();

    val singleNoteType = Result.of(() -> Optional.of(noteType));

    val result = validator.refuseIfNoteTypeNotFound(singleNoteType);

    assertThat(result.succeeded(), is(true));
    assertThat(result.value(), is(noteType));
  }

  @Test
  public void failWhenNoNoteType() {
    val validator = new GeneralNoteTypeValidator();

    val result = validator.refuseIfNoteTypeNotFound(Result.of(Optional::empty));

    assertThat(result, hasValidationFailure("No General note type found"));
  }

  private NoteType generateNoteType() {
    return new NoteType(UUID.randomUUID().toString(), "General");
  }
}
