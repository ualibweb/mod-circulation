package api.support.fakes;

import static api.support.fakes.StorageSchema.validatorForLocationCampSchema;
import static api.support.fakes.StorageSchema.validatorForLocationInstSchema;
import static api.support.fakes.StorageSchema.validatorForLocationLibSchema;
import static api.support.fakes.StorageSchema.validatorForStorageLoanSchema;
import static api.support.fixtures.CalendarExamples.CASE_CALENDAR_IS_EMPTY_SERVICE_POINT_ID;
import static api.support.fixtures.CalendarExamples.getCalendarById;
import static api.support.fixtures.LibraryHoursExamples.CASE_CALENDAR_IS_UNAVAILABLE_SERVICE_POINT_ID;
import static api.support.fixtures.LibraryHoursExamples.CASE_CLOSED_LIBRARY_IN_THU_SERVICE_POINT_ID;
import static api.support.fixtures.LibraryHoursExamples.CASE_CLOSED_LIBRARY_SERVICE_POINT_ID;
import static api.support.fixtures.LibraryHoursExamples.getLibraryHoursById;
import static org.folio.circulation.support.JsonPropertyWriter.write;
import static org.folio.circulation.support.results.CommonFailures.failedDueToServerError;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.Collection;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.folio.circulation.domain.representations.ItemProperties;
import org.folio.circulation.support.Result;
import org.folio.circulation.support.ValidationErrorFailure;
import org.folio.circulation.support.http.client.BufferHelper;
import org.folio.circulation.support.http.client.OkapiHttpClient;
import org.folio.circulation.support.http.client.Response;
import org.folio.circulation.support.http.client.ResponseHandler;
import org.folio.circulation.support.http.server.ForwardResponse;
import org.folio.circulation.support.http.server.ServerErrorResponse;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.inject.internal.util.Lists;

import api.support.APITestContext;
import api.support.http.InterfaceUrls;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;

public class FakeOkapi extends AbstractVerticle {
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  private static final int PORT_TO_USE = 9493;
  private static final String address =
    String.format("http://localhost:%s", PORT_TO_USE);

  private HttpServer server;
  private String circulationRules = "{ \"rulesAsText\": \"\" }";

  public static String getAddress() {
    return address;
  }

