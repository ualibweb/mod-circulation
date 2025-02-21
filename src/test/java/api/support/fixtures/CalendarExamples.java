package api.support.fixtures;

import static api.support.OpeningDayPeriod.createDayPeriod;
import static io.vertx.core.MultiMap.caseInsensitiveMultiMap;
import static java.time.ZoneOffset.UTC;
import static org.folio.circulation.domain.OpeningDay.createOpeningDay;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.folio.circulation.domain.OpeningHour;

import api.support.OpeningDayPeriod;
import api.support.builders.CalendarBuilder;
import api.support.builders.OpeningDayPeriodBuilder;
import io.vertx.core.MultiMap;

public class CalendarExamples {
  public static final String ROLLOVER_SCENARIO_SERVICE_POINT_ID = "22211111-2f09-4bc9-8924-3734882d44a3";
  public static final String ROLLOVER_SCENARIO_NEXT_DAY_CLOSED_SERVICE_POINT_ID = "33311111-2f09-4bc9-8924-3734882d44a3";

  public static final String CASE_FRI_SAT_MON_DAY_ALL_SERVICE_POINT_ID = "11111111-2f09-4bc9-8924-3734882d44a3";
  public static final String CASE_FRI_SAT_MON_SERVICE_POINT_ID = "22222222-2f09-4bc9-8924-3734882d44a3";
  public static final String CASE_WED_THU_FRI_DAY_ALL_SERVICE_POINT_ID = "33333333-2f09-4bc9-8924-3734882d44a3";
  public static final String CASE_WED_THU_FRI_SERVICE_POINT_ID = "44444444-2f09-4bc9-8924-3734882d44a3";

  public static final String CASE_PREV_OPEN_AND_CURRENT_NEXT_CLOSED = "85346678-2f09-4bc9-8924-3734882d44a3";
  public static final String CASE_CALENDAR_IS_EMPTY_SERVICE_POINT_ID = "66655555-2f09-4bc9-8924-3734882d44a3";

  public static final String CASE_FIRST_DAY_OPEN_SECOND_CLOSED_THIRD_CLOSED = "6ab38b7a-c889-4839-a337-86aad0297d7c";
  public static final String CASE_FIRST_DAY_OPEN_SECOND_CLOSED_THIRD_OPEN = "6ab38b7a-c889-4839-a337-86aad0297d8d";
  public static final String CASE_MON_WED_FRI_OPEN_TUE_THU_CLOSED = "87291415-9f43-42ec-ba6b-d590f33509a0";

  static final String CASE_START_DATE_MONTHS_AGO_AND_END_DATE_THU = "12345698-2f09-4bc9-8924-3734882d44a3";

  static final String CASE_START_DATE_MONTHS_AGO_AND_END_DATE_WED = "77777777-2f09-4bc9-8924-3734882d44a3";
  static final String CASE_START_DATE_FRI_AND_END_DATE_NEXT_MONTHS = "88888888-2f09-4bc9-8924-3734882d44a3";

  public static final String CASE_CURRENT_IS_OPEN = "7a50ce1e-ce47-4841-a01f-fd771ff3da1b";
  public static final LocalDate CASE_CURRENT_IS_OPEN_PREV_DAY = LocalDate.of(2019, 2, 4);
  public static final LocalDate CASE_CURRENT_IS_OPEN_CURR_DAY = LocalDate.of(2019, 2, 5);
  public static final LocalDate CASE_CURRENT_IS_OPEN_NEXT_DAY = LocalDate.of(2019, 2, 6);
  public static final LocalDate CASE_CURRENT_IS_OPEN_IN_ONE_DAY = LocalDate.of(2019, 2, 7);

  public static final LocalDate MONDAY_DATE = LocalDate.of(2018, 12, 9);
  public static final LocalDate TUESDAY_DATE = LocalDate.of(2018, 12, 10);
  public static final LocalDate WEDNESDAY_DATE = LocalDate.of(2018, 12, 11);
  public static final LocalDate THURSDAY_DATE = LocalDate.of(2018, 12, 12);
  public static final LocalDate FRIDAY_DATE = LocalDate.of(2018, 12, 13);

  public static final LocalTime START_TIME_FIRST_PERIOD = LocalTime.of(8, 0);
  public static final LocalTime END_TIME_FIRST_PERIOD = LocalTime.of(12, 0);

  public static final LocalTime START_TIME_SECOND_PERIOD = LocalTime.of(14, 0);
  public static final LocalTime END_TIME_SECOND_PERIOD = LocalTime.of(19, 0);

