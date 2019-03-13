package org.folio.circulation.domain.policy;

import java.util.function.Function;

import org.folio.circulation.domain.notice.PatronNoticePolicy;
import org.folio.circulation.domain.notice.PatronNoticePolicyMapper;
import org.folio.circulation.support.Clients;
import org.folio.circulation.support.HttpResult;

import io.vertx.core.json.JsonObject;

public class PatronNoticePolicyRepository extends CirculationPolicyRepository<PatronNoticePolicy> {

  private final Function<JsonObject, HttpResult<PatronNoticePolicy>> patronNoticePolicyMapper;

  public PatronNoticePolicyRepository(Clients clients) {
    this(clients, new PatronNoticePolicyMapper());
  }

  private PatronNoticePolicyRepository(
    Clients clients,
    Function<JsonObject, HttpResult<PatronNoticePolicy>> patronNoticePolicyMapper) {
    super(clients.noticePolicyCirculationRules(), clients.patronNoticePolicesStorageClient());
    this.patronNoticePolicyMapper = patronNoticePolicyMapper;
  }

  @Override
  protected String getPolicyNotFoundErrorMessage(String policyId) {
    return String.format("Notice policy %s could not be found, please check circulation rules", policyId);
  }

  @Override
  protected HttpResult<PatronNoticePolicy> toPolicy(JsonObject representation) {
    return patronNoticePolicyMapper.apply(representation);
  }

  @Override
  protected String fetchPolicyId(JsonObject jsonObject) {
    return jsonObject.getString("noticePolicyId");
  }
}
