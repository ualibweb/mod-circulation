package org.folio.circulation.domain.notice;

import static java.lang.Math.max;
import static java.util.stream.Collectors.joining;
import static org.folio.circulation.support.json.JsonPropertyWriter.write;
import static org.folio.circulation.support.utils.DateFormatUtil.formatDateTime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;

import org.apache.commons.lang3.ObjectUtils;
import org.folio.circulation.domain.Account;
import org.folio.circulation.domain.CallNumberComponents;
import org.folio.circulation.domain.CheckInContext;
import org.folio.circulation.domain.FeeFineAction;
import org.folio.circulation.domain.Item;
import org.folio.circulation.domain.Loan;
import org.folio.circulation.domain.Location;
import org.folio.circulation.domain.Request;
import org.folio.circulation.domain.RequestType;
import org.folio.circulation.domain.ServicePoint;
import org.folio.circulation.domain.User;
import org.folio.circulation.domain.policy.LoanPolicy;
import org.folio.circulation.support.utils.ClockUtil;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

public class TemplateContextUtil {

  private static final String USER = "user";
  private static final String ITEM = "item";
  private static final String REQUEST = "request";
  private static final String REQUESTER = "requester";
  private static final String LOAN = "loan";
  private static final String LOANS = "loans";
  private static final String FEE_CHARGE = "feeCharge";
  private static final String FEE_ACTION = "feeAction";

  private static final String UNLIMITED = "unlimited";

  private TemplateContextUtil() {
  }

  public static JsonObject createLoanNoticeContextWithoutUser(Loan loan) {
    return new JsonObject()
      .put(ITEM, createItemContext(loan.getItem()))
      .put(LOAN, createLoanContext(loan));
  }

  public static JsonObject createMultiLoanNoticeContext(User user,
    Collection<JsonObject> loanContexts) {

    return new JsonObject()
      .put(USER, createUserContext(user))
      .put(LOANS, new JsonArray(new ArrayList<>(loanContexts)));
  }

  public static JsonObject createLoanNoticeContext(Loan loan) {
    return new JsonObject()
      .put(USER, createUserContext(loan.getUser()))
      .put(ITEM, createItemContext(loan.getItem()))
      .put(LOAN, createLoanContext(loan));
  }

  public static JsonObject createRequestNoticeContext(Request request) {
    JsonObject requestNoticeContext = new JsonObject()
      .put(USER, createUserContext(request.getRequester()))
      .put(REQUEST, createRequestContext(request));

    // item may be missing if it is a title level request
    if (request.getItem() != null && request.getItem().isFound()) {
      requestNoticeContext.put(ITEM, createItemContext(request.getItem()));
    }

    if (request.getRequestType() == RequestType.RECALL && request.getLoan() != null) {
      requestNoticeContext.put(LOAN, createLoanContext(request.getLoan()));
    }
    return requestNoticeContext;
  }

  public static JsonObject createCheckInContext(CheckInContext context) {
    Item item = context.getItem();
    Request firstRequest = context.getHighestPriorityFulfillableRequest();
    JsonObject staffSlipContext = createStaffSlipContext(item, firstRequest);
    JsonObject itemContext = staffSlipContext.getJsonObject(ITEM);

    if (ObjectUtils.allNotNull(item, itemContext)) {
      write(itemContext, "lastCheckedInDateTime", ClockUtil.getZonedDateTime());
      if (item.getInTransitDestinationServicePoint() != null) {
        itemContext.put("fromServicePoint", context.getCheckInServicePoint().getName());
        itemContext.put("toServicePoint", item.getInTransitDestinationServicePoint().getName());
      }
    }

    return staffSlipContext;
  }

  public static JsonObject createStaffSlipContext(Request request) {
    if (request == null) {
      return new JsonObject();
    }

    return createStaffSlipContext(request.getItem(), request);
  }

  public static JsonObject createStaffSlipContext(
    Item item, Request request) {

    JsonObject staffSlipContext = new JsonObject();

    if (item != null) {
      JsonObject itemContext = createItemContext(item);
      if (item.getLastCheckIn() != null) {
        write(itemContext, "lastCheckedInDateTime", item.getLastCheckIn().getDateTime());
      }
      staffSlipContext.put(ITEM, itemContext);
    }

    if (request != null) {
      staffSlipContext.put(REQUEST, createRequestContext(request));

      User requester = request.getRequester();
      if (requester != null) {
        staffSlipContext.put(REQUESTER, createUserContext(requester, request.getDeliveryAddressTypeId()));
      }
    }

    return staffSlipContext;
  }

  public static JsonObject createUserContext(User user, String deliveryAddressTypeId) {
    JsonObject address = user.getAddressByType(deliveryAddressTypeId);

    JsonObject userContext = createUserContext(user);
    if(address != null){
      userContext
        .put("addressLine1", address.getString("addressLine1", null))
        .put("addressLine2", address.getString("addressLine2", null))
        .put("city", address.getString("city", null))
        .put("region", address.getString("region", null))
        .put("postalCode", address.getString("postalCode", null))
        .put("countryId", address.getString("countryId", null));
    }

    return userContext;
  }

  public static JsonObject createUserContext(User user) {
    return new JsonObject()
    .put("firstName", user.getFirstName())
    .put("lastName", user.getLastName())
    .put("middleName", user.getMiddleName())
    .put("barcode", user.getBarcode());
  }

