package org.folio.circulation.domain.notice;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.stream.Collectors.groupingBy;
import static org.folio.circulation.support.AsyncCoordinationUtil.allOf;
import static org.folio.circulation.support.results.Result.succeeded;
import static org.folio.circulation.support.results.ResultBinding.mapResult;
import static org.folio.circulation.support.http.CommonResponseInterpreters.mapToRecordInterpreter;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.builder.EqualsBuilder;
import org.apache.commons.lang3.builder.HashCodeBuilder;
import org.apache.commons.lang3.tuple.Pair;
import org.folio.circulation.domain.notice.schedule.ScheduledNoticeConfig;
import org.folio.circulation.infrastructure.storage.notices.PatronNoticePolicyRepository;
import org.folio.circulation.rules.AppliedRuleConditions;
import org.folio.circulation.services.EventPublisher;
import org.folio.circulation.support.Clients;
import org.folio.circulation.support.CollectionResourceClient;
import org.folio.circulation.support.results.Result;
import org.folio.circulation.support.http.client.ResponseInterpreter;

import io.vertx.core.json.JsonObject;
import org.joda.time.DateTime;

public class PatronNoticeService {
  public static PatronNoticeService using(Clients clients, EventPublisher eventPublisher) {
    return new PatronNoticeService(new PatronNoticePolicyRepository(clients), clients, eventPublisher);
  }

  private PatronNoticePolicyRepository noticePolicyRepository;
  private CollectionResourceClient patronNoticeClient;
  private EventPublisher eventPublisher;

  public PatronNoticeService(PatronNoticePolicyRepository noticePolicyRepository, Clients clients, EventPublisher eventPublisher) {
    this.noticePolicyRepository = noticePolicyRepository;
    this.patronNoticeClient = clients.patronNoticeClient();
    this.eventPublisher = eventPublisher;
  }

  public CompletableFuture<Result<Void>> acceptNoticeEvent(PatronNoticeEvent event) {
    return acceptMultipleNoticeEvent(Collections.singletonList(event),
      contexts -> contexts.stream().findFirst().orElse(new JsonObject()));
  }

  public CompletableFuture<Result<Void>> acceptScheduledNoticeEvent(
    ScheduledNoticeConfig noticeConfig, String recipientId, JsonObject context,
    NoticeLogContext noticeLogContext) {

    PatronNotice patronNotice = new PatronNotice();
    patronNotice.setRecipientId(recipientId);
    patronNotice.setTemplateId(noticeConfig.getTemplateId());
    patronNotice.setDeliveryChannel(noticeConfig.getFormat().getDeliveryChannel());
    patronNotice.setOutputFormat(noticeConfig.getFormat().getOutputFormat());
    patronNotice.setContext(context);

    return sendNotice(patronNotice)
      .thenCompose(r -> r.after(v -> publishAuditLogEvent(noticeLogContext)));
  }

  public CompletableFuture<Result<Void>> acceptMultipleNoticeEvent(
    Collection<PatronNoticeEvent> events,
    Function<Collection<JsonObject>, JsonObject> contextCombiner) {

    return allOf(events, this::loadNoticePolicyId)
      .thenApply(mapResult(this::groupEvents))
      .thenCompose(r -> r.after(eventGroups -> handleGroupedEvents(eventGroups, contextCombiner)));
  }

  private CompletableFuture<Result<Pair<PatronNoticeEvent, String>>> loadNoticePolicyId(PatronNoticeEvent event) {
    return noticePolicyRepository.lookupPolicyId(event.getItem(), event.getUser())
      .thenApply(mapResult(circulationRuleMatchEntity -> Pair.of(event, circulationRuleMatchEntity.getPolicyId())));
  }

  private Map<NoticeEventGroupDefinition, List<PatronNoticeEvent>> groupEvents(
    List<Pair<PatronNoticeEvent, String>> eventsWithNoticePolicyId) {

    return eventsWithNoticePolicyId.stream()
      .collect(groupingBy(NoticeEventGroupDefinition::from,
        Collectors.mapping(Pair::getLeft, Collectors.toList())));
  }

  private CompletableFuture<Result<Void>> handleGroupedEvents(
    Map<NoticeEventGroupDefinition, List<PatronNoticeEvent>> eventGroups,
    Function<Collection<JsonObject>, JsonObject> contextCombiner) {

    return allOf(eventGroups.entrySet(), e -> handleGroupedEvent(e, contextCombiner))
      .thenApply(mapResult(v -> null));
  }

