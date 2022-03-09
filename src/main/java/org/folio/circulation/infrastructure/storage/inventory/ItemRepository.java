package org.folio.circulation.infrastructure.storage.inventory;

import static java.util.Objects.isNull;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.function.Function.identity;
import static org.folio.circulation.domain.ItemStatus.AVAILABLE;
import static org.folio.circulation.domain.representations.ItemProperties.HOLDINGS_RECORD_ID;
import static org.folio.circulation.domain.representations.ItemProperties.IN_TRANSIT_DESTINATION_SERVICE_POINT_ID;
import static org.folio.circulation.domain.representations.ItemProperties.LAST_CHECK_IN;
import static org.folio.circulation.domain.representations.ItemProperties.STATUS_PROPERTY;
import static org.folio.circulation.support.fetching.MultipleCqlIndexValuesCriteria.byIndex;
import static org.folio.circulation.support.fetching.RecordFetching.findWithMultipleCqlIndexValues;
import static org.folio.circulation.support.http.CommonResponseInterpreters.noContentRecordInterpreter;
import static org.folio.circulation.support.http.client.CqlQuery.exactMatch;
import static org.folio.circulation.support.http.client.PageLimit.one;
import static org.folio.circulation.support.json.JsonKeys.byId;
import static org.folio.circulation.support.json.JsonPropertyFetcher.getProperty;
import static org.folio.circulation.support.json.JsonPropertyWriter.remove;
import static org.folio.circulation.support.json.JsonPropertyWriter.write;
import static org.folio.circulation.support.results.AsynchronousResultBindings.combineAfter;
import static org.folio.circulation.support.results.Result.ofAsync;
import static org.folio.circulation.support.results.Result.succeeded;
import static org.folio.circulation.support.results.ResultBinding.flatMapResult;
import static org.folio.circulation.support.results.ResultBinding.mapResult;
import static org.folio.circulation.support.utils.CollectionUtil.uniqueSet;

