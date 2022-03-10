package org.folio.circulation.infrastructure.storage.inventory;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.function.Function.identity;
import static org.folio.circulation.domain.ItemStatus.AVAILABLE;
import static org.folio.circulation.domain.MultipleRecords.CombinationMatchers.matchRecordsById;
import static org.folio.circulation.domain.representations.ItemProperties.HOLDINGS_RECORD_ID;
import static org.folio.circulation.domain.representations.ItemProperties.IN_TRANSIT_DESTINATION_SERVICE_POINT_ID;
import static org.folio.circulation.domain.representations.ItemProperties.LAST_CHECK_IN;
import static org.folio.circulation.domain.representations.ItemProperties.STATUS_PROPERTY;
import static org.folio.circulation.support.fetching.MultipleCqlIndexValuesCriteria.byIndex;
import static org.folio.circulation.support.fetching.RecordFetching.findWithMultipleCqlIndexValues;
import static org.folio.circulation.support.http.CommonResponseInterpreters.noContentRecordInterpreter;
import static org.folio.circulation.support.http.client.CqlQuery.exactMatch;
import static org.folio.circulation.support.http.client.PageLimit.one;
import static org.folio.circulation.support.json.JsonPropertyFetcher.getProperty;
import static org.folio.circulation.support.json.JsonPropertyWriter.remove;
import static org.folio.circulation.support.json.JsonPropertyWriter.write;
import static org.folio.circulation.support.results.AsynchronousResultBindings.combineAfter;
import static org.folio.circulation.support.results.Result.ofAsync;
import static org.folio.circulation.support.results.Result.succeeded;
import static org.folio.circulation.support.results.ResultBinding.mapResult;

import java.lang.invoke.MethodHandles;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.circulation.domain.Holdings;
import org.folio.circulation.domain.Instance;
import org.folio.circulation.domain.Item;
import org.folio.circulation.domain.ItemRelatedRecord;
import org.folio.circulation.domain.LoanType;
import org.folio.circulation.domain.Location;
import org.folio.circulation.domain.MaterialType;
import org.folio.circulation.domain.MultipleRecords;
import org.folio.circulation.domain.ServicePoint;
import org.folio.circulation.infrastructure.storage.IdentityMap;
import org.folio.circulation.infrastructure.storage.ServicePointRepository;
import org.folio.circulation.storage.mappers.ItemMapper;
import org.folio.circulation.storage.mappers.LoanTypeMapper;
import org.folio.circulation.support.Clients;
import org.folio.circulation.support.CollectionResourceClient;
import org.folio.circulation.support.ServerErrorFailure;
import org.folio.circulation.support.SingleRecordFetcher;
import org.folio.circulation.support.fetching.CqlIndexValuesFinder;
import org.folio.circulation.support.fetching.CqlQueryFinder;
import org.folio.circulation.support.http.client.CqlQuery;
import org.folio.circulation.support.results.Result;

import io.vertx.core.json.JsonObject;
import lombok.AllArgsConstructor;

@AllArgsConstructor
public class ItemRepository {
  private static final Logger log = LogManager.getLogger(MethodHandles.lookup().lookupClass());

  private final CollectionResourceClient itemsClient;
  private final CollectionResourceClient loanTypesClient;
  private final LocationRepository locationRepository;
  private final MaterialTypeRepository materialTypeRepository;
  private final ServicePointRepository servicePointRepository;
  private final InstanceRepository instanceRepository;
  private final HoldingsRepository holdingsRepository;
  private final IdentityMap identityMap = new IdentityMap(
    item -> getProperty(item, "id"));

  public ItemRepository(Clients clients) {
    this(clients.itemsStorage(), clients.loanTypesStorage(),
      LocationRepository.using(clients), new MaterialTypeRepository(clients),
      new ServicePointRepository(clients), new InstanceRepository(clients),
      new HoldingsRepository(clients.holdingsStorage()));
  }

  public CompletableFuture<Result<Item>> fetchFor(ItemRelatedRecord itemRelatedRecord) {
    if (itemRelatedRecord.getItemId() == null) {
      return completedFuture(succeeded(Item.from(null)));
    }

    return fetchById(itemRelatedRecord.getItemId());
  }

