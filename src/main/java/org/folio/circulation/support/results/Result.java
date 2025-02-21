package org.folio.circulation.support.results;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.apache.commons.lang3.BooleanUtils.isTrue;
import static org.folio.circulation.support.results.CommonFailures.failedDueToServerError;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.folio.circulation.support.HttpFailure;
import org.folio.circulation.support.ThrowingSupplier;

public interface Result<T> {
  /**
   * Creates a successful result with the supplied value
   * unless an exception is thrown
   *
   * @param supplier of the result value
   * @return successful result or failed result with error
   */
  static <T> Result<T> of(ThrowingSupplier<T, Exception> supplier) {
    try {
      return succeeded(supplier.get());
    } catch (Exception e) {
      return failedDueToServerError(e);
    }
  }

  /**
   * Creates a completed future with successful result with the supplied value
   * unless an exception is thrown
   *
   * @param supplier of the result value
   * @return completed future with successful result or failed result with error
   */
  static <T> CompletableFuture<Result<T>> ofAsync(ThrowingSupplier<T, Exception> supplier) {
    return completedFuture(of(supplier));
  }

  /**
   * Combines results from all the lists of results, of all elements succeed.
   * Otherwise, returns  failure, first failed element takes precedence
   *
   * @param results lists of results to combine
   * @return either failure of the first failed result,
   * or successful result with values collected to list
   */
  @SafeVarargs
  static <T> Result<List<T>> combineAll(List<Result<T>>... results) {
    return Stream.of(results).flatMap(Collection::stream)
      .map(r -> r.map(Stream::of))
      .reduce(of(Stream::empty), Result::combineResultStream)
      .map(stream -> stream.collect(Collectors.toList()));
  }

  /**
   * Combines two result streams, if all of elements succeed.
   * Otherwise, returns either failure, first failed element takes precedence
   *
   * @param firstResult  first result stream
   * @param secondResult second result stream
   * @return either failure of the first failed result,
   * or successful result with values collected to stream
   */
  static <T> Result<Stream<T>> combineResultStream(
    Result<Stream<T>> firstResult, Result<Stream<T>> secondResult) {

    return firstResult.combine(secondResult, Stream::concat);
  }

  /**
   * Combines this and another result together, if both succeed.
   * Otherwise, returns either failure, this result takes precedence
   *
   * @param otherResult the other result
   * @param combiner function to combine the values together
   * @return either failure from this result, failure from the other
   * or successful result with the values combined
   */
  default <U, V> Result<V> combine(Result<U> otherResult, BiFunction<T, U, V> combiner) {
    return next(firstValue ->
      otherResult.map(secondValue ->
        combiner.apply(firstValue, secondValue)));
  }

  /**
   * Combines this and another result together, if both succeed.
   * Otherwise, returns either failure, this result takes precedence
   *
   * @param otherResult the other result
   * @param combiner function to combine the values together
   * @return either failure from this result, failure from the other
   * or result of the combination
   */
  default <U, V> Result<V> combineToResult(Result<U> otherResult,
    BiFunction<T, U, Result<V>> combiner) {

    return next(firstValue ->
      otherResult.next(secondValue ->
        combiner.apply(firstValue, secondValue)));
  }

  /**
   * Combines a result together with the result of an action, if both succeed.
   * If the first result is a failure then it is returned, and the action is not invoked
   * otherwise if the result of the action is a failure it is returned
   *
   * @param nextAction the action to invoke if the current result succeeded
   * @param combiner function to combine the values together
   * @return either failure from the first result, failure from the action
   * or successful result with the values combined
   */
  default <U, V> CompletableFuture<Result<V>> combineAfter(
    Function<T, CompletableFuture<Result<U>>> nextAction,
    BiFunction<T, U, V> combiner) {

    return after(nextAction)
      .thenApply(actionResult -> combine(actionResult, combiner));
  }

  /**
   * Combines a result together with the result of an action, if both succeed.
   * If the first result is a failure then it is returned, and the action is not invoked
   * otherwise if the result of the action is a failure it is returned
   *
   * @param nextAction the action to invoke if the current result succeeded
   * @param combiner function to combine the values together
   * @return either failure from the first result, failure from the action
   * or successful result with the values combined
   */
  default <U, V> CompletableFuture<Result<V>> combineAfter(
    Supplier<CompletableFuture<Result<U>>> nextAction,
    BiFunction<T, U, V> combiner) {

    return combineAfter(u -> nextAction.get(), combiner);
  }

  /**
   * Fail a result when a condition evaluates to true
   *
   * Responds with the result of the failure function when condition evaluates to true
   * Responds with success of the prior result when condition evaluates to false
   * Executes neither if the condition evaluation fails
   * Forwards on failure if previous result failed
   *
   * @param condition on which to decide upon
   * @param failure executed to create failure reason when condition evaluates to true
   * @return success when condition is false, failure otherwise
   */
  default CompletableFuture<Result<T>> failAfter(
    Function<T, CompletableFuture<Result<Boolean>>> condition,
    Function<T, HttpFailure> failure) {

    return after(MappingFunctions.when(condition,
      value -> completedFuture(failed(failure.apply(value))),
      value -> completedFuture(succeeded(value))));
  }

