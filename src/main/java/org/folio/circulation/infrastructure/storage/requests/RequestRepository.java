package org.folio.circulation.infrastructure.storage.requests;

import static java.util.Objects.isNull;
import static org.folio.circulation.support.http.ResponseMapping.forwardOnFailure;
import static org.folio.circulation.support.http.ResponseMapping.mapUsingJson;
import static org.folio.circulation.domain.RequestStatus.openStates;
import static org.folio.circulation.support.CqlSortBy.ascending;
import static org.folio.circulation.support.fetching.RecordFetching.findWithMultipleCqlIndexValues;
import static org.folio.circulation.support.http.client.CqlQuery.exactMatchAny;
import static org.folio.circulation.support.results.Result.failed;
import static org.folio.circulation.support.results.Result.of;
import static org.folio.circulation.support.results.Result.ofAsync;
import static org.folio.circulation.support.results.Result.succeeded;
import static org.folio.circulation.support.results.ResultBinding.flatMapResult;
import static org.folio.circulation.support.results.ResultBinding.mapResult;

import java.util.Collection;
import java.util.concurrent.CompletableFuture;

import org.folio.circulation.domain.MultipleRecords;
import org.folio.circulation.domain.Request;
import org.folio.circulation.domain.RequestAndRelatedRecords;
import org.folio.circulation.domain.ServicePoint;
import org.folio.circulation.domain.StoredRequestRepresentation;
import org.folio.circulation.domain.User;
import org.folio.circulation.infrastructure.storage.ServicePointRepository;
import org.folio.circulation.infrastructure.storage.inventory.InstanceRepository;
import org.folio.circulation.infrastructure.storage.inventory.ItemRepository;
import org.folio.circulation.infrastructure.storage.loans.LoanRepository;
import org.folio.circulation.infrastructure.storage.users.PatronGroupRepository;
import org.folio.circulation.infrastructure.storage.users.UserRepository;
import org.folio.circulation.storage.RequestBatch;
import org.folio.circulation.support.CollectionResourceClient;
import org.folio.circulation.support.FetchSingleRecord;
import org.folio.circulation.support.RecordNotFoundFailure;
import org.folio.circulation.support.SingleRecordFetcher;
import org.folio.circulation.support.http.client.CqlQuery;
import org.folio.circulation.support.http.client.PageLimit;
import org.folio.circulation.support.http.client.Response;
import org.folio.circulation.support.http.client.ResponseInterpreter;
import org.folio.circulation.support.results.Result;

import io.vertx.core.json.JsonObject;
import lombok.AllArgsConstructor;
import lombok.Getter;

public class RequestRepository {
  private final CollectionResourceClient requestsStorageClient;
  private final CollectionResourceClient requestsBatchStorageClient;
  private final CollectionResourceClient cancellationReasonStorageClient;
  private final ItemRepository itemRepository;
  private final UserRepository userRepository;
  private final LoanRepository loanRepository;
  private final ServicePointRepository servicePointRepository;
  private final PatronGroupRepository patronGroupRepository;
  private final InstanceRepository instanceRepository;

  /**
   * Public constructor to avoid creating repositories twice
   */
  public RequestRepository(org.folio.circulation.support.Clients clients,
    ItemRepository itemRepository, UserRepository userRepository, LoanRepository loanRepository,
    ServicePointRepository servicePointRepository, PatronGroupRepository patronGroupRepository) {

    this(new Clients(clients.requestsStorage(), clients.requestsBatchStorage(),
        clients.cancellationReasonStorage()), itemRepository, userRepository,
      loanRepository, servicePointRepository, patronGroupRepository, new InstanceRepository(clients));
  }

  private RequestRepository(Clients clients, ItemRepository itemRepository,
    UserRepository userRepository, LoanRepository loanRepository,
    ServicePointRepository servicePointRepository,
    PatronGroupRepository patronGroupRepository, InstanceRepository instanceRepository) {

    this.requestsStorageClient = clients.getRequestsStorageClient();
    this.requestsBatchStorageClient = clients.getRequestsBatchStorageClient();
    this.cancellationReasonStorageClient = clients.getCancellationReasonStorageClient();
    this.itemRepository = itemRepository;
    this.userRepository = userRepository;
    this.loanRepository = loanRepository;
    this.servicePointRepository = servicePointRepository;
    this.patronGroupRepository = patronGroupRepository;
    this.instanceRepository = instanceRepository;
  }