  private static JsonObject createItemContext(Item item) {
    String contributorNamesToken = item.getContributorNames()
      .collect(joining("; "));

    String yearCaptionsToken = String.join("; ", item.getYearCaption());
    String copyNumber = item.getCopyNumber() != null ? item.getCopyNumber() : "";

    JsonObject itemContext = new JsonObject()
      .put("title", item.getTitle())
      .put("barcode", item.getBarcode())
      .put("status", item.getStatus().getValue())
      .put("primaryContributor", item.getPrimaryContributorName())
      .put("allContributors", contributorNamesToken)
      .put("enumeration", item.getEnumeration())
      .put("volume", item.getVolume())
      .put("chronology", item.getChronology())
      .put("yearCaption", yearCaptionsToken)
      .put("materialType", item.getMaterialTypeName())
      .put("loanType", item.getLoanTypeName())
      .put("copy", copyNumber)
      .put("numberOfPieces", item.getNumberOfPieces())
      .put("descriptionOfPieces", item.getDescriptionOfPieces());

    Location location = item.getLocation();

    if (location != null) {
      itemContext
        .put("effectiveLocationSpecific", location.getName())
        .put("effectiveLocationLibrary", location.getLibraryName())
        .put("effectiveLocationCampus", location.getCampusName())
        .put("effectiveLocationInstitution", location.getInstitutionName());
    }

    CallNumberComponents callNumberComponents = item.getCallNumberComponents();
    if (callNumberComponents != null) {
      itemContext
        .put("callNumber", callNumberComponents.getCallNumber())
        .put("callNumberPrefix", callNumberComponents.getPrefix())
        .put("callNumberSuffix", callNumberComponents.getSuffix());
    }

    return itemContext;
  }

  private static JsonObject createRequestContext(Request request) {
    Optional<Request> optionalRequest = Optional.ofNullable(request);
    JsonObject requestContext = new JsonObject();

    optionalRequest
      .map(Request::getId)
      .ifPresent(value -> requestContext.put("requestID", value));
    optionalRequest
      .map(Request::getPickupServicePoint)
      .map(ServicePoint::getName)
      .ifPresent(value -> requestContext.put("servicePointPickup", value));
    optionalRequest
      .map(Request::getRequestExpirationDate)
      .ifPresent(value -> write(requestContext, "requestExpirationDate", value));
    optionalRequest
      .map(Request::getHoldShelfExpirationDate)
      .ifPresent(value -> write(requestContext, "holdShelfExpirationDate", value));
    optionalRequest
      .map(Request::getCancellationAdditionalInformation)
      .ifPresent(value -> requestContext.put("additionalInfo", value));
    optionalRequest
      .map(Request::getCancellationReasonPublicDescription)
      .map(Optional::of)
      .orElse(optionalRequest.map(Request::getCancellationReasonName))
      .ifPresent(value -> requestContext.put("reasonForCancellation", value));
    optionalRequest
      .map(Request::getAddressType)
      .ifPresent(value -> requestContext.put("deliveryAddressType", value.getName()));

    optionalRequest
      .map(Request::getPatronComments)
      .ifPresent(value -> write(requestContext, "patronComments", value));

    return requestContext;
  }

  private static JsonObject createLoanContext(Loan loan) {
    JsonObject loanContext = new JsonObject();

    write(loanContext, "initialBorrowDate", loan.getLoanDate());
    write(loanContext, "dueDate", loan.getDueDate());
    if (loan.getReturnDate() != null) {
      write(loanContext, "checkedInDate", loan.getReturnDate());
    }

    loanContext.put("numberOfRenewalsTaken", Integer.toString(loan.getRenewalCount()));
    LoanPolicy loanPolicy = loan.getLoanPolicy();
    if (loanPolicy != null) {
      if (loanPolicy.unlimitedRenewals()) {
        loanContext.put("numberOfRenewalsAllowed", UNLIMITED);
        loanContext.put("numberOfRenewalsRemaining", UNLIMITED);
      } else {
        int renewalLimit = loanPolicy.getRenewalLimit();
        int renewalsRemaining = max(renewalLimit - loan.getRenewalCount(), 0);
        loanContext.put("numberOfRenewalsAllowed", Integer.toString(renewalLimit));
        loanContext.put("numberOfRenewalsRemaining", Integer.toString(renewalsRemaining));
      }
    }

    return loanContext;
  }

  public static JsonObject createFeeFineNoticeContext(Account account, Loan loan) {
    return createLoanNoticeContext(loan)
      .put(FEE_CHARGE, createFeeChargeContext(account));
  }

  public static JsonObject createFeeFineNoticeContext(Account account, Loan loan,
    FeeFineAction feeFineAction) {

    return createFeeFineNoticeContext(account, loan)
      .put(FEE_ACTION, createFeeActionContext(feeFineAction));
  }

  private static JsonObject createFeeChargeContext(Account account) {
    JsonObject context = new JsonObject();

    write(context, "owner", account.getFeeFineOwner());
    write(context, "type", account.getFeeFineType());
    write(context, "paymentStatus", account.getPaymentStatus());
    write(context, "amount", account.getAmount().toScaledString());
    write(context, "remainingAmount", account.getRemaining().toScaledString());
    write(context, "chargeDate", account.getCreationDate());
    write(context, "chargeDateTime", account.getCreationDate());

    return context;
  }

  private static JsonObject createFeeActionContext(FeeFineAction feeFineAction) {
    final JsonObject context = new JsonObject();
    String actionDateString = formatDateTime(feeFineAction.getDateAction());

    write(context, "type", feeFineAction.getActionType());
    write(context, "actionDate", actionDateString);
    write(context, "actionDateTime", actionDateString);
    write(context, "amount", feeFineAction.getAmount().toDouble());
    write(context, "remainingAmount", feeFineAction.getBalance().toDouble());

    return context;
  }

}
