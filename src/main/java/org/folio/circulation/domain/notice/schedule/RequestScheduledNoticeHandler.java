package org.folio.circulation.domain.notice.schedule;

import static java.util.Collections.singletonList;
import static org.folio.circulation.domain.notice.NoticeTiming.UPON_AT;
import static org.folio.circulation.domain.notice.TemplateContextUtil.createRequestNoticeContext;
import static org.folio.circulation.domain.notice.schedule.TriggeringEvent.HOLD_EXPIRATION;
import static org.folio.circulation.support.results.Result.ofAsync;
import static org.folio.circulation.support.results.ResultBinding.mapResult;
import static org.folio.circulation.support.utils.DateTimeUtil.isAfterMillis;
import static org.folio.circulation.support.utils.DateTimeUtil.isBeforeMillis;

import java.time.ZonedDateTime;
import java.util.concurrent.CompletableFuture;

import org.folio.circulation.domain.Request;
import org.folio.circulation.domain.notice.NoticeTiming;
import org.folio.circulation.domain.representations.logs.NoticeLogContext;
import org.folio.circulation.domain.representations.logs.NoticeLogContextItem;
import org.folio.circulation.infrastructure.storage.loans.LoanRepository;
import org.folio.circulation.infrastructure.storage.requests.RequestRepository;
import org.folio.circulation.rules.CirculationRuleMatch;
import org.folio.circulation.support.Clients;
import org.folio.circulation.support.results.Result;
import org.folio.circulation.support.utils.ClockUtil;

import io.vertx.core.json.JsonObject;

public abstract class RequestScheduledNoticeHandler extends ScheduledNoticeHandler {
  protected final RequestRepository requestRepository;

  public RequestScheduledNoticeHandler(Clients clients,
    LoanRepository loanRepository, RequestRepository requestRepository) {

    super(clients, loanRepository);
    this.requestRepository = requestRepository;
  }

  @Override
  protected abstract CompletableFuture<Result<ScheduledNoticeContext>> fetchData(
    ScheduledNoticeContext context);

  @Override
  protected CompletableFuture<Result<ScheduledNoticeContext>> fetchPatronNoticePolicyId(
    ScheduledNoticeContext context) {

    if (isNoticeIrrelevant(context)) {
      return ofAsync(() -> context);
    }

    return patronNoticePolicyRepository.lookupPolicyId(context.getRequest())
      .thenApply(mapResult(CirculationRuleMatch::getPolicyId))
      .thenApply(mapResult(context::withPatronNoticePolicyId));
  }

  @Override
  protected boolean isNoticeIrrelevant(ScheduledNoticeContext context) {
    return isNoticeNotRelevantYet(context) || isNoticeNoLongerRelevant(context);
  }

  @Override
  protected JsonObject buildNoticeContextJson(ScheduledNoticeContext context) {
    return createRequestNoticeContext(context.getRequest());
  }

  @Override
  protected CompletableFuture<Result<ScheduledNotice>> updateNotice(ScheduledNoticeContext context) {
    Request request = context.getRequest();
    ScheduledNotice notice = context.getNotice();
    boolean isNoticeNonRecurring = !notice.getConfiguration().isRecurring();

    if (isNoticeNotRelevantYet(context)) {
      return ofAsync(() -> notice);
    }

    if (request.isClosed() || isNoticeNonRecurring || isNoticeNoLongerRelevant(context)) {
      return deleteNoticeAsIrrelevant(notice);
    }

    ScheduledNotice nextRecurringNotice = updateNoticeNextRunTime(notice);

    return nextRecurringNoticeIsNotRelevant(nextRecurringNotice, request)
      ? deleteNoticeAsIrrelevant(notice)
      : scheduledNoticesRepository.update(nextRecurringNotice);
  }

  @Override
  protected NoticeLogContext buildNoticeLogContext(ScheduledNoticeContext context) {
    ScheduledNotice notice = context.getNotice();
    Request request = context.getRequest();

    NoticeLogContextItem logContextItem = NoticeLogContextItem.from(request)
      .withTemplateId(notice.getConfiguration().getTemplateId())
      .withTriggeringEvent(notice.getTriggeringEvent().getRepresentation())
      .withNoticePolicyId(context.getPatronNoticePolicyId());

    return new NoticeLogContext()
      .withUser(request.getRequester())
      .withRequestId(request.getId())
      .withItems(singletonList(logContextItem));
  }

  private static boolean isNoticeNotRelevantYet(ScheduledNoticeContext context) {
    Request request = context.getRequest();
    ScheduledNotice notice = context.getNotice();

    return notice.getConfiguration().getTiming() == UPON_AT && request.isOpen() &&
      !(notice.getTriggeringEvent() == HOLD_EXPIRATION && request.isNotYetFilled());
  }

  private static boolean isNoticeNoLongerRelevant(ScheduledNoticeContext context) {
    return context.getNotice().getTriggeringEvent() == HOLD_EXPIRATION &&
      isHoldExpirationNoticeIrrelevant(context);
  }

  private static boolean isHoldExpirationNoticeIrrelevant(ScheduledNoticeContext context) {
    Request request = context.getRequest();

    return context.getNotice().getConfiguration().getTiming() == UPON_AT
      ? request.isClosed() && !request.isPickupExpired() || request.isNotYetFilled()
      : request.isClosed();
  }

  private static ScheduledNotice updateNoticeNextRunTime(ScheduledNotice notice) {
    final ZonedDateTime systemTime = ClockUtil.getZonedDateTime();
    ScheduledNoticeConfig noticeConfig = notice.getConfiguration();

    ZonedDateTime recurringNoticeNextRunTime = noticeConfig
      .getRecurringPeriod().plusDate(notice.getNextRunTime());

    if (isBeforeMillis(recurringNoticeNextRunTime, systemTime)) {
      recurringNoticeNextRunTime = noticeConfig
        .getRecurringPeriod().plusDate(systemTime);
    }

    return notice.withNextRunTime(recurringNoticeNextRunTime);
  }

  private static boolean nextRecurringNoticeIsNotRelevant(ScheduledNotice notice, Request request) {
    ScheduledNoticeConfig noticeConfig = notice.getConfiguration();

    return noticeConfig.isRecurring() &&
      noticeConfig.getTiming() == NoticeTiming.BEFORE &&
      nextRunTimeIsAfterRequestExpiration(notice, request);
  }

  private static boolean nextRunTimeIsAfterRequestExpiration(ScheduledNotice notice, Request request) {
    ZonedDateTime nextRunTime = notice.getNextRunTime();
    ZonedDateTime requestExpirationDate = request.getRequestExpirationDate();
    ZonedDateTime holdShelfExpirationDate = request.getHoldShelfExpirationDate();

    return requestExpirationDate != null && isAfterMillis(nextRunTime, requestExpirationDate) ||
      holdShelfExpirationDate != null && isAfterMillis(nextRunTime, holdShelfExpirationDate);
  }
}