  @Override
  public void start(Future<Void> startFuture) throws IOException {
    log.debug("Starting fake loan storage module");

    Router router = Router.router(vertx);

    this.server = vertx.createHttpServer();

    forwardRequestsToApplyCirculationRulesBackToCirculationModule(router);

    new FakeStorageModuleBuilder()
      .withRecordName("material type")
      .withRootPath("/material-types")
      .withCollectionPropertyName("mtypes")
      .create().register(router);

    new FakeStorageModuleBuilder()
      .withRecordName("loan type")
      .withRootPath("/loan-types")
      .withCollectionPropertyName("loantypes")
      .create().register(router);

    new FakeStorageModuleBuilder()
      .withRecordName("instance type")
      .withRootPath("/instance-types")
      .withCollectionPropertyName("instanceTypes")
      .withRequiredProperties("name", "code", "source")
      .create().register(router);

    new FakeStorageModuleBuilder()
      .withRecordName("contributor name type")
      .withRootPath("/contributor-name-types")
      .withCollectionPropertyName("contributorNameTypes")
      .withRequiredProperties("name")
      .create().register(router);

    new FakeStorageModuleBuilder()
      .withRecordName("instance")
      .withRootPath("/instance-storage/instances")
      .withCollectionPropertyName("instances")
      .withRequiredProperties("source", "title", "instanceTypeId")
      .create().register(router);

    new FakeStorageModuleBuilder()
      .withRecordName("holding")
      .withRootPath("/holdings-storage/holdings")
      .withCollectionPropertyName("holdingsRecords")
      .withRequiredProperties("instanceId", "permanentLocationId")
      .create().register(router);

    new FakeStorageModuleBuilder()
      .withRecordName("item")
      .withRootPath("/item-storage/items")
      .withRequiredProperties("holdingsRecordId", "materialTypeId", "permanentLoanTypeId")
      .withRecordPreProcessor(Lists.newArrayList(this::setEffectiveLocationIdForItem,
        this::setItemStatusDateForItem))
      .create().register(router);

    new FakeStorageModuleBuilder()
      .withRecordName("loan")
      .withRootPath("/loan-storage/loans")
      .validateRecordsWith(validatorForStorageLoanSchema())
      .withChangeMetadata()
      .create().register(router);

    new FakeStorageModuleBuilder()
      .withRecordName("fixed due date schedules")
      .withRootPath("/fixed-due-date-schedule-storage/fixed-due-date-schedules")
      .withCollectionPropertyName("fixedDueDateSchedules")
      .withUniqueProperties("name")
      .create().register(router);

    new FakeStorageModuleBuilder()
      .withRecordName("loan policy")
      .withRootPath("/loan-policy-storage/loan-policies")
      .withCollectionPropertyName("loanPolicies")
      .withRequiredProperties("name", "loanable", "renewable")
      .create().register(router);

    new FakeStorageModuleBuilder()
      .withRecordName("accounts")
      .withRootPath("/accounts")
      .withCollectionPropertyName("accounts")
      .create().register(router);

    new FakeStorageModuleBuilder()
        .withRecordName("feefineactions")
        .withRootPath("/feefineactions")
        .withCollectionPropertyName("feefineactions")
        .create().register(router);

    new FakeStorageModuleBuilder()
      .withRecordName("request policy")
      .withRootPath("/request-policy-storage/request-policies")
      .withCollectionPropertyName("requestPolicies")
      .withRequiredProperties("name")
      .create().register(router);

    new FakeStorageModuleBuilder()
      .withRecordName("notice policy")
      .withRootPath("/patron-notice-policy-storage/patron-notice-policies")
      .withCollectionPropertyName("noticePolicies")
      .withRequiredProperties("name", "active")
      .create().register(router);

    new FakeStorageModuleBuilder()
      .withRecordName("overdue fine policy")
      .withRootPath("/overdue-fines-policies")
      .withCollectionPropertyName("overdueFinePolicies")
      .withRequiredProperties("name")
      .create().register(router);

    new FakeStorageModuleBuilder()
      .withRecordName("lost item fee policy")
      .withRootPath("/lost-item-fees-policies")
      .withCollectionPropertyName("lostItemFeePolicies")
      .withRequiredProperties("name")
      .create().register(router);

    new FakeStorageModuleBuilder()
      .withRecordName("user group")
      .withRootPath("/groups")
      .withCollectionPropertyName("usergroups")
      .withRequiredProperties("group", "desc")
      .withUniqueProperties("group")
      .withChangeMetadata()
      .disallowCollectionDelete()
      .create().register(router);

    new FakeStorageModuleBuilder()
      .withRecordName("Address type")
      .withRootPath("/addresstypes")
      .withCollectionPropertyName("addressTypes")
      .withRequiredProperties("addressType")
      .disallowCollectionDelete()
      .create().register(router);

    new FakeStorageModuleBuilder()
      .withRecordName("user")
      .withRootPath("/users")
      .withRequiredProperties("id", "username")
      .withUniqueProperties("username")
      .disallowCollectionDelete()
      .create().register(router);

    new FakeStorageModuleBuilder()
      .withRecordName("proxyFor")
      .withCollectionPropertyName("proxiesFor")
      .withRootPath("/proxiesfor")
      .withRequiredProperties("id")
      .withUniqueProperties("id")
      .disallowCollectionDelete()
      .create().register(router);

    new FakeStorageModuleBuilder()
      .withRecordName("request")
      .withRootPath("/request-storage/requests")
      .withRequiredProperties("itemId", "requesterId", "requestType",
        "requestDate", "fulfilmentPreference")
      .withDisallowedProperties("pickupServicePoint", "loan", "deliveryAddress")
      .withRecordConstraint(this::requestHasSamePosition)
      .withChangeMetadata()
      .withBatchUpdate("/request-storage-batch/requests")
      .withBatchUpdatePreProcessor(this::resetPositionsBeforeBatchUpdate)
      .create().register(router);

    registerCirculationRulesStorage(router);
    registerCalendar(router);
    registerLibraryHours(router);
    registerFakeStorageLoansAnonymize(router);
    registerCurrentlyLoggedInUser(router);

    new FakeStorageModuleBuilder()
      .withRecordName("institution")
      .withRootPath("/location-units/institutions")
      .withCollectionPropertyName("locinsts")
      .validateRecordsWith(validatorForLocationInstSchema())
      .create().register(router);

    new FakeStorageModuleBuilder()
      .withRecordName("campus")
      .withRootPath("/location-units/campuses")
      .withCollectionPropertyName("loccamps")
      .validateRecordsWith(validatorForLocationCampSchema())
      .create().register(router);

    new FakeStorageModuleBuilder()
      .withRecordName("library")
      .withRootPath("/location-units/libraries")
      .withCollectionPropertyName("loclibs")
      .validateRecordsWith(validatorForLocationLibSchema())
      .create().register(router);

    new FakeStorageModuleBuilder()
      .withRecordName("locations")
      .withRootPath("/locations")
      .withCollectionPropertyName("locations")
      .withRequiredProperties(
        "name",
        "code",
        "institutionId",
        "campusId",
        "libraryId",
        "primaryServicePoint")
      .create()
      .register(router);

    new FakeStorageModuleBuilder()
      .withRecordName("cancellationReason")
      .withCollectionPropertyName("cancellationReasons")
      .withRootPath("/cancellation-reason-storage/cancellation-reasons")
      .withRequiredProperties("name", "description")
      .withChangeMetadata()
      .create()
      .register(router);

    new FakeStorageModuleBuilder()
      .withRecordName("service point")
      .withCollectionPropertyName("servicepoints")
      .withRootPath("/service-points")
      .withRequiredProperties("name", "code", "discoveryDisplayName")
      .withUniqueProperties("name")
      .withChangeMetadata()
      .disallowCollectionDelete()
      .create()
      .register(router);

    new FakeStorageModuleBuilder()
      .withRecordName("patron notice")
      .withCollectionPropertyName("patronnotices")
      .withRootPath("/patron-notice")
      .create()
      .register(router);

    new FakeStorageModuleBuilder()
      .withRecordName("configuration")
      .withCollectionPropertyName("configs")
      .withRootPath("/configurations/entries")
      .withChangeMetadata()
      .create()
      .register(router);

    new FakeStorageModuleBuilder()
      .withCollectionPropertyName("expiredSessions")
      .withRootPath("/patron-action-session-storage/expired-session-patron-ids")
      .withQueryParameters("action_type", "session_inactivity_time")
      .create()
      .register(router);

    new FakeStorageModuleBuilder()
      .withRecordName("scheduled notice")
      .withCollectionPropertyName("scheduledNotices")
      .withRootPath("/scheduled-notice-storage/scheduled-notices")
      .allowDeleteByQuery()
      .create()
      .register(router);


    new FakeStorageModuleBuilder()
      .withRecordName("patron action session")
      .withCollectionPropertyName("patronActionSessions")
      .withRootPath("/patron-action-session-storage/patron-action-sessions")
      .create()
      .register(router);

    server.requestHandler(router::accept)
      .listen(PORT_TO_USE, result -> {
        if (result.succeeded()) {
          log.info("Listening on {}", server.actualPort());
          startFuture.complete();
        } else {
          startFuture.fail(result.cause());
        }
      });
  }