  public static final LocalDate CASE_FRI_SAT_MON_SERVICE_POINT_PREV_DAY = LocalDate.of(2019, 2, 1);
  public static final LocalDate CASE_FRI_SAT_MON_SERVICE_POINT_CURR_DAY = LocalDate.of(2019, 2, 2);
  public static final LocalDate CASE_FRI_SAT_MON_SERVICE_POINT_NEXT_DAY = LocalDate.of(2019, 2, 4);

  public static final LocalDate CASE_FRI_SAT_MON_DAY_ALL_PREV_DATE = LocalDate.of(2018, 12, 14);
  public static final LocalDate CASE_FRI_SAT_MON_DAY_ALL_CURRENT_DATE = LocalDate.of(2018, 12, 15);
  public static final LocalDate CASE_FRI_SAT_MON_DAY_ALL_NEXT_DATE = LocalDate.of(2018, 12, 17);

  public static final LocalDate FIRST_DAY_OPEN = LocalDate.of(2020, 10, 29);
  public static final LocalDate SECOND_DAY_CLOSED = LocalDate.of(2020, 10, 30);
  public static final LocalDate THIRD_DAY_CLOSED = LocalDate.of(2020, 10, 31);
  public static final LocalDate THIRD_DAY_OPEN = LocalDate.of(2020, 10, 31);

  private static final String REQUESTED_DATE_PARAM = "requestedDate";

  private static final Map<String, OpeningDayPeriodBuilder> fakeOpeningPeriods = new HashMap<>();

  private CalendarExamples() { }

