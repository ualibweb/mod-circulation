package org.folio.circulation.domain;

import static java.util.concurrent.CompletableFuture.completedFuture;
import static org.folio.circulation.support.Result.of;
import static org.folio.circulation.support.Result.succeeded;
import static org.folio.circulation.support.ResultBinding.mapResult;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.folio.circulation.domain.policy.OverdueFineInterval;
import org.folio.circulation.domain.policy.OverdueFinePolicy;
import org.folio.circulation.domain.policy.OverdueFinePolicyRepository;
import org.folio.circulation.domain.representations.AccountStorageRepresentation;
import org.folio.circulation.domain.representations.FeeFineActionStorageRepresentation;
import org.folio.circulation.support.Clients;
import org.folio.circulation.support.ItemRepository;
import org.folio.circulation.support.Result;
import org.folio.circulation.support.ResultBinding;
import org.folio.circulation.support.http.server.WebContext;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;

public class OverdueFineCalculatorService {
  public static OverdueFineCalculatorService using(Clients clients) {
    return new OverdueFineCalculatorService(clients);
  }

  private final Repos repos;
  private final OverduePeriodCalculatorService overduePeriodCalculatorService;

  public OverdueFineCalculatorService(Repos repos,
    OverduePeriodCalculatorService overduePeriodCalculatorService) {
    this.repos = repos;
    this.overduePeriodCalculatorService = overduePeriodCalculatorService;
  }

  private OverdueFineCalculatorService(Clients clients) {
    this(
      new Repos(new OverdueFinePolicyRepository(clients),
        new AccountRepository(clients),
        new ItemRepository(clients, true, false, false),
        new FeeFineOwnerRepository(clients),
        new FeeFineRepository(clients),
        new UserRepository(clients),
        new FeeFineActionRepository(clients)),
      new OverduePeriodCalculatorService(new CalendarRepository(clients))
    );
  }

  public CompletableFuture<Result<CheckInProcessRecords>> calculateOverdueFine(
    CheckInProcessRecords records, WebContext context) {

    Loan loan = records.getLoan();

    if (loan == null || context == null) {
      return completedFuture(of(() -> records));
    }

    return calculateOverdueFine(loan, context.getUserId()).thenApply(mapResult(v -> records));
  }

  public CompletableFuture<Result<LoanAndRelatedRecords>> calculateOverdueFine(
    LoanAndRelatedRecords records, WebContext context) {

    Loan loan = records.getLoan();

    if (loan == null || context == null) {
      return completedFuture(of(() -> records));
    }

    return calculateOverdueFine(loan, context.getUserId()).thenApply(mapResult(v -> records));
  }

  private CompletableFuture<Result<Account>> calculateOverdueFine(Loan loan,
    String loggedInUserId) {

    return completedFuture(succeeded(loan))
      .thenComposeAsync(repos.overdueFinePolicyRepository::findOverdueFinePolicyForLoan)
      .thenCompose(r -> r.after(l -> getOverdueMinutes(l)
          .thenCompose(mr -> mr.after(minutes -> calculateOverdueFine(l, minutes)))
          .thenCompose(fr -> fr.after(fine -> this.createFeeFineRecord(l, fine, loggedInUserId)))
      ));
  }

  private CompletableFuture<Result<Integer>> getOverdueMinutes(Loan loan) {
    DateTime systemTime = loan.getReturnDate();
    if (systemTime == null) {
      systemTime = DateTime.now(DateTimeZone.UTC);
    }
    return overduePeriodCalculatorService.getMinutes(loan, systemTime);
  }

  private CompletableFuture<Result<Double>> calculateOverdueFine(Loan loan, Integer overdueMinutes) {
    double overdueFine = 0.0;

    OverdueFinePolicy overdueFinePolicy = loan.getOverdueFinePolicy();
    if (overdueMinutes > 0 && overdueFinePolicy != null) {
      Double maxFine = loan.wasDueDateChangedByRecall() ?
        overdueFinePolicy.getMaxOverdueRecallFine() :
        overdueFinePolicy.getMaxOverdueFine();

      OverdueFineInterval interval = overdueFinePolicy.getOverdueFineInterval();
      Double overdueFinePerInterval = loan.getOverdueFinePolicy().getOverdueFine();

      if (maxFine != null && interval != null && overdueFinePerInterval != null) {
        double numberOfIntervals = Math.ceil(overdueMinutes.doubleValue() /
          interval.getMinutes().doubleValue());
        overdueFine = overdueFinePerInterval * numberOfIntervals;

        if (maxFine > 0) {
          overdueFine = Math.min(overdueFine, maxFine);
        }
      }
    }

    return CompletableFuture.completedFuture(succeeded(overdueFine));
  }

  private CompletableFuture<Result<CalculationParameters>> lookupItemRelatedRecords(
    CalculationParameters params) {
    return repos.itemRepository.fetchItemRelatedRecords(succeeded(params.loan.getItem()))
      .thenApply(ResultBinding.mapResult(params::withItem));
  }

  private CompletableFuture<Result<CalculationParameters>> lookupFeeFineOwner(
    CalculationParameters params) {
    return repos.feeFineOwnerRepository.getFeeFineOwner(
      params.item.getLocation().getPrimaryServicePointId().toString())
      .thenApply(ResultBinding.mapResult(params::withOwner));
  }