  private Result<Object> requestHasSamePosition(
    Collection<JsonObject> existingRequests,
    JsonObject newOrUpdatedRequest) {

    try {
      return existingRequests.stream()
        .filter(request -> !Objects.equals(request.getString("id"),
          newOrUpdatedRequest.getString("id")))
        .filter(request -> Objects.equals(request.getString("itemId"),
          newOrUpdatedRequest.getString("itemId")))
        .filter(request -> newOrUpdatedRequest.getInteger("position") != null &&
          Objects.equals(request.getInteger("position"),
            newOrUpdatedRequest.getInteger("position")))
        .findAny()
        .map(r -> (Result<Object>) ValidationErrorFailure.failedValidation(
          "Cannot have more than one request with the same position in the queue",
          "itemId", r.getString("itemId")))
        .orElse(Result.succeeded(null));
    } catch (Exception e) {
      return failedDueToServerError(e);
    }
  }

  private void forwardRequestsToApplyCirculationRulesBackToCirculationModule(Router router) {
    //During loan creation, a request to /circulation/rules/loan-policy is made,
    //which is effectively to itself, so needs to be routed back
    router.get("/circulation/rules/loan-policy").handler(context -> {
      OkapiHttpClient client = APITestContext.createClient(throwable ->
        ServerErrorResponse.internalError(context.response(),
          String.format("Exception when forward circulation rules apply request: %s",
            throwable.getMessage())));

      client.get(String.format("http://localhost:%s/circulation/rules/loan-policy?%s"
        , APITestContext.circulationModulePort(), context.request().query()),
        httpClientResponse ->
          httpClientResponse.bodyHandler(buffer ->
            ForwardResponse.forward(context.response(), httpClientResponse,
              BufferHelper.stringFromBuffer(buffer))));
    });

    router.get("/circulation/rules/notice-policy").handler(context -> {
      OkapiHttpClient client = APITestContext.createClient(throwable ->
        ServerErrorResponse.internalError(context.response(),
          String.format("Exception when forward circulation rules apply request: %s",
            throwable.getMessage())));

      client.get(String.format("http://localhost:%s/circulation/rules/notice-policy?%s"
        , APITestContext.circulationModulePort(), context.request().query()),
        httpClientResponse ->
          httpClientResponse.bodyHandler(buffer ->
            ForwardResponse.forward(context.response(), httpClientResponse,
              BufferHelper.stringFromBuffer(buffer))));
    });

    router.get("/circulation/rules/request-policy").handler(context -> {
      OkapiHttpClient client = APITestContext.createClient(throwable ->
        ServerErrorResponse.internalError(context.response(),
          String.format("Exception when forward circulation rules apply request: %s",
            throwable.getMessage())));

      client.get(String.format("http://localhost:%s/circulation/rules/request-policy?%s"
        , APITestContext.circulationModulePort(), context.request().query()),
        httpClientResponse ->
          httpClientResponse.bodyHandler(buffer ->
            ForwardResponse.forward(context.response(), httpClientResponse,
              BufferHelper.stringFromBuffer(buffer))));
    });
  }