  public static RequestRepository using(
    org.folio.circulation.support.Clients clients,
    ItemRepository itemRepository, UserRepository userRepository,
    LoanRepository loanRepository) {

    return new RequestRepository(
      new Clients(clients.requestsStorage(), clients.requestsBatchStorage(),
        clients.cancellationReasonStorage()),
      itemRepository, userRepository, loanRepository,
      new ServicePointRepository(clients),
      new PatronGroupRepository(clients),
      new InstanceRepository(clients));
  }

  public CompletableFuture<Result<MultipleRecords<Request>>> findBy(String query) {
    return requestsStorageClient.getManyWithRawQueryStringParameters(query)
      .thenApply(flatMapResult(this::mapResponseToRequests))
      .thenCompose(r -> r.after(this::fetchAdditionalFields));
  }

  CompletableFuture<Result<MultipleRecords<Request>>> findBy(CqlQuery query, PageLimit pageLimit) {
    return findByWithoutItems(query, pageLimit)
      .thenCompose(r -> r.after(this::fetchAdditionalFields));
  }

  private CompletableFuture<Result<MultipleRecords<Request>>> fetchAdditionalFields(
    MultipleRecords<Request> requestRecords) {

    return ofAsync(() -> requestRecords)
      .thenComposeAsync(requests -> itemRepository.fetchItemsFor(requests, Request::withItem))
      .thenComposeAsync(result -> result.after(loanRepository::findOpenLoansFor))
      .thenComposeAsync(result -> result.after(servicePointRepository::findServicePointsForRequests))
      .thenComposeAsync(result -> result.after(userRepository::findUsersForRequests))
      .thenComposeAsync(result -> result.after(patronGroupRepository::findPatronGroupsForRequestsUsers))
      .thenComposeAsync(result -> result.after(instanceRepository::findInstancesForRequests));
  }

  CompletableFuture<Result<MultipleRecords<Request>>> findByWithoutItems(
    CqlQuery query, PageLimit pageLimit) {

    return requestsStorageClient.getMany(query, pageLimit)
      .thenApply(result -> result.next(this::mapResponseToRequests));
  }

  private Result<MultipleRecords<Request>> mapResponseToRequests(Response response) {
    return MultipleRecords.from(response, Request::from, "requests");
  }

  public CompletableFuture<Result<Boolean>> exists(
    RequestAndRelatedRecords requestAndRelatedRecords) {

    return exists(requestAndRelatedRecords.getRequest());
  }

  private CompletableFuture<Result<Boolean>> exists(Request request) {
    return exists(request.getId());
  }

  private CompletableFuture<Result<Boolean>> exists(String id) {
    return createSingleRequestFetcher(new ResponseInterpreter<Boolean>()
      .on(200, of(() -> true))
      .on(404, of(() -> false)))
      .fetch(id);
  }

  public CompletableFuture<Result<Request>> getById(String id) {
    return getByIdWithoutItem(id)
      .thenComposeAsync(result -> result.combineAfter(itemRepository::fetchFor,
        Request::withItem))
      .thenComposeAsync(result -> result.combineAfter(instanceRepository::fetch,
        Request::withInstance))
      .thenComposeAsync(this::fetchLoan);
  }

  public CompletableFuture<Result<Request>> getByIdWithoutItem(String id) {
    return fetchRequest(id)
      .thenComposeAsync(this::fetchRequester)
      .thenComposeAsync(this::fetchProxy)
      .thenComposeAsync(this::fetchPickupServicePoint)
      .thenComposeAsync(this::fetchPatronGroups);
  }

  private CompletableFuture<Result<Request>> fetchRequest(String id) {
    return createSingleRequestFetcher(new ResponseInterpreter<Request>()
      .flatMapOn(200, mapUsingJson(Request::from))
      .on(404, failed(new RecordNotFoundFailure("request", id))))
      .fetch(id);
  }

  public CompletableFuture<Result<MultipleRecords<Request>>> findOpenRequestsByItemIds(
    Collection<String> itemIds) {

    Result<CqlQuery> query = exactMatchAny("status", openStates())
      .map(q -> q.sortBy(ascending("position")));

    return findWithMultipleCqlIndexValues(requestsStorageClient, "requests", Request::from)
      .findByIdIndexAndQuery(itemIds, "itemId", query);
  }