  /**
   * Allows branching between two paths based upon the outcome of a condition
   *
   * Executes the whenTrue function when condition evaluates to true
   * Executes the whenFalse function when condition evaluates to false
   * Executes neither if the condition evaluation fails
   * Forwards on failure if previous result failed
   *
   * @param condition on which to branch upon
   * @param whenTrue executed when condition evaluates to true
   * @param whenFalse executed when condition evaluates to false
   * @return Result of whenTrue or whenFalse, unless previous result failed
   */
  default Result<T> nextWhen(Function<T, Result<Boolean>> condition,
    Function<T, Result<T>> whenTrue, Function<T, Result<T>> whenFalse) {

    return next(value ->
      when(condition.apply(value),
        () -> whenTrue.apply(value),
        () -> whenFalse.apply(value)));
  }

  /**
   * Allows branching between two paths based upon the outcome of a condition
   *
   * Executes the whenTrue function when condition evaluates to true
   * Executes the whenFalse function when condition evaluates to false
   * Executes neither if the condition evaluation fails
   * Forwards on failure if previous result failed
   *
   * @param condition on which to branch upon
   * @param whenTrue executed when condition evaluates to true
   * @param whenFalse executed when condition evaluates to false
   * @return Result of whenTrue or whenFalse, unless previous result failed
   */
  static <R> Result<R> when(Result<Boolean> condition,
    Supplier<Result<R>> whenTrue, Supplier<Result<R>> whenFalse) {

    return condition.next(result -> isTrue(result)
      ? whenTrue.get()
      : whenFalse.get());
  }

  /**
   * Fail a result when a condition evaluates to true
   *
   * Responds with the result of the failure function when condition evaluates to true
   * Responds with success of the prior result when condition evaluates to false
   * Executes neither if the condition evaluation fails
   * Forwards on failure if previous result failed
   *
   * @param condition on which to decide upon
   * @param failure executed to create failure reason when condition evaluates to true
   * @return success when condition is false, failure otherwise
   */
  default Result<T> failWhen(Function<T, Result<Boolean>> condition,
    Function<T, HttpFailure> failure) {

    return nextWhen(condition, value -> failed(failure.apply(value)), Result::succeeded);
  }

  boolean failed();

  T value();
  HttpFailure cause();

  default boolean succeeded() {
    return !failed();
  }

  static <T> Result<T> succeeded(T value) {
    return new SuccessfulResult<>(value);
  }

  static <T> Result<T> failed(HttpFailure cause) {
    return new FailedResult<>(cause);
  }

  default <R> CompletableFuture<Result<R>> after(
    Function<T, CompletableFuture<Result<R>>> action) {

    if(failed()) {
      return completedFuture(failed(cause()));
    }

    try {
      return action.apply(value())
        .exceptionally(CommonFailures::failedDueToServerError);
    } catch (Exception e) {
      return completedFuture(failedDueToServerError(e));
    }
  }

  /**
   * Apply the next action to the value of the result
   *
   * Responds with the result of applying the next action to the current value
   * unless current result is failed or the application of action fails e.g. throws an exception
   *
   * @param action action to take after this result
   * @return success when result succeeded and action is applied successfully, failure otherwise
   */
  default <R> Result<R> next(Function<T, Result<R>> action) {
    if(failed()) {
      return failed(cause());
    }

    try {
      return action.apply(value());
    } catch (Exception e) {
      return failedDueToServerError(e);
    }
  }

  /**
   * Map the value of a result to a new value
   *
   * Responds with a new result with the outcome of applying the map to the current value
   * unless current result is failed or the mapping fails e.g. throws an exception
   *
   * @param map function to apply to value of result
   * @return success when result succeeded and map is applied successfully, failure otherwise
   */
  default <U> Result<U> map(Function<T, U> map) {
    return next(value -> succeeded(map.apply(value)));
  }

  /**
   * Map the cause of a failed result to a new result (of the same type)
   *
   * Responds with a new result with the outcome of applying the map to the current
   * failure cause unless current result has succeeded (or the mapping throws an exception)
   *
   * @param map function to apply to value of result
   * @return success when result succeeded and map is applied successfully, failure otherwise
   */
  default Result<T> mapFailure(Function<HttpFailure, Result<T>> map) {
    if(succeeded()) {
      return Result.of(this::value);
    }

    try {
      return map.apply(cause());
    }
    catch(Exception e) {
      return failedDueToServerError(e);
    }
  }

  default T orElse(T other) {
    return succeeded()
      ? value()
      : other;
  }

  default void applySideEffect(Consumer<T> onSuccess, Consumer<HttpFailure> onFailure) {
    if (succeeded()) {
      onSuccess.accept(value());
    }
    else {
      onFailure.accept(cause());
    }
  }

  default Result<Void> mapEmpty() {
    return map(value -> null);
  }

  /**
   * Returns a BiFunction that combines two results and executes another BiFunction
   * on un-flatted values.
   *
   * @param resultValuesHandler - Handler to handle result values.
   * @param <T>                 - First result type.
   * @param <U>                 - Second result type.
   * @param <R>                 - Resulting type.
   * @return A BiFunction that combines two results and executes a resultValuesHandler on un-flatted values.
   */
  static <T, U, R> BiFunction<Result<T>, Result<U>, Result<R>> combined(
    BiFunction<T, U, Result<R>> resultValuesHandler) {

    return (firstResult, secondResult) ->
      firstResult.combineToResult(secondResult, resultValuesHandler);
  }
}