  @Override
  public void stop(Future<Void> stopFuture) {
    log.debug("Stopping fake okapi");

    if (server != null) {
      server.close(result -> {
        if (result.succeeded()) {
          log.info("Stopped listening on {}", server.actualPort());
          stopFuture.complete();
        } else {
          stopFuture.fail(result.cause());
        }
      });
    }
  }

  private void registerCurrentlyLoggedInUser(Router router) {

    router.get("/bl-users/_self")
        .handler(routingContext -> {
          routingContext.request()
              .bodyHandler(body -> {
                JsonObject responseBody = new JsonObject();
                JsonObject userObj = new JsonObject();
                write(userObj, "id", UUID.randomUUID());
                write(responseBody, "user", userObj);
                routingContext.response()
                    .putHeader("Content-type", "application/json")
                    .setStatusCode(200)
                    .end(responseBody.encode());
              });
        });
  }

  private void registerFakeStorageLoansAnonymize(Router router) {

    router.post("/anonymize-storage-loans")
      .handler(routingContext -> {
        routingContext.request()
          .bodyHandler(body -> {
            JsonObject responseBody = new JsonObject();
            JsonArray providedLoanIds = body.toJsonObject()
              .getJsonArray("loanIds");
            providedLoanIds = Objects.isNull(providedLoanIds) ? new JsonArray() : providedLoanIds;
            responseBody.put("anonymizedLoans", providedLoanIds);
            responseBody.put("notAnonymizedLoans", new JsonArray());
            routingContext.response()
              .putHeader("Content-type", "application/json")
              .setStatusCode(200)
              .end(responseBody.encode());
          });
      });
  }

  private void registerCirculationRulesStorage(Router router) {
    router.put("/circulation-rules-storage").handler(routingContext -> {
      log.debug("/circulation-rules-storage PUT");
      routingContext.request().bodyHandler(body -> {
        circulationRules = body.toString();
        log.debug("/circulation-rules-storage PUT body={}", circulationRules);
        routingContext.response().setStatusCode(204).end();
      }).exceptionHandler(ex -> {
        log.error("Unhandled exception in body handler", ex);
        routingContext.response().setStatusCode(500).end(ExceptionUtils.getStackTrace(ex));
      });
    });
    router.get("/circulation-rules-storage").handler(routingContext -> {
      log.debug("/circulation-rules-storage GET returns {}", circulationRules);
      routingContext.response().setStatusCode(200).end(circulationRules);
    });
  }

  private void registerLibraryHours(Router router) {
    router.get("/calendar/periods/:id/period")
      .handler(routingContext -> {
        String servicePointId = routingContext.pathParam("id");
        switch (servicePointId) {
          case CASE_CALENDAR_IS_UNAVAILABLE_SERVICE_POINT_ID:
            routingContext.response()
              .putHeader("content-type", "application/json")
              .setStatusCode(404)
              .end();
            break;

          case CASE_CLOSED_LIBRARY_SERVICE_POINT_ID:
            routingContext.response()
              .setStatusCode(200)
              .putHeader("content-type", "application/json")
              .end(findFakeLibraryHoursById(servicePointId));
            break;

          case CASE_CLOSED_LIBRARY_IN_THU_SERVICE_POINT_ID:
            routingContext.response()
              .setStatusCode(200)
              .putHeader("content-type", "application/json")
              .end(findFakeLibraryHoursById(servicePointId));
            break;

          default:
            routingContext.response()
              .setStatusCode(200)
              .putHeader("content-type", "application/json")
              .end(findFakeLibraryHoursById(servicePointId));
        }
      });
  }