  public CompletableFuture<Result<Item>> updateItem(Item item) {
    if (item == null) {
      return ofAsync(() -> null);
    }

    if (identityMap.entryNotPresent(item.getItemId())) {
      return completedFuture(Result.failed(new ServerErrorFailure(
        "Cannot update item when original representation is not available in identity map")));
    }

    final var updatedItemRepresentation = identityMap.get(item.getItemId());

    write(updatedItemRepresentation, STATUS_PROPERTY,
      new JsonObject().put("name", item.getStatus().getValue()));

    remove(updatedItemRepresentation, IN_TRANSIT_DESTINATION_SERVICE_POINT_ID);
    write(updatedItemRepresentation, IN_TRANSIT_DESTINATION_SERVICE_POINT_ID,
      item.getInTransitDestinationServicePointId());

    final var lastCheckIn = item.getLastCheckIn();

    if (lastCheckIn == null) {
      remove(updatedItemRepresentation, LAST_CHECK_IN);
    }
    else {
      write(updatedItemRepresentation, LAST_CHECK_IN, lastCheckIn.toJson());
    }

    return itemsClient.put(item.getItemId(), updatedItemRepresentation)
      .thenApply(noContentRecordInterpreter(item)::flatMap)
      .thenCompose(x -> ofAsync(() -> item));
  }

  public CompletableFuture<Result<Item>> getFirstAvailableItemByInstanceId(String instanceId) {
    return holdingsRepository.fetchByInstanceId(instanceId)
      .thenCompose(r -> r.after(this::getAvailableItem));
  }

  private CompletableFuture<Result<Item>> getAvailableItem(
    MultipleRecords<Holdings> holdingsRecords) {

    if (holdingsRecords == null || holdingsRecords.isEmpty()) {
      return ofAsync(() -> Item.from(null));
    }

    return findByIndexNameAndQuery(holdingsRecords.toKeys(Holdings::getId),
      HOLDINGS_RECORD_ID, exactMatch("status.name", AVAILABLE.getValue()))
      .thenApply(mapResult(MultipleRecords::firstOrNull));
  }

  public CompletableFuture<Result<Item>> fetchByBarcode(String barcode) {
    return fetchItemByBarcode(barcode)
      .thenComposeAsync(this::fetchItemRelatedRecords);
  }

  public CompletableFuture<Result<Item>> fetchById(String itemId) {
    return fetchItem(itemId)
      .thenComposeAsync(this::fetchItemRelatedRecords);
  }

  private CompletableFuture<Result<MultipleRecords<Item>>> fetchLocations(
    Result<MultipleRecords<Item>> result) {

    return result.combineAfter(locationRepository::getAllItemLocations,
      (items, locations) -> items
        .combineRecords(locations, matchRecordsById(Item::getPermanentLocationId, Location::getId),
          Item::withPermanentLocation, null)
        .combineRecords(locations, matchRecordsById(Item::getLocationId, Location::getId),
          Item::withLocation, null));
  }

  private CompletableFuture<Result<MultipleRecords<Item>>> fetchMaterialTypes(
    Result<MultipleRecords<Item>> result) {

    return result.after(items ->
      materialTypeRepository.getMaterialTypes(items)
        .thenApply(mapResult(materialTypes -> items.combineRecords(materialTypes,
          matchRecordsById(Item::getMaterialTypeId, MaterialType::getId),
          Item::withMaterialType, MaterialType.unknown()))));
  }

  private CompletableFuture<Result<MultipleRecords<Item>>> fetchLoanTypes(
    Result<MultipleRecords<Item>> result) {

    final var mapper = new LoanTypeMapper();

    return result.after(items -> {
      final var loanTypeIdsToFetch = items.toKeys(Item::getLoanTypeId);

      return findWithMultipleCqlIndexValues(loanTypesClient, "loantypes", mapper::toDomain)
        .findByIds(loanTypeIdsToFetch)
        .thenApply(mapResult(loanTypes -> items.combineRecords(loanTypes,
          matchRecordsById(Item::getLoanTypeId, LoanType::getId),
          Item::withLoanType, LoanType.unknown())));
    });
  }

