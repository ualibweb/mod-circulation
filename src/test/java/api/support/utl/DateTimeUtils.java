package api.support.utl;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.function.Supplier;

import org.joda.time.DateTime;

import io.vertx.core.json.JsonObject;

public class DateTimeUtils {
  public static LocalDate getLocalDatePropertyForDateWithTime(JsonObject representation,
    String propertyName) {

    if (representation != null && representation.containsKey(propertyName)) {
      return LocalDate.parse(representation.getString(propertyName),
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSz"));
    } else {
      return null;
    }
  }

  public static <T> T executeWithFixedDateTime(Supplier<T> supplier, DateTime dateTime) {
    org.joda.time.DateTimeUtils.setCurrentMillisFixed(dateTime.getMillis());
    T result = supplier.get();
    org.joda.time.DateTimeUtils.setCurrentMillisSystem();
    return result;
  }
}