  private void registerCalendar(Router router) {
    router.get("/calendar/periods/:id/calculateopening")
      .handler(routingContext -> {
        String servicePointId = routingContext.pathParam("id");
        switch (servicePointId) {
          case CASE_CALENDAR_IS_UNAVAILABLE_SERVICE_POINT_ID:
            routingContext.response()
              .putHeader("content-type", "application/json")
              .setStatusCode(404)
              .end();
            break;

          case CASE_CLOSED_LIBRARY_SERVICE_POINT_ID:
            routingContext.response()
              .putHeader("content-type", "application/json")
              .setStatusCode(404)
              .end();
            break;

          case CASE_CALENDAR_IS_EMPTY_SERVICE_POINT_ID:
            routingContext.response()
              .putHeader("content-type", "application/json")
              .setStatusCode(200)
              .end();
            break;

          case CASE_CLOSED_LIBRARY_IN_THU_SERVICE_POINT_ID:
            routingContext.response()
              .putHeader("content-type", "application/json")
              .setStatusCode(404)
              .end();
            break;

          default:
            MultiMap queries = routingContext.queryParams();
            routingContext.response()
              .setStatusCode(200)
              .putHeader("content-type", "application/json")
              .end(findFakeCalendarById(servicePointId, queries));
        }
      });
  }

  private String findFakeLibraryHoursById(String servicePointId) {
    log.debug(String.format("GET: /calendar/periods/%s/period", servicePointId));
    return getLibraryHoursById(servicePointId).toString();
  }

  private String findFakeCalendarById(String servicePointId, MultiMap queries) {
    log.debug(String.format("GET: /calendar/periods/%s/calculateopening, queries=%s",
      servicePointId, queries));
    return getCalendarById(servicePointId, queries).toString();
  }

  private CompletableFuture<JsonObject> setEffectiveLocationIdForItem(JsonObject oldItem,
                                                                      JsonObject newItem) {
    String permanentLocationId = newItem.getString(ItemProperties.PERMANENT_LOCATION_ID);
    String temporaryLocationId = newItem.getString(ItemProperties.TEMPORARY_LOCATION_ID);

    if (ObjectUtils.anyNotNull(temporaryLocationId, permanentLocationId)) {
      newItem.put(
        ItemProperties.EFFECTIVE_LOCATION_ID,
        ObjectUtils.firstNonNull(temporaryLocationId, permanentLocationId)
      );

      return CompletableFuture.completedFuture(newItem);
    }

    CompletableFuture<Response> getCompleted = new CompletableFuture<>();
    final String holdingsRecordId = newItem.getString("holdingsRecordId");

    APITestContext.createClient(ex -> log.warn("Error: ", ex))
      .get(
        InterfaceUrls.holdingsStorageUrl("?query=id=" + holdingsRecordId),
        ResponseHandler.json(getCompleted)
      );

    return getCompleted.thenApply(response -> {
      JsonObject holding = response.getJson()
        .getJsonArray("holdingsRecords").getJsonObject(0);

      String permanentLocation = holding.getString(ItemProperties.PERMANENT_LOCATION_ID);
      String temporaryLocation = holding.getString(ItemProperties.TEMPORARY_LOCATION_ID);

      return newItem.put(ItemProperties.EFFECTIVE_LOCATION_ID,
        ObjectUtils.firstNonNull(temporaryLocation, permanentLocation)
      );
    });
  }

  private CompletableFuture<JsonObject> setItemStatusDateForItem(JsonObject oldItem,
                                                                 JsonObject newItem) {
    if (Objects.nonNull(oldItem)) {
      JsonObject oldItemStatus = oldItem.getJsonObject(ItemProperties.STATUS_PROPERTY);
      JsonObject newItemStatus = newItem.getJsonObject(ItemProperties.STATUS_PROPERTY);
      if (ObjectUtils.allNotNull(oldItemStatus, newItemStatus)) {
        if (!Objects.equals(oldItemStatus.getString("name"),
          newItemStatus.getString("name"))) {
          write(newItemStatus, "date", new DateTime());
        }
      }
    }
    return CompletableFuture.completedFuture(newItem);
  }

  private JsonObject resetPositionsBeforeBatchUpdate(JsonObject batchUpdateRequest) {
    JsonArray requests = batchUpdateRequest.getJsonArray("requests");

    JsonArray requestsCopy = requests.copy();
    requestsCopy
      .forEach(requestCopy -> ((JsonObject) requestCopy).remove("position"));

    batchUpdateRequest.put("requests", requestsCopy.addAll(requests));
    return batchUpdateRequest;
  }
}