  private CompletableFuture<Result<Void>> handleGroupedEvent(
    Map.Entry<NoticeEventGroupDefinition, List<PatronNoticeEvent>> groupedEvent,
    Function<Collection<JsonObject>, JsonObject> contextCombiner) {

    NoticeEventGroupDefinition eventGroupDefinition = groupedEvent.getKey();
    List<PatronNoticeEvent> events = groupedEvent.getValue();

    List<NoticeLogContext> logRecords = events.stream()
      .map(PatronNoticeEvent::getAuditLogRecord)
      .collect(Collectors.toList());
    NoticeLogContext combinedNoticeLogContext = combineAuditLogRecords(logRecords);
    combinedNoticeLogContext.setNoticePolicyId(groupedEvent.getKey().noticePolicyId);

    List<JsonObject> noticeContexts = events.stream()
      .map(PatronNoticeEvent::getNoticeContext)
      .collect(Collectors.toList());

    JsonObject combinedContext = contextCombiner.apply(noticeContexts);

    return noticePolicyRepository.lookupPolicy(
      eventGroupDefinition.noticePolicyId,
      new AppliedRuleConditions(false, false, false))
      .thenCompose(r -> r.after(policy -> applyNoticePolicy(policy, eventGroupDefinition,
        combinedContext, combinedNoticeLogContext)));
  }

  private CompletableFuture<Result<Void>> applyNoticePolicy(
    PatronNoticePolicy policy, NoticeEventGroupDefinition eventGroupDefinition,
    JsonObject noticeContext, NoticeLogContext noticeLogContext) {

    Optional<NoticeConfiguration> matchingNoticeConfiguration =
      policy.lookupNoticeConfiguration(eventGroupDefinition.eventType);

    if (!matchingNoticeConfiguration.isPresent()) {
      return completedFuture(succeeded(null));
    }

    matchingNoticeConfiguration.ifPresent(config -> {
      noticeLogContext.setTemplateId(config.getTemplateId());
      noticeLogContext.setTriggeringEvent(config.getNoticeEventType().getRepresentation());
    });

    return sendPatronNotice(matchingNoticeConfiguration.get(),
      eventGroupDefinition.recipientId, noticeContext)
      .thenCompose(r -> r.after(v -> publishAuditLogEvent(noticeLogContext)));
  }

  private CompletableFuture<Result<Void>> sendPatronNotice(
    NoticeConfiguration noticeConfiguration, String recipientId, JsonObject context) {

    PatronNotice patronNotice = new PatronNotice();
    patronNotice.setRecipientId(recipientId);
    patronNotice.setTemplateId(noticeConfiguration.getTemplateId());
    patronNotice.setDeliveryChannel(noticeConfiguration.getNoticeFormat().getDeliveryChannel());
    patronNotice.setOutputFormat(noticeConfiguration.getNoticeFormat().getOutputFormat());
    patronNotice.setContext(context);

    return sendNotice(patronNotice);
  }

  private CompletableFuture<Result<Void>> sendNotice(PatronNotice patronNotice) {
    JsonObject body = JsonObject.mapFrom(patronNotice);
    ResponseInterpreter<Void> responseInterpreter =
      mapToRecordInterpreter(null, 200, 201);

    return patronNoticeClient.post(body)
      .thenApply(responseInterpreter::flatMap);
  }

  private NoticeLogContext combineAuditLogRecords(List<NoticeLogContext> records) {
    NoticeLogContext groupedRecord = records.get(0);
    groupedRecord.setItems(records.stream()
      .map(NoticeLogContext::getItems)
      .flatMap(Collection::stream)
      .collect(Collectors.toList()));
    return groupedRecord;
  }

  private CompletableFuture<Result<Void>> publishAuditLogEvent(NoticeLogContext noticeLogContext) {
    return eventPublisher.publishSendNoticeEvent(noticeLogContext.withDate(DateTime.now()));
  }

  private static class NoticeEventGroupDefinition {

    private final String recipientId;
    private final String noticePolicyId;
    private final NoticeEventType eventType;

    private static NoticeEventGroupDefinition from(
      Pair<PatronNoticeEvent, String> noticeEventWithPolicyId) {

      PatronNoticeEvent event = noticeEventWithPolicyId.getLeft();
      String noticePolicyId = noticeEventWithPolicyId.getRight();

      return new NoticeEventGroupDefinition(
        event.getUser().getId(),
        noticePolicyId,
        event.getEventType());
    }

    public NoticeEventGroupDefinition(
      String recipientId, String noticePolicyId,
      NoticeEventType eventType) {

      this.recipientId = recipientId;
      this.noticePolicyId = noticePolicyId;
      this.eventType = eventType;
    }

    @Override
    public boolean equals(Object object) {
      if (this == object) return true;

      if (object == null || getClass() != object.getClass()) return false;

      NoticeEventGroupDefinition that = (NoticeEventGroupDefinition) object;

      return new EqualsBuilder()
        .append(recipientId, that.recipientId)
        .append(noticePolicyId, that.noticePolicyId)
        .append(eventType, that.eventType)
        .isEquals();
    }

    @Override
    public int hashCode() {
      return new HashCodeBuilder(17, 37)
        .append(recipientId)
        .append(noticePolicyId)
        .append(eventType)
        .toHashCode();
    }
  }
}