  static {
    fakeOpeningPeriods.put(CASE_PREV_OPEN_AND_CURRENT_NEXT_CLOSED, new OpeningDayPeriodBuilder(CASE_WED_THU_FRI_DAY_ALL_SERVICE_POINT_ID,
      // prev day
      createDayPeriod(
        createOpeningDay(Arrays.asList(new OpeningHour(START_TIME_FIRST_PERIOD, END_TIME_FIRST_PERIOD), new OpeningHour(START_TIME_SECOND_PERIOD, END_TIME_SECOND_PERIOD)),
          WEDNESDAY_DATE, false, true)
      ),
      // current day
      createDayPeriod(
        createOpeningDay(new ArrayList<>(), THURSDAY_DATE, false, false)
      ),
      // next day
      createDayPeriod(
        createOpeningDay(new ArrayList<>(), FRIDAY_DATE, false, false)
      )));
    fakeOpeningPeriods.put(CASE_WED_THU_FRI_SERVICE_POINT_ID, new OpeningDayPeriodBuilder(CASE_WED_THU_FRI_DAY_ALL_SERVICE_POINT_ID,
      // prev day
      createDayPeriod(
        createOpeningDay(Arrays.asList(new OpeningHour(START_TIME_FIRST_PERIOD, END_TIME_FIRST_PERIOD), new OpeningHour(START_TIME_SECOND_PERIOD, END_TIME_SECOND_PERIOD)),
          WEDNESDAY_DATE, false, true)
      ),
      // current day
      createDayPeriod(
        createOpeningDay(new ArrayList<>(),
          THURSDAY_DATE, false, false)
      ),
      // next day
      createDayPeriod(
        createOpeningDay(Arrays.asList(new OpeningHour(START_TIME_FIRST_PERIOD, END_TIME_FIRST_PERIOD), new OpeningHour(START_TIME_SECOND_PERIOD, END_TIME_SECOND_PERIOD)),
          FRIDAY_DATE, false, true)
      )));
    fakeOpeningPeriods.put(CASE_WED_THU_FRI_DAY_ALL_SERVICE_POINT_ID, new OpeningDayPeriodBuilder(CASE_WED_THU_FRI_DAY_ALL_SERVICE_POINT_ID,
      // prev day
      createDayPeriod(
        createOpeningDay(new ArrayList<>(), WEDNESDAY_DATE, true, true)
      ),
      // current day
      createDayPeriod(
        createOpeningDay(new ArrayList<>(), THURSDAY_DATE, false, false)
      ),
      // next day
      createDayPeriod(
        createOpeningDay(new ArrayList<>(), FRIDAY_DATE, true, true)
      )));
    fakeOpeningPeriods.put(CASE_FRI_SAT_MON_DAY_ALL_SERVICE_POINT_ID, new OpeningDayPeriodBuilder(CASE_FRI_SAT_MON_DAY_ALL_SERVICE_POINT_ID,
      // prev day
      createDayPeriod(
        createOpeningDay(new ArrayList<>(), CASE_FRI_SAT_MON_DAY_ALL_PREV_DATE, true, true)
      ),
      // current day
      createDayPeriod(
        createOpeningDay(new ArrayList<>(), CASE_FRI_SAT_MON_DAY_ALL_CURRENT_DATE, false, false)
      ),
      // next day
      createDayPeriod(
        createOpeningDay(new ArrayList<>(), CASE_FRI_SAT_MON_DAY_ALL_NEXT_DATE, true, true)
      )));
    fakeOpeningPeriods.put(CASE_FRI_SAT_MON_SERVICE_POINT_ID, new OpeningDayPeriodBuilder(CASE_FRI_SAT_MON_SERVICE_POINT_ID,
      // prev day
      createDayPeriod(
        createOpeningDay(Arrays.asList(new OpeningHour(START_TIME_FIRST_PERIOD, END_TIME_FIRST_PERIOD), new OpeningHour(START_TIME_SECOND_PERIOD, END_TIME_SECOND_PERIOD)),
          CASE_FRI_SAT_MON_SERVICE_POINT_PREV_DAY, false, true)
      ),
      // current day
      createDayPeriod(
        createOpeningDay(new ArrayList<>(), CASE_FRI_SAT_MON_SERVICE_POINT_CURR_DAY, false, false)
      ),
      // next day
      createDayPeriod(
        createOpeningDay(Arrays.asList(new OpeningHour(START_TIME_FIRST_PERIOD, END_TIME_FIRST_PERIOD), new OpeningHour(START_TIME_SECOND_PERIOD, END_TIME_SECOND_PERIOD)),
          CASE_FRI_SAT_MON_SERVICE_POINT_NEXT_DAY, false, true)
      )));
    fakeOpeningPeriods.put(CASE_CURRENT_IS_OPEN, new OpeningDayPeriodBuilder(CASE_CURRENT_IS_OPEN,
      // prev day
      createDayPeriod(
        createOpeningDay(Arrays.asList(new OpeningHour(START_TIME_FIRST_PERIOD, END_TIME_FIRST_PERIOD), new OpeningHour(START_TIME_SECOND_PERIOD, END_TIME_SECOND_PERIOD)),
          CASE_CURRENT_IS_OPEN_PREV_DAY, false, true)
      ),
      // current day
      createDayPeriod(
        createOpeningDay(Arrays.asList(new OpeningHour(START_TIME_FIRST_PERIOD, END_TIME_FIRST_PERIOD), new OpeningHour(START_TIME_SECOND_PERIOD, END_TIME_SECOND_PERIOD)),
          CASE_CURRENT_IS_OPEN_CURR_DAY, false, true)
      ),
      // next day
      createDayPeriod(
        createOpeningDay(Arrays.asList(new OpeningHour(START_TIME_FIRST_PERIOD, END_TIME_FIRST_PERIOD), new OpeningHour(START_TIME_SECOND_PERIOD, END_TIME_SECOND_PERIOD)),
          CASE_CURRENT_IS_OPEN_NEXT_DAY, false, true)
      )));
    fakeOpeningPeriods.put(ROLLOVER_SCENARIO_SERVICE_POINT_ID, new OpeningDayPeriodBuilder(ROLLOVER_SCENARIO_SERVICE_POINT_ID,
      // prev day
      createDayPeriod(
        createOpeningDay(Collections.singletonList(new OpeningHour(END_TIME_SECOND_PERIOD, LocalTime.MIDNIGHT.minusSeconds(1))),
          CASE_CURRENT_IS_OPEN_PREV_DAY, false, true)
      ),
      // current day
      createDayPeriod(
        createOpeningDay(Collections.singletonList(new OpeningHour(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT.plusHours(3))),
          CASE_CURRENT_IS_OPEN_CURR_DAY, false, true)
      ),
      // next day
      createDayPeriod(
        createOpeningDay(Collections.singletonList(new OpeningHour(START_TIME_FIRST_PERIOD, END_TIME_FIRST_PERIOD)),
          CASE_CURRENT_IS_OPEN_NEXT_DAY, false, true)
      )));
    fakeOpeningPeriods.put(ROLLOVER_SCENARIO_NEXT_DAY_CLOSED_SERVICE_POINT_ID, new OpeningDayPeriodBuilder(ROLLOVER_SCENARIO_NEXT_DAY_CLOSED_SERVICE_POINT_ID,
      // prev day
      createDayPeriod(
        createOpeningDay(Collections.singletonList(new OpeningHour(START_TIME_FIRST_PERIOD, END_TIME_SECOND_PERIOD)),
          CASE_CURRENT_IS_OPEN_PREV_DAY, false, true)
      ),
      // current day
      createDayPeriod(
        createOpeningDay(Collections.singletonList(new OpeningHour(END_TIME_SECOND_PERIOD, LocalTime.MIDNIGHT.minusSeconds(1))),
          CASE_CURRENT_IS_OPEN_CURR_DAY, false, true)
      ),
      // next day
      createDayPeriod(
        createOpeningDay(Collections.singletonList(new OpeningHour(LocalTime.MIDNIGHT, LocalTime.MIDNIGHT.plusHours(3))),
          CASE_CURRENT_IS_OPEN_IN_ONE_DAY, false, true)
      )));
    fakeOpeningPeriods.put(CASE_FIRST_DAY_OPEN_SECOND_CLOSED_THIRD_CLOSED, new OpeningDayPeriodBuilder(
      CASE_FIRST_DAY_OPEN_SECOND_CLOSED_THIRD_CLOSED,
      // prev day
      createDayPeriod(
        createOpeningDay(Arrays.asList(new OpeningHour(START_TIME_FIRST_PERIOD, END_TIME_FIRST_PERIOD), new OpeningHour(START_TIME_SECOND_PERIOD, END_TIME_SECOND_PERIOD)),
          FIRST_DAY_OPEN, false, true)
      ),
      // current day
      createDayPeriod(
        createOpeningDay(new ArrayList<>(), SECOND_DAY_CLOSED, false, false)
      ),
      // next day
      createDayPeriod(
        createOpeningDay(new ArrayList<>(), THIRD_DAY_CLOSED, false, false)
      )));
    fakeOpeningPeriods.put(CASE_FIRST_DAY_OPEN_SECOND_CLOSED_THIRD_OPEN,
      new OpeningDayPeriodBuilder(CASE_FIRST_DAY_OPEN_SECOND_CLOSED_THIRD_OPEN,
        // prev day
        createDayPeriod(
          createOpeningDay(Arrays.asList(
            new OpeningHour(START_TIME_FIRST_PERIOD, END_TIME_FIRST_PERIOD),
            new OpeningHour(START_TIME_SECOND_PERIOD, END_TIME_SECOND_PERIOD)), FIRST_DAY_OPEN,
            false, true)
        ),
        // current day
        createDayPeriod(
          createOpeningDay(new ArrayList<>(), SECOND_DAY_CLOSED, false, false)
        ),
        // next day
        createDayPeriod(
          createOpeningDay(Arrays.asList(
            new OpeningHour(START_TIME_FIRST_PERIOD, END_TIME_FIRST_PERIOD),
            new OpeningHour(START_TIME_SECOND_PERIOD, END_TIME_SECOND_PERIOD)), THIRD_DAY_OPEN,
            false, true)
        )));
  }

