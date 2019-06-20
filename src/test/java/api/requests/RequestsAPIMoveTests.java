package api.requests;

import static java.util.Arrays.asList;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.junit.MatcherAssert.assertThat;

import java.net.MalformedURLException;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import org.folio.circulation.support.http.client.IndividualResource;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.LocalDate;
import org.junit.Test;
import org.junit.runner.RunWith;

import api.support.APITests;
import api.support.builders.RequestBuilder;
import junitparams.JUnitParamsRunner;

@RunWith(JUnitParamsRunner.class)
public class RequestsAPIMoveTests extends APITests {

  @Test
  public void canMoveARequest()
    throws InterruptedException,
    ExecutionException,
    TimeoutException,
    MalformedURLException {

    UUID pickupServicePointId = servicePointsFixture.cd1().getId();

    IndividualResource smallAngryPlanet = itemsFixture.basedUponSmallAngryPlanet();
    IndividualResource uponInterestingTimes = itemsFixture.basedUponInterestingTimes();
    
    IndividualResource james = usersFixture.james();
    IndividualResource jessica = usersFixture.jessica();
    IndividualResource steve = usersFixture.steve();
    IndividualResource charlotte = usersFixture.charlotte();
    IndividualResource rebecca = usersFixture.rebecca();

    // james checks out basedUponSmallAngryPlanet
    loansFixture.checkOutByBarcode(smallAngryPlanet, james);
    
    // charlotte checks out basedUponInterestingTimes
    loansFixture.checkOutByBarcode(uponInterestingTimes, charlotte);

    // make requests for smallAngryPlanet
    IndividualResource requestByJessica = requestsFixture.placeHoldShelfRequest(
      smallAngryPlanet, jessica, new DateTime(2017, 7, 22, 10, 22, 54, DateTimeZone.UTC));

    IndividualResource requestBySteve = requestsFixture.placeHoldShelfRequest(
      smallAngryPlanet, steve, new DateTime(2017, 10, 27, 11, 54, 37, DateTimeZone.UTC));

    IndividualResource requestByCharlotte = requestsFixture.placeHoldShelfRequest(
      smallAngryPlanet, charlotte, new DateTime(2018, 1, 10, 15, 34, 21, DateTimeZone.UTC));

    IndividualResource requestByRebecca = requestsFixture.placeHoldShelfRequest(
      smallAngryPlanet, rebecca, new DateTime(2018, 2, 4, 7, 4, 53, DateTimeZone.UTC));

    // make requests for uponInterestingTimes
    IndividualResource requestByJames = requestsFixture.placeHoldShelfRequest(
      uponInterestingTimes, james, new DateTime(2018, 7, 22, 10, 22, 54, DateTimeZone.UTC));

    // move steve requests from smallAngryPlanet to uponInterestingTimes
    IndividualResource moveRequest = requestsFixture.move(new RequestBuilder()
        .withId(requestBySteve.getId())
        .withDestinationItemId(uponInterestingTimes.getId())
        .open()
        .recall()
        .forItem(smallAngryPlanet)
        .by(steve)
        .withRequestDate(new DateTime(2018, 7, 22, 10, 22, 54, DateTimeZone.UTC))
        .fulfilToHoldShelf()
        .withRequestExpiration(new LocalDate(2017, 7, 30))
        .withHoldShelfExpiration(new LocalDate(2017, 8, 31))
        .withPickupServicePointId(pickupServicePointId)
        .withTags(new RequestBuilder.Tags(asList("move", "request", "smallAngryPlanet", "basedUponInterestingTimes"))));

    assertThat("Move request should not retain stored destination item id",
        moveRequest.getJson().containsKey("destinationItemId"), is(false));

    assertThat("Move request should have correct item id",
        moveRequest.getJson().getString("itemId"), is(uponInterestingTimes.getId().toString()));

    // check positioning on uponInterestingTimes
    requestByJames = requestsClient.get(requestByJames);
    assertThat(requestByJames.getJson().getInteger("position"), is(1));
    assertThat(requestByJames.getJson().getString("itemId"), is(uponInterestingTimes.getId().toString()));
    retainsStoredSummaries(requestByJames);

    requestBySteve = requestsClient.get(requestBySteve);
    assertThat(requestBySteve.getJson().getInteger("position"), is(2));
    assertThat(requestBySteve.getJson().getString("itemId"), is(uponInterestingTimes.getId().toString()));
    retainsStoredSummaries(requestBySteve);

    // check positioning on smallAngryPlanet
    requestByJessica = requestsClient.get(requestByJessica);
    assertThat(requestByJessica.getJson().getInteger("position"), is(1));
    assertThat(requestByJessica.getJson().getString("itemId"), is(smallAngryPlanet.getId().toString()));
    retainsStoredSummaries(requestByJessica);

    requestByCharlotte = requestsClient.get(requestByCharlotte);
    assertThat(requestByCharlotte.getJson().getInteger("position"), is(2));
    assertThat(requestByCharlotte.getJson().getString("itemId"), is(smallAngryPlanet.getId().toString()));
    retainsStoredSummaries(requestByCharlotte);

    requestByRebecca = requestsClient.get(requestByRebecca);
    assertThat(requestByRebecca.getJson().getInteger("position"), is(3));
    assertThat(requestByRebecca.getJson().getString("itemId"), is(smallAngryPlanet.getId().toString()));
    retainsStoredSummaries(requestByRebecca);

  }
  
  private void retainsStoredSummaries(IndividualResource request) {
    assertThat("Updated request in queue should retain stored item summary",
      request.getJson().containsKey("item"), is(true));

    assertThat("Updated request in queue should retain stored requester summary",
      request.getJson().containsKey("requester"), is(true));
  }

}