  private CompletableFuture<Result<MultipleRecords<Item>>> fetchInstances(
    Result<MultipleRecords<Item>> result) {

    return result.after(items -> {
      final var instanceIds = items.toKeys(Item::getInstanceId);

      return instanceRepository.fetchByIds(instanceIds)
        .thenApply(mapResult(instances -> items.combineRecords(instances,
          matchRecordsById(Item::getInstanceId, Instance::getId),
          Item::withInstance, Instance.unknown())));
    });
  }

  private CompletableFuture<Result<MultipleRecords<Item>>> fetchHoldingsRecords(
    Result<MultipleRecords<Item>> result) {

    return result.after(items -> {
      final var holdingsIds = items.toKeys(Item::getHoldingsRecordId);

      return holdingsRepository.fetchByIds(holdingsIds)
        .thenApply(mapResult(holdings -> items.combineRecords(holdings,
          matchRecordsById(Item::getHoldingsRecordId, Holdings::getId),
          Item::withHoldings, Holdings.unknown())));
    });
  }

  private CompletableFuture<Result<MultipleRecords<Item>>> fetchItems(Collection<String> itemIds) {
    final var finder = new CqlIndexValuesFinder<>(createItemFinder());
    final var mapper = new ItemMapper();

    return finder.findByIds(itemIds)
      .thenApply(mapResult(identityMap::add))
      .thenApply(mapResult(records -> records.mapRecords(mapper::toDomain)));
  }

  private CompletableFuture<Result<Item>> fetchItem(String itemId) {
    final var mapper = new ItemMapper();

    return SingleRecordFetcher.jsonOrNull(itemsClient, "item")
      .fetch(itemId)
      .thenApply(mapResult(identityMap::add))
      .thenApply(mapResult(mapper::toDomain));
  }

  private CompletableFuture<Result<Item>> fetchItemByBarcode(String barcode) {
    log.info("Fetching item with barcode: {}", barcode);

    final var finder = createItemFinder();
    final var mapper = new ItemMapper();

    return finder.findByQuery(exactMatch("barcode", barcode), one())
      .thenApply(records -> records.map(MultipleRecords::firstOrNull))
      .thenApply(mapResult(identityMap::add))
      .thenApply(mapResult(mapper::toDomain));
  }

  public <T extends ItemRelatedRecord> CompletableFuture<Result<MultipleRecords<T>>>
  fetchItemsFor(Result<MultipleRecords<T>> result, BiFunction<T, Item, T> includeItemMap) {

    return fetchItemsFor(result, includeItemMap, this::fetchFor);
  }

  public <T extends ItemRelatedRecord> CompletableFuture<Result<MultipleRecords<T>>>
  fetchItemsWithHoldings(Result<MultipleRecords<T>> result, BiFunction<T, Item, T> includeItemMap) {

    return fetchItemsFor(result, includeItemMap, this::fetchItemsWithHoldingsRecords);
  }

  public <T extends ItemRelatedRecord> CompletableFuture<Result<MultipleRecords<T>>>
  fetchItemsFor(Result<MultipleRecords<T>> result, BiFunction<T, Item, T> includeItemMap,
    Function<Collection<String>, CompletableFuture<Result<MultipleRecords<Item>>>> fetcher) {

    if (result.failed() || result.value().getRecords().isEmpty()) {
      return CompletableFuture.completedFuture(result);
    }

    return result.combineAfter(
      r -> fetcher.apply(r.toKeys(ItemRelatedRecord::getItemId)),
      (records, items) ->
        matchItemToRecord(records, items, includeItemMap));
  }

  public CompletableFuture<Result<Collection<Item>>> findBy(String indexName, Collection<String> ids) {
    final var finder = new CqlIndexValuesFinder<>(createItemFinder());
    final var mapper = new ItemMapper();

    return finder.find(byIndex(indexName, ids))
      .thenApply(mapResult(identityMap::add))
      .thenApply(mapResult(m -> m.mapRecords(mapper::toDomain)))
      .thenComposeAsync(this::fetchItemsRelatedRecords)
      .thenApply(mapResult(MultipleRecords::getRecords));
  }

  public CompletableFuture<Result<MultipleRecords<Holdings>>> findHoldingsByIds(
    Collection<String> holdingsRecordIds) {

    return holdingsRepository.fetchByIds(holdingsRecordIds);
  }