  public CompletableFuture<Result<Request>> update(Request request) {
    final JsonObject representation
      = new StoredRequestRepresentation().storedRequest(request);

    final ResponseInterpreter<Request> interpreter = new ResponseInterpreter<Request>()
      .on(204, of(() -> request))
      .otherwise(forwardOnFailure());

    return requestsStorageClient.put(request.getId(), representation)
      .thenApply(interpreter::flatMap);
  }

  public CompletableFuture<Result<RequestAndRelatedRecords>> update(
    RequestAndRelatedRecords requestAndRelatedRecords) {

    return update(requestAndRelatedRecords.getRequest())
      .thenApply(r -> r.map(requestAndRelatedRecords::withRequest));
  }

  public CompletableFuture<Result<RequestAndRelatedRecords>> create(
    RequestAndRelatedRecords requestAndRelatedRecords) {

    final Request request = requestAndRelatedRecords.getRequest();

    JsonObject representation = new StoredRequestRepresentation()
      .storedRequest(request);

    final ResponseInterpreter<Request> interpreter = new ResponseInterpreter<Request>()
      .flatMapOn(201, mapUsingJson(request::withRequestRepresentation))
      .otherwise(forwardOnFailure());

    return requestsStorageClient.post(representation)
      .thenApply(interpreter::flatMap)
      .thenApply(mapResult(requestAndRelatedRecords::withRequest));
  }

  public CompletableFuture<Result<Request>> delete(Request request) {
    final ResponseInterpreter<Request> interpreter = new ResponseInterpreter<Request>()
      .on(204, of(() -> request))
      .otherwise(forwardOnFailure());

    return requestsStorageClient.delete(request.getId())
      .thenApply(flatMapResult(interpreter::apply));
  }

  public CompletableFuture<Result<Request>> loadCancellationReason(Request request) {
    if(isNull(request) || isNull(request.getCancellationReasonId())) {
      return ofAsync(() -> null);
    }

    return FetchSingleRecord.<Request>forRecord("cancellation reason")
      .using(cancellationReasonStorageClient)
      .mapTo(request::withCancellationReasonRepresentation)
      .whenNotFound(succeeded(request))
      .fetch(request.getCancellationReasonId());
  }

  CompletableFuture<Result<Collection<Request>>> batchUpdate(
    Collection<Request> requests) {

    if (requests == null || requests.isEmpty()) {
      return CompletableFuture.completedFuture(succeeded(requests));
    }

    ResponseInterpreter<Collection<Request>> interpreter =
      new ResponseInterpreter<Collection<Request>>()
        .on(201, of(() -> requests))
        .otherwise(forwardOnFailure());

    RequestBatch requestBatch = new RequestBatch(requests);
    return requestsBatchStorageClient.post(requestBatch.toJson())
      .thenApply(interpreter::flatMap);
  }

  private CompletableFuture<Result<Request>> fetchRequester(Result<Request> result) {
    return result.combineAfter(request ->
      getUser(request.getUserId()), Request::withRequester);
  }

  private CompletableFuture<Result<Request>> fetchProxy(Result<Request> result) {
    return result.combineAfter(request ->
      getUser(request.getProxyUserId()), Request::withProxy);
  }

  private CompletableFuture<Result<Request>> fetchLoan(Result<Request> result) {
    return result.combineAfter(loanRepository::findOpenLoanForRequest, Request::withLoan);
  }

  private CompletableFuture<Result<Request>> fetchPickupServicePoint(Result<Request> result) {
    return result.combineAfter(request -> getServicePoint(request.getPickupServicePointId()),
        Request::withPickupServicePoint);
  }

  private CompletableFuture<Result<Request>> fetchPatronGroups(Result<Request> result) {
    return patronGroupRepository.findPatronGroupsForSingleRequestUsers(result);
  }

  private CompletableFuture<Result<User>> getUser(String userId) {
    return userRepository.getUser(userId);
  }

  private CompletableFuture<Result<ServicePoint>> getServicePoint(String servicePointId) {
    return servicePointRepository.getServicePointById(servicePointId);
  }

  private <R> SingleRecordFetcher<R> createSingleRequestFetcher(
    ResponseInterpreter<R> interpreter) {

    return new SingleRecordFetcher<>(requestsStorageClient, "request", interpreter);
  }

  @AllArgsConstructor
  @Getter
  private static class Clients {
    private final CollectionResourceClient requestsStorageClient;
    private final CollectionResourceClient requestsBatchStorageClient;
    private final CollectionResourceClient cancellationReasonStorageClient;
  }
}