  private static OpeningDayPeriodBuilder buildAllDayOpenCalenderResponse(LocalDate requestedDate, String servicePointId) {
    return new OpeningDayPeriodBuilder(servicePointId,
      createDayPeriod(
        createOpeningDay(Collections.emptyList(), requestedDate.minusDays(1), true, true)
      ),
      createDayPeriod(
        createOpeningDay(Collections.emptyList(), requestedDate, true, true)
      ),
      createDayPeriod(
        createOpeningDay(Collections.emptyList(), requestedDate.plusDays(1), true, true)
      )
    );
  }

  public static CalendarBuilder getCalendarById(String serviceId) {
    return getCalendarById(serviceId,
      caseInsensitiveMultiMap().add(REQUESTED_DATE_PARAM, "2019-01-01"));
  }

  public static CalendarBuilder getCalendarById(String serviceId, MultiMap queries) {
    switch (serviceId) {
      case ROLLOVER_SCENARIO_SERVICE_POINT_ID:
        return new CalendarBuilder(fakeOpeningPeriods.get(serviceId));

      case ROLLOVER_SCENARIO_NEXT_DAY_CLOSED_SERVICE_POINT_ID:
        return new CalendarBuilder(fakeOpeningPeriods.get(serviceId));

      case CASE_PREV_OPEN_AND_CURRENT_NEXT_CLOSED:
        return new CalendarBuilder(fakeOpeningPeriods.get(serviceId));

      case CASE_FRI_SAT_MON_SERVICE_POINT_ID:
        return new CalendarBuilder(fakeOpeningPeriods.get(serviceId));

      case CASE_FRI_SAT_MON_DAY_ALL_SERVICE_POINT_ID:
        return new CalendarBuilder(fakeOpeningPeriods.get(serviceId));

      case CASE_WED_THU_FRI_DAY_ALL_SERVICE_POINT_ID:
        return new CalendarBuilder(fakeOpeningPeriods.get(serviceId));

      case CASE_WED_THU_FRI_SERVICE_POINT_ID:
        return new CalendarBuilder(fakeOpeningPeriods.get(serviceId));

      case CASE_CURRENT_IS_OPEN:
        return new CalendarBuilder(fakeOpeningPeriods.get(serviceId));

      case CASE_FIRST_DAY_OPEN_SECOND_CLOSED_THIRD_CLOSED:
        return new CalendarBuilder(fakeOpeningPeriods.get(serviceId));

      case CASE_FIRST_DAY_OPEN_SECOND_CLOSED_THIRD_OPEN:
        return new CalendarBuilder(fakeOpeningPeriods.get(serviceId));

      case CASE_START_DATE_MONTHS_AGO_AND_END_DATE_THU:
        ZonedDateTime endDate = ZonedDateTime.of(THURSDAY_DATE, LocalTime.MIDNIGHT, UTC);
        ZonedDateTime startDate = endDate.minusMonths(1);
        return new CalendarBuilder(CASE_START_DATE_MONTHS_AGO_AND_END_DATE_THU,
          startDate, endDate);

      case CASE_START_DATE_MONTHS_AGO_AND_END_DATE_WED:
        ZonedDateTime endDateWednesday = ZonedDateTime.of(WEDNESDAY_DATE, LocalTime.MIDNIGHT, UTC);
        ZonedDateTime startDateWednesday = endDateWednesday.minusMonths(1);
        return new CalendarBuilder(CASE_START_DATE_MONTHS_AGO_AND_END_DATE_THU,
          startDateWednesday, endDateWednesday);

      case CASE_START_DATE_FRI_AND_END_DATE_NEXT_MONTHS:
        ZonedDateTime startDateFriday = ZonedDateTime.of(FRIDAY_DATE, LocalTime.MIDNIGHT, UTC);
        ZonedDateTime endDateFriday = startDateFriday.plusMonths(1);
        return new CalendarBuilder(CASE_START_DATE_MONTHS_AGO_AND_END_DATE_THU,
          startDateFriday, endDateFriday);

      case CASE_MON_WED_FRI_OPEN_TUE_THU_CLOSED:
        LocalDate date = LocalDate.parse(queries.get(REQUESTED_DATE_PARAM));
        if (date.isEqual(THURSDAY_DATE)) {
          return new CalendarBuilder(new OpeningDayPeriodBuilder(CASE_MON_WED_FRI_OPEN_TUE_THU_CLOSED,
            createDayPeriod(createOpeningDay(new ArrayList<>(), WEDNESDAY_DATE, true, true)),
            createDayPeriod(createOpeningDay(new ArrayList<>(), THURSDAY_DATE, true, false)),
            createDayPeriod(createOpeningDay(new ArrayList<>(), FRIDAY_DATE, true, true))));
        }

        return new CalendarBuilder(new OpeningDayPeriodBuilder(CASE_MON_WED_FRI_OPEN_TUE_THU_CLOSED,
          createDayPeriod(createOpeningDay(new ArrayList<>(), MONDAY_DATE, true, true)),
          createDayPeriod(createOpeningDay(new ArrayList<>(), TUESDAY_DATE, true, false)),
          createDayPeriod(createOpeningDay(new ArrayList<>(), WEDNESDAY_DATE, true, true))));

      default:
        LocalDate requestedDate = LocalDate.parse(queries.get(REQUESTED_DATE_PARAM));
        return new CalendarBuilder(buildAllDayOpenCalenderResponse(requestedDate, serviceId));
    }
  }

  public static List<OpeningDayPeriod> getCurrentAndNextFakeOpeningDayByServId(String serviceId) {
    OpeningDayPeriodBuilder periodBuilder = fakeOpeningPeriods.get(serviceId);
    return Arrays.asList(periodBuilder.getCurrentPeriod(), periodBuilder.getLastPeriod());
  }

  public static OpeningDayPeriod getFirstFakeOpeningDayByServId(String serviceId) {
    return fakeOpeningPeriods.get(serviceId).getFirstPeriod();
  }

  public static OpeningDayPeriod getLastFakeOpeningDayByServId(String serviceId) {
    return fakeOpeningPeriods.get(serviceId).getLastPeriod();
  }
}