  private CompletableFuture<Result<CalculationParameters>> lookupFeeFine(
    CalculationParameters params) {
    if (params.feeFineOwner == null) {
      return completedFuture(succeeded(params));
    }
    return repos.feeFineRepository.getOverdueFine(params.feeFineOwner.getId())
      .thenApply(ResultBinding.mapResult(params::withFeeFine))
      .thenCompose(r -> r.after(updatedParams -> {
        if (updatedParams.feeFine == null) {
          FeeFine feeFine = new FeeFine(UUID.randomUUID().toString(),
            updatedParams.feeFineOwner.getId(), FeeFine.OVERDUE_FINE_TYPE);
          return repos.feeFineRepository.create(feeFine)
            .thenApply(ResultBinding.mapResult(params::withFeeFine));
        }
        return completedFuture(succeeded(updatedParams));
      }));
  }

  private CompletableFuture<Result<CalculationParameters>> lookupLoggedInUser(
    CalculationParameters params, String loggedInUserId) {
    return repos.userRepository.getUser(loggedInUserId)
      .thenApply(ResultBinding.mapResult(params::withLoggedInUser));
  }

  private CompletableFuture<Result<Account>> createFeeFineRecord(Loan loan, Double fineAmount,
    String loggedInUserId) {

    if (fineAmount <= 0) {
      return failure();
    }

    return CompletableFuture.completedFuture(succeeded(
      new CalculationParameters(loan, null, null, null, null)))
      .thenCompose(r -> r.after(this::lookupItemRelatedRecords))
      .thenCompose(r -> r.after(this::lookupFeeFineOwner))
      .thenCompose(r -> r.after(this::lookupFeeFine))
      .thenCompose(r -> r.after(params -> this.lookupLoggedInUser(params, loggedInUserId)))
      .thenCompose(r -> r.after(params -> {
        if (params.item == null || params.feeFineOwner == null || params.feeFine == null) {
          return failure();
        }

        AccountStorageRepresentation accountRepresentation =
          new AccountStorageRepresentation(params.loan, params.item, params.feeFineOwner,
            params.feeFine, fineAmount);

        return repos.accountRepository.create(accountRepresentation)
          .thenCompose(rac -> rac.after(account -> this.createFeeFineAction(account, params)
            .thenApply(rfa -> rfa.map(feeFineAction -> account))));
      }));
  }

  private CompletableFuture<Result<FeeFineAction>> createFeeFineAction(
    Account account, CalculationParameters params) {

    return repos.feeFineActionRepository.create(new FeeFineActionStorageRepresentation(account,
      params.feeFineOwner, params.feeFine, account.getAmount(), account.getAmount(),
      params.loggedInUser));
  }

  private CompletableFuture<Result<Account>> failure() {
    return completedFuture(succeeded(Account.from(null)));
  }

  private static class CalculationParameters {
    final Loan loan;
    final Item item;
    final FeeFineOwner feeFineOwner;
    final FeeFine feeFine;
    final User loggedInUser;

    CalculationParameters(Loan loan, Item item, FeeFineOwner feeFineOwner, FeeFine feeFine,
      User loggedInUser) {

      this.loan = loan;
      this.item = item;
      this.feeFineOwner = feeFineOwner;
      this.feeFine = feeFine;
      this.loggedInUser = loggedInUser;
    }

    CalculationParameters withItem(Item item) {
      return new CalculationParameters(this.loan, item, this.feeFineOwner, this.feeFine,
        this.loggedInUser);
    }

    CalculationParameters withOwner(FeeFineOwner feeFineOwner) {
      return new CalculationParameters(this.loan, this.item, feeFineOwner, this.feeFine,
        this.loggedInUser);
    }

    CalculationParameters withFeeFine(FeeFine feeFine) {
      return new CalculationParameters(this.loan, this.item, this.feeFineOwner, feeFine,
        this.loggedInUser);
    }

    CalculationParameters withLoggedInUser(User loggedInUser) {
      return new CalculationParameters(this.loan, this.item, this.feeFineOwner, this.feeFine,
        loggedInUser);
    }
  }

  public static class Repos {
    private final OverdueFinePolicyRepository overdueFinePolicyRepository;
    private final AccountRepository accountRepository;
    private final ItemRepository itemRepository;
    private final FeeFineOwnerRepository feeFineOwnerRepository;
    private final FeeFineRepository feeFineRepository;
    private final UserRepository userRepository;
    private final FeeFineActionRepository feeFineActionRepository;

    Repos(OverdueFinePolicyRepository overdueFinePolicyRepository,
      AccountRepository accountRepository, ItemRepository itemRepository,
      FeeFineOwnerRepository feeFineOwnerRepository, FeeFineRepository feeFineRepository,
      UserRepository userRepository, FeeFineActionRepository feeFineActionRepository) {

      this.overdueFinePolicyRepository = overdueFinePolicyRepository;
      this.accountRepository = accountRepository;
      this.itemRepository = itemRepository;
      this.feeFineOwnerRepository = feeFineOwnerRepository;
      this.feeFineRepository = feeFineRepository;
      this.userRepository = userRepository;
      this.feeFineActionRepository = feeFineActionRepository;
    }
  }
}