import java.lang.invoke.MethodHandles;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.folio.circulation.domain.Holdings;
import org.folio.circulation.domain.Instance;
import org.folio.circulation.domain.Item;
import org.folio.circulation.domain.ItemRelatedRecord;
import org.folio.circulation.domain.LoanType;
import org.folio.circulation.domain.Location;
import org.folio.circulation.domain.MultipleRecords;
import org.folio.circulation.domain.ServicePoint;
import org.folio.circulation.infrastructure.storage.IdentityMap;
import org.folio.circulation.infrastructure.storage.ServicePointRepository;
import org.folio.circulation.storage.mappers.HoldingsMapper;
import org.folio.circulation.storage.mappers.ItemMapper;
import org.folio.circulation.storage.mappers.LoanTypeMapper;
import org.folio.circulation.storage.mappers.MaterialTypeMapper;
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
  public final CollectionResourceClient holdingsClient;
  private final CollectionResourceClient loanTypesClient;
  private final LocationRepository locationRepository;
  private final MaterialTypeRepository materialTypeRepository;
  private final ServicePointRepository servicePointRepository;
  private final InstanceRepository instanceRepository;
  private final HoldingsRepository holdingsRepository;
  private final IdentityMap identityMap = new IdentityMap(
    item -> getProperty(item, "id"));

  public ItemRepository(Clients clients) {
    this(clients.itemsStorage(), clients.holdingsStorage(),
      clients.loanTypesStorage(), LocationRepository.using(clients),
      new MaterialTypeRepository(clients), new ServicePointRepository(clients),
      new InstanceRepository(clients), new HoldingsRepository(clients.holdingsStorage()));
  }

  public CompletableFuture<Result<Item>> fetchFor(ItemRelatedRecord itemRelatedRecord) {
    if (itemRelatedRecord.getItemId() == null) {
      return completedFuture(succeeded(Item.from(null)));
    }

    return fetchById(itemRelatedRecord.getItemId());
  }

  private CompletableFuture<Result<ServicePoint>> fetchPrimaryServicePoint(Location location) {
    if(isNull(location) || isNull(location.getPrimaryServicePointId())) {
      return ofAsync(() -> null);
    }

    return servicePointRepository.getServicePointById(
      location.getPrimaryServicePointId());
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
      .thenApply(mapResult(ItemRepository::firstOrNull));
  }

  public CompletableFuture<Result<Item>> fetchByBarcode(String barcode) {
    return fetchItemByBarcode(barcode)
      .thenComposeAsync(this::fetchItemRelatedRecords);
  }

  public CompletableFuture<Result<Item>> fetchById(String itemId) {
    return fetchItem(itemId)
      .thenComposeAsync(this::fetchItemRelatedRecords);
  }

  private CompletableFuture<Result<Collection<Item>>> fetchLocations(
    Result<Collection<Item>> result) {

    return result.after(items -> locationRepository.getAllItemLocations(items)
      .thenApply(mapResult(locations -> map(items, populateItemLocations(locations)))));
  }

  private Function<Item, Item> populateItemLocations(Map<String, Location> locations) {
    return item -> {
      final Location permLocation = locations.get(item.getPermanentLocationId());
      final Location location = locations.get(item.getLocationId());

      return item.withLocation(location).withPermanentLocation(permLocation);
    };
  }

  private CompletableFuture<Result<Collection<Item>>> fetchMaterialTypes(
    Result<Collection<Item>> result) {

    final var mapper = new MaterialTypeMapper();

    return result.after(items ->
      materialTypeRepository.getMaterialTypes(items)
        .thenApply(mapResult(materialTypes -> items.stream()
            .map(item -> item.withMaterialType(mapper.toDomain(materialTypes
              .getOrDefault(item.getMaterialTypeId(), null))))
            .collect(Collectors.toList()))));
  }

  private CompletableFuture<Result<Collection<Item>>> fetchLoanTypes(Result<Collection<Item>> result) {
    return result.after(items -> {
      Map<Item, String> itemToLoanTypeIdMap = items.stream()
        .collect(Collectors.toMap(identity(), Item::getLoanTypeId));

      final var loanTypeIdsToFetch
        = uniqueSet(itemToLoanTypeIdMap.values(), identity());

      return findWithMultipleCqlIndexValues(loanTypesClient, "loantypes", identity())
        .findByIds(loanTypeIdsToFetch)
        .thenApply(mapResult(records -> records.toMap(byId())))
        .thenApply(flatMapResult(loanTypes -> matchLoanTypesToItems(itemToLoanTypeIdMap, loanTypes)));
    });
  }

  private Result<Collection<Item>> matchLoanTypesToItems(
    Map<Item, String> itemToLoanTypeId, Map<String, JsonObject> loanTypes) {

    final var mapper = new LoanTypeMapper();

    return succeeded(
      itemToLoanTypeId.entrySet().stream()
        .map(e -> e.getKey().withLoanType(
          mapper.toDomain(loanTypes.get(e.getValue()))))
        .collect(Collectors.toList()));
  }

  private CompletableFuture<Result<Collection<Item>>> fetchInstances(
    Result<Collection<Item>> result) {

    return result.after(items -> {
      final var instanceIds = uniqueSet(items, Item::getInstanceId);

      return instanceRepository.fetchByIds(instanceIds)
        .thenApply(mapResult(instances -> items.stream()
          .map(item -> item.withInstance(
              instances.filter(instance -> Objects.equals(instance.getId(),
                item.getInstanceId())).firstOrElse(Instance.unknown())))
          .collect(Collectors.toList())));
    });
  }

  private CompletableFuture<Result<Collection<Item>>> fetchHoldingsRecords(
    Result<Collection<Item>> result) {

    return result.after(items -> {
      final var holdingsIds = uniqueSet(items, Item::getHoldingsRecordId);

      final var mapper = new HoldingsMapper();

      return fetchHoldingsByIds(holdingsIds)
        .thenApply(mapResult(holdings -> items.stream()
          .map(item -> item.withHoldings(mapper.toDomain(
              findById(item.getHoldingsRecordId(), holdings.getRecords()).orElse(null))))
          .collect(Collectors.toList())));
    });
  }

  private static Optional<JsonObject> findById(
    String id,
    Collection<JsonObject> collection) {

    return collection.stream()
      .filter(item -> item.getString("id").equals(id))
      .findFirst();
  }

  private CompletableFuture<Result<Collection<Item>>> fetchItems(Collection<String> itemIds) {
    final var finder = new CqlIndexValuesFinder<>(createItemFinder());
    final var mapper = new ItemMapper();

    return finder.findByIds(itemIds)
      .thenApply(mapResult(identityMap::add))
      .thenApply(mapResult(records -> records.mapRecords(mapper::toDomain)))
      .thenApply(mapResult(MultipleRecords::getRecords));
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
    Function<Collection<String>, CompletableFuture<Result<Collection<Item>>>> fetcher) {

    if (result.failed() || result.value().getRecords().isEmpty()) {
      return CompletableFuture.completedFuture(result);
    }

    return result.combineAfter(
      r -> fetcher.apply(r.toKeys(ItemRelatedRecord::getItemId)),
      (records, items) -> new MultipleRecords<>(
        matchItemToRecord(records, items, includeItemMap),
        records.getTotalRecords()));
  }

  public CompletableFuture<Result<Collection<Item>>> findBy(String indexName, Collection<String> ids) {
    final var finder = new CqlIndexValuesFinder<>(createItemFinder());
    final var mapper = new ItemMapper();

    return finder.find(byIndex(indexName, ids))
      .thenApply(mapResult(identityMap::add))
      .thenApply(mapResult(m -> m.mapRecords(mapper::toDomain)))
      .thenApply(mapResult(MultipleRecords::getRecords))
      .thenComposeAsync(this::fetchItemsRelatedRecords);
  }

  public CompletableFuture<Result<MultipleRecords<Holdings>>> findHoldingsByIds(
    Collection<String> holdingsRecordIds) {

    return fetchHoldingsByIds(holdingsRecordIds)
      .thenApply(mapResult(multipleRecords ->
        multipleRecords.mapRecords(new HoldingsMapper()::toDomain)));
  }

  private CompletableFuture<Result<MultipleRecords<JsonObject>>> fetchHoldingsByIds(
    Collection<String> holdingsRecordIds) {

    return findWithMultipleCqlIndexValues(holdingsClient, "holdingsRecords", identity())
      .findByIds(holdingsRecordIds);
  }

  public CompletableFuture<Result<Collection<Item>>> findByIndexNameAndQuery(
    Collection<String> ids, String indexName, Result<CqlQuery> query) {

    final var finder = new CqlIndexValuesFinder<>(createItemFinder());
    final var mapper = new ItemMapper();

    return finder.find(byIndex(indexName, ids).withQuery(query))
      .thenApply(mapResult(identityMap::add))
      .thenApply(mapResult(m -> m.mapRecords(mapper::toDomain)))
      .thenApply(mapResult(MultipleRecords::getRecords))
      .thenComposeAsync(this::fetchItemsRelatedRecords);
  }

  private CompletableFuture<Result<Collection<Item>>> fetchFor(
    Collection<String> itemIds) {

    return fetchItems(itemIds)
      .thenComposeAsync(this::fetchItemsRelatedRecords);
  }

  private CompletableFuture<Result<Collection<Item>>> fetchItemsWithHoldingsRecords(
    Collection<String> itemIds) {

    return fetchItems(itemIds)
      .thenComposeAsync(this::fetchHoldingsRecords);
  }

  private <T extends ItemRelatedRecord> Collection<T> matchItemToRecord(
    MultipleRecords<T> records,
    Collection<Item> items,
    BiFunction<T, Item, T> includeItemMap) {

    final var mapper = new ItemMapper();

    return records.getRecords().stream()
      .map(r -> includeItemMap.apply(r,
        items.stream()
          .filter(item -> StringUtils.equals(item.getItemId(), r.getItemId()))
          .findFirst().orElse(mapper.toDomain(null))))
      .collect(Collectors.toList());
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
    return fetchPrimaryServicePoint(item.getLocation());
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

  public CompletableFuture<Result<Collection<Item>>> fetchItemsRelatedRecords(
    Result<Collection<Item>> items) {

    return fetchHoldingsRecords(items)
      .thenComposeAsync(this::fetchInstances)
      .thenComposeAsync(this::fetchLocations)
      .thenComposeAsync(this::fetchMaterialTypes)
      .thenComposeAsync(this::fetchLoanTypes);
  }

  private CqlQueryFinder<JsonObject> createItemFinder() {
    return new CqlQueryFinder<>(itemsClient, "items", identity());
  }

  private static <T, R> Collection<R> map(Collection<T> collection, Function<T, R> mapper) {
    return collection.stream()
      .map(mapper)
      .collect(Collectors.toList());
  }

  private static <T> T firstOrNull(Collection<T> collection) {
    if (collection == null) {
      return null;
    }

    return collection.stream()
      .findFirst()
      .orElse(null);
  }
}
