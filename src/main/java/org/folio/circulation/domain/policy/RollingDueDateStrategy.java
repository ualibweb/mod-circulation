package org.folio.circulation.domain.policy;

import io.vertx.core.json.JsonObject;
import org.folio.circulation.support.HttpResult;
import org.joda.time.DateTime;

class RollingDueDateStrategy extends DueDateStrategy {
  RollingDueDateStrategy(String loanPolicyId) {
    super(loanPolicyId);
  }

  @Override
  HttpResult<DateTime> calculate(JsonObject loan, LoanPolicy loanPolicy) {
    final DateTime loanDate = DateTime.parse(loan.getString("loanDate"));
    final JsonObject loansPolicy = loanPolicy.getJsonObject("loansPolicy");
    final String profile = loansPolicy.getString("profileId");

    final JsonObject period = loansPolicy.getJsonObject("period");

    final String interval = period.getString("intervalId");
    final Integer duration = period.getInteger("duration");

    log.info("Applying loan policy {}, profile: {}, period: {} {}",
      loanPolicyId, profile, duration, interval);

    return calculateRollingDueDate(loanDate, interval, duration);
  }

  private HttpResult<DateTime> calculateRollingDueDate(
    DateTime loanDate,
    String interval,
    Integer duration) {

    if(interval.equals("Months") && duration != null) {
      return HttpResult.success(loanDate.plusMonths(duration));
    }
    else if(interval.equals("Weeks") && duration != null) {
      return HttpResult.success(loanDate.plusWeeks(duration));
    }
    else if(interval.equals("Days") && duration != null) {
      return HttpResult.success(loanDate.plusDays(duration));
    }
    else if(interval.equals("Hours") && duration != null) {
      return HttpResult.success(loanDate.plusHours(duration));
    }
    else if(interval.equals("Minutes") && duration != null) {
      return HttpResult.success(loanDate.plusMinutes(duration));
    }
    else {
      return fail(String.format("Unrecognised interval - %s", interval));
    }
  }
}
