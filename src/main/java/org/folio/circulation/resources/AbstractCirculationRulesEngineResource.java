package org.folio.circulation.resources;

import static org.folio.circulation.support.results.Result.failed;
import static org.folio.circulation.support.results.Result.succeeded;

import java.lang.invoke.MethodHandles;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.folio.circulation.domain.Location;
import org.folio.circulation.rules.CirculationRuleMatch;
import org.folio.circulation.rules.Drools;
import org.folio.circulation.support.Clients;
import org.folio.circulation.support.CollectionResourceClient;
import org.folio.circulation.support.FetchSingleRecord;
import org.folio.circulation.support.ServerErrorFailure;
import org.folio.circulation.support.http.server.ClientErrorResponse;
import org.folio.circulation.support.http.server.JsonHttpResponse;
import org.folio.circulation.support.http.server.WebContext;
import org.folio.circulation.support.results.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import lombok.val;

/**
 * The circulation rules engine calculates the loan policy based on
 * item type, loan type, patron type and location.
 */
public abstract class AbstractCirculationRulesEngineResource extends Resource {
  protected static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static final String ITEM_TYPE_ID_NAME = "item_type_id";
  public static final String PATRON_TYPE_ID_NAME = "patron_type_id";
  public static final String LOCATION_ID_NAME = "location_id";
  public static final String LOAN_TYPE_ID_NAME = "loan_type_id";

  private final String applyPath;
  private final String applyAllPath;

  /**
   * Create a circulation rules engine that listens at applyPath and applyAllPath.
   * @param applyPath  URL path for circulation rules triggering that returns the first match
   * @param applyAllPath  URL path for circulation rules triggering that returns all matches
   * @param client  the HttpClient to use for requests via Okapi
   */
  AbstractCirculationRulesEngineResource(String applyPath, String applyAllPath, HttpClient client) {
    super(client);
    this.applyPath = applyPath;
    this.applyAllPath = applyAllPath;
  }

  /**
   * Register the paths set in the constructor.
   * @param router  where to register
   */
  @Override
  public void register(Router router) {
    router.get(applyPath   ).handler(this::apply);
    router.get(applyAllPath).handler(this::applyAll);
  }

  private boolean invalidUuid(HttpServerRequest request, String paramName) {
    final String regex = "^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[1-5][a-fA-F0-9]{3}-[89abAB][a-fA-F0-9]{3}-[a-fA-F0-9]{12}$";
    String uuid = request.getParam(paramName);
    if (uuid == null) {
      ClientErrorResponse.badRequest(request.response(), "required query parameter missing: " + paramName);
      return true;
    }
    if (! uuid.matches(regex)) {
      ClientErrorResponse.badRequest(request.response(), "invalid uuid format of " + paramName +
          ", expecting " + regex + " but it is " + uuid);
      return true;
    }
    return false;
  }

  private void apply(RoutingContext routingContext) {
    HttpServerRequest request = routingContext.request();

    applyRules(routingContext,
      (location, drools) -> getPolicyIdAndRuleMatch(request.params(), drools, location),
      this::buildJsonResult);
  }

  private CompletableFuture<Result<JsonObject>> buildJsonResult(CirculationRuleMatch entity) {
    JsonObject appliedRuleConditions = new JsonObject()
      .put("materialTypeMatch", entity.getAppliedRuleConditions().isItemTypePresent())
      .put("loanTypeMatch", entity.getAppliedRuleConditions().isLoanTypePresent())
      .put("patronGroupMatch", entity.getAppliedRuleConditions().isPatronGroupPresent());

    return CompletableFuture.completedFuture(succeeded(new JsonObject()
      .put(getPolicyIdKey(), entity.getPolicyId())
      .put("appliedRuleConditions", appliedRuleConditions)
    ));
  }

  private void applyAll(RoutingContext routingContext) {
    HttpServerRequest request = routingContext.request();

    applyRules(routingContext,
      (location, drools) -> getPolicies(request.params(), drools, location),
      this::buildJsonResult);
  }

  private CompletableFuture<Result<JsonObject>> buildJsonResult(JsonArray matches) {
    return CompletableFuture.completedFuture(succeeded(new JsonObject().put("circulationRuleMatches",
      matches)));
  }

  private <T> void applyRules(RoutingContext routingContext,
    BiFunction<Location, Drools, T> interpretMatches,
    Function<T, CompletableFuture<Result<JsonObject>>> mapToJson) {

    val request = routingContext.request();

    if (invalidApplyParameters(request)) {
      return;
    }

    Clients clients = Clients.create(routingContext, client);

    final WebContext context = new WebContext(routingContext);
    final CollectionResourceClient locationsStorageClient = clients.locationsStorage();

    val droolsFuture = CirculationRulesProcessor.getInstance()
      .getDrools(context.getTenantId(), clients.circulationRulesStorage());

    CompletableFuture<Result<Location>> locationFuture = FetchSingleRecord.<Location>forRecord("location")
      .using(locationsStorageClient)
      .mapTo(Location::from)
      .whenNotFound(failed(new ServerErrorFailure("Can`t find location")))
      .fetch(request.params().get(LOCATION_ID_NAME));

    val circulationRuleMatchFuture = droolsFuture.thenCombine(locationFuture,
      (droolsResult, locationResult) -> locationResult.combine(droolsResult, interpretMatches));

    circulationRuleMatchFuture.thenCompose(r -> r.after(mapToJson))
      .thenApply(r -> r.map(JsonHttpResponse::ok))
      .thenAccept(context::writeResultToHttpResponse);
  }

  private boolean invalidApplyParameters(HttpServerRequest request) {
    return
        invalidUuid(request, ITEM_TYPE_ID_NAME) ||
        invalidUuid(request, LOAN_TYPE_ID_NAME) ||
        invalidUuid(request, PATRON_TYPE_ID_NAME) ||
        invalidUuid(request, LOCATION_ID_NAME);
  }

  public static void setCacheTime(long triggerAgeInMilliseconds, long maxAgeInMilliseconds) {
    CirculationRulesProcessor.setCacheTime(triggerAgeInMilliseconds, maxAgeInMilliseconds);
  }

  public static void dropCache() {
    CirculationRulesProcessor.dropCache();
  }

  static void clearCache(String tenantId) {
    CirculationRulesProcessor.clearCache(tenantId);
  }

  protected abstract CirculationRuleMatch getPolicyIdAndRuleMatch(
    MultiMap params, Drools drools, Location location);

  protected abstract String getPolicyIdKey();

  protected abstract JsonArray getPolicies(MultiMap params,
    Drools drools, Location location);
}
