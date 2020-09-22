package org.folio.circulation.resources;

import static org.folio.circulation.support.results.ResultBinding.mapResult;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;

import org.folio.circulation.domain.MultipleRecords;
import org.folio.circulation.domain.notice.schedule.DueDateScheduledNoticeHandler;
import org.folio.circulation.domain.notice.schedule.ScheduledNotice;
import org.folio.circulation.infrastructure.storage.ConfigurationRepository;
import org.folio.circulation.infrastructure.storage.notices.ScheduledNoticesRepository;
import org.folio.circulation.domain.notice.schedule.TriggeringEvent;
import org.folio.circulation.services.EventPublisher;
import org.folio.circulation.support.Clients;
import org.folio.circulation.support.CqlSortBy;
import org.folio.circulation.support.results.Result;
import org.folio.circulation.support.http.client.PageLimit;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

import io.vertx.core.http.HttpClient;

public class DueDateScheduledNoticeProcessingResource extends ScheduledNoticeProcessingResource {

  public DueDateScheduledNoticeProcessingResource(HttpClient client) {
    super("/circulation/due-date-scheduled-notices-processing", client);
  }

  @Override
  protected CompletableFuture<Result<MultipleRecords<ScheduledNotice>>> findNoticesToSend(
    ConfigurationRepository configurationRepository,
    ScheduledNoticesRepository scheduledNoticesRepository, PageLimit pageLimit) {

    return scheduledNoticesRepository.findNotices(
      DateTime.now(DateTimeZone.UTC), true,
      Collections.singletonList(TriggeringEvent.DUE_DATE),
      CqlSortBy.ascending("nextRunTime"), pageLimit);
  }

  @Override
  protected CompletableFuture<Result<MultipleRecords<ScheduledNotice>>> handleNotices(
    Clients clients, MultipleRecords<ScheduledNotice> noticesResult, EventPublisher eventPublisher) {

    final DueDateScheduledNoticeHandler dueDateNoticeHandler =
      DueDateScheduledNoticeHandler.using(clients, DateTime.now(DateTimeZone.UTC), eventPublisher);

    return dueDateNoticeHandler.handleNotices(noticesResult.getRecords())
      .thenApply(mapResult(v -> noticesResult));
  }
}