  public CompletableFuture<Result<MultipleRecords<Item>>> findByIndexNameAndQuery(
    Collection<String> ids, String indexName, Result<CqlQuery> query) {

    final var finder = new CqlIndexValuesFinder<>(createItemFinder());
    final var mapper = new ItemMapper();

    return finder.find(byIndex(indexName, ids).withQuery(query))
      .thenApply(mapResult(identityMap::add))
      .thenApply(mapResult(m -> m.mapRecords(mapper::toDomain)))
      .thenComposeAsync(this::fetchItemsRelatedRecords);
  }

  private CompletableFuture<Result<MultipleRecords<Item>>> fetchFor(
    Collection<String> itemIds) {

    return fetchItems(itemIds)
      .thenComposeAsync(this::fetchItemsRelatedRecords);
  }

  private CompletableFuture<Result<MultipleRecords<Item>>> fetchItemsWithHoldingsRecords(
    Collection<String> itemIds) {

    return fetchItems(itemIds)
      .thenComposeAsync(this::fetchHoldingsRecords);
  }

  private <T extends ItemRelatedRecord> MultipleRecords<T> matchItemToRecord(
    MultipleRecords<T> records, MultipleRecords<Item> items,
    BiFunction<T, Item, T> includeItemMap) {

    final var mapper = new ItemMapper();

    return records
      .mapRecords(r -> includeItemMap.apply(r,
        items.filter(item -> StringUtils.equals(item.getItemId(), r.getItemId()))
          .firstOrElse(mapper.toDomain(null))));
  }

  public CompletableFuture<Result<Item>> fetchItemRelatedRecords(Result<Item> itemResult) {
    return itemResult.combineAfter(this::fetchHoldingsRecord, Item::withHoldings)
      .thenComposeAsync(combineAfter(this::fetchInstance, Item::withInstance))
      .thenComposeAsync(combineAfter(locationRepository::getLocation, Item::withLocation))
      .thenComposeAsync(combineAfter(this::fetchPrimaryServicePoint, Item::withPrimaryServicePoint))
      .thenComposeAsync(combineAfter(materialTypeRepository::getFor, Item::withMaterialType))
      .thenComposeAsync(combineAfter(this::fetchLoanType, Item::withLoanType));
  }

  private CompletableFuture<Result<Holdings>> fetchHoldingsRecord(Item item) {
    if (item == null || item.isNotFound()) {
      log.info("Item was not found, aborting fetching holding or instance");
      return ofAsync(Holdings::unknown);
    }
    else {
      return holdingsRepository.fetchById(item.getHoldingsRecordId());
    }
  }

  private CompletableFuture<Result<Instance>> fetchInstance(Item item) {
    if (item == null || item.isNotFound() || item.getInstanceId() == null) {
      log.info("Holding was not found, aborting fetching instance");
      return ofAsync(Instance::unknown);
    } else {
      return instanceRepository.fetchById(item.getInstanceId());
    }
  }

  private CompletableFuture<Result<ServicePoint>> fetchPrimaryServicePoint(Item item) {
    if (item == null || item.getLocation() == null
      || item.getLocation().getPrimaryServicePointId() == null) {

      log.info("Location was not fund, aborting fetching primary service point");
      return ofAsync(() -> null);
    }

    return servicePointRepository.getServicePointById(item.getLocation().getPrimaryServicePointId());
  }

  private CompletableFuture<Result<LoanType>> fetchLoanType(Item item) {
    if (item.getLoanTypeId() == null) {
      return completedFuture(succeeded(LoanType.unknown()));
    }

    return SingleRecordFetcher.json(loanTypesClient, "loan types",
        response -> succeeded(null))
      .fetch(item.getLoanTypeId())
      .thenApply(mapResult(new LoanTypeMapper()::toDomain));
  }

  public CompletableFuture<Result<MultipleRecords<Item>>> fetchItemsRelatedRecords(
    Result<MultipleRecords<Item>> items) {

    return fetchHoldingsRecords(items)
      .thenComposeAsync(this::fetchInstances)
      .thenComposeAsync(this::fetchLocations)
      .thenComposeAsync(this::fetchMaterialTypes)
      .thenComposeAsync(this::fetchLoanTypes);
  }

  private CqlQueryFinder<JsonObject> createItemFinder() {
    return new CqlQueryFinder<>(itemsClient, "items", identity());
  }
}
