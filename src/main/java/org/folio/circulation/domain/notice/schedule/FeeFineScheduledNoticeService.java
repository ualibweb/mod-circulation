package org.folio.circulation.domain.notice.schedule;

import static org.folio.circulation.domain.notice.NoticeEventType.AGED_TO_LOST_FINE_CHARGED;
import static org.folio.circulation.domain.notice.NoticeEventType.AGED_TO_LOST_RETURNED;
import static org.folio.circulation.domain.notice.NoticeEventType.OVERDUE_FINE_RENEWED;
import static org.folio.circulation.domain.notice.NoticeEventType.OVERDUE_FINE_RETURNED;
import static org.folio.circulation.support.AsyncCoordinationUtil.allOf;
import static org.folio.circulation.support.results.Result.ofAsync;
import static org.folio.circulation.support.results.Result.succeeded;

import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.folio.circulation.domain.CheckInContext;
import org.folio.circulation.domain.FeeFineAction;
import org.folio.circulation.domain.Loan;
import org.folio.circulation.domain.notice.NoticeConfiguration;
import org.folio.circulation.domain.notice.NoticeEventType;
import org.folio.circulation.domain.notice.NoticeTiming;
import org.folio.circulation.domain.notice.PatronNoticePolicy;
import org.folio.circulation.infrastructure.storage.notices.PatronNoticePolicyRepository;
import org.folio.circulation.infrastructure.storage.notices.ScheduledNoticesRepository;
import org.folio.circulation.resources.context.RenewalContext;
import org.folio.circulation.services.LostItemFeeRefundContext;
import org.folio.circulation.support.Clients;
import org.folio.circulation.support.results.Result;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FeeFineScheduledNoticeService {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static FeeFineScheduledNoticeService using(Clients clients) {
    return new FeeFineScheduledNoticeService(
      ScheduledNoticesRepository.using(clients),
      new PatronNoticePolicyRepository(clients));
  }

  private final ScheduledNoticesRepository scheduledNoticesRepository;
  private final PatronNoticePolicyRepository noticePolicyRepository;

  public FeeFineScheduledNoticeService(
    ScheduledNoticesRepository scheduledNoticesRepository,
    PatronNoticePolicyRepository noticePolicyRepository) {
    this.scheduledNoticesRepository = scheduledNoticesRepository;
    this.noticePolicyRepository = noticePolicyRepository;
  }

  public Result<CheckInContext> scheduleOverdueFineNotices(CheckInContext context,
    FeeFineAction action) {

    scheduleNotices(context.getLoan(), action, OVERDUE_FINE_RETURNED);

    return succeeded(context);
  }

  public Result<RenewalContext> scheduleOverdueFineNotices(RenewalContext context) {
    scheduleNotices(context.getLoan(), context.getOverdueFeeFineAction(), OVERDUE_FINE_RENEWED);

    return succeeded(context);
  }

  public Result<LostItemFeeRefundContext> scheduleAgedToLostReturnedNotices(
    LostItemFeeRefundContext context, FeeFineAction feeFineAction) {

    scheduleNotices(context.getLoan(), feeFineAction, AGED_TO_LOST_RETURNED);

    return succeeded(context);
  }

  private CompletableFuture<Result<List<ScheduledNotice>>> scheduleNotices(
    Loan loan, FeeFineAction action, NoticeEventType eventType) {

    if (action == null) {
      return ofAsync(() -> null);
    }

    log.info("Scheduling a fee/fine notice: loanId={}, feeFineActionId={}, eventType=\"{}\"",
      loan.getId(), action.getId(), eventType.getRepresentation());

    return noticePolicyRepository.lookupPolicy(loan)
      .thenCompose(r -> r.after(policy ->
        scheduleNoticeBasedOnPolicy(loan, policy, action, eventType)));
  }

  public CompletableFuture<Result<Void>> scheduleNoticesForAgedLostFeeFineCharged(
    Loan loan, List<FeeFineAction> actions) {

    actions.forEach(feeFineAction -> scheduleNotices(loan, feeFineAction,
      AGED_TO_LOST_FINE_CHARGED));

    return ofAsync(() -> null);
  }

  private CompletableFuture<Result<List<ScheduledNotice>>> scheduleNoticeBasedOnPolicy(
    Loan loan, PatronNoticePolicy noticePolicy, FeeFineAction action, NoticeEventType eventType) {

    List<ScheduledNotice> scheduledNotices = noticePolicy.getNoticeConfigurations().stream()
      .filter(config -> config.getNoticeEventType() == eventType)
      .map(config -> createScheduledNotice(config, loan, action, eventType))
      .collect(Collectors.toList());

    return allOf(scheduledNotices, scheduledNoticesRepository::create);
  }

  private ScheduledNotice createScheduledNotice(NoticeConfiguration configuration,
    Loan loan, FeeFineAction action, NoticeEventType eventType) {

    return new ScheduledNoticeBuilder()
      .setId(UUID.randomUUID().toString())
      .setLoanId(loan.getId())
      .setFeeFineActionId(action.getId())
      .setRecipientUserId(loan.getUserId())
      .setNextRunTime(determineNextRunTime(configuration, action))
      .setNoticeConfig(createScheduledNoticeConfig(configuration))
      .setTriggeringEvent(TriggeringEvent.from(eventType))
      .build();
  }

  private DateTime determineNextRunTime(NoticeConfiguration configuration, FeeFineAction action) {
    DateTime actionDateTime = action.getDateAction();

    return configuration.getTiming() == NoticeTiming.AFTER
      ? actionDateTime.plus(configuration.getTimingPeriod().timePeriod())
      : actionDateTime;
  }

  private ScheduledNoticeConfig createScheduledNoticeConfig(NoticeConfiguration configuration) {
    return new ScheduledNoticeConfigBuilder()
      .setTemplateId(configuration.getTemplateId())
      .setTiming(configuration.getTiming())
      .setFormat(configuration.getNoticeFormat())
      .setRecurringPeriod(configuration.getRecurringPeriod())
      .setSendInRealTime(configuration.sendInRealTime())
      .build();
  }

}
