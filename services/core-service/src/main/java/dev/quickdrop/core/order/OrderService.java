package dev.quickdrop.core.order;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import dev.quickdrop.common.toggles.StrategySelector;
import dev.quickdrop.common.toggles.ToggleRegistry;
import dev.quickdrop.core.catalog.CatalogService;
import dev.quickdrop.core.catalog.ProductSnapshot;
import dev.quickdrop.core.catalog.Store;
import dev.quickdrop.core.config.CoreToggles;
import dev.quickdrop.core.customer.CustomerDirectory;
import dev.quickdrop.core.db.ReadRouter;
import dev.quickdrop.core.inventory.InventoryReservation;
import dev.quickdrop.core.inventory.OrderLine;
import dev.quickdrop.core.notification.NotificationService;
import dev.quickdrop.core.rider.RiderAssignment;
import dev.quickdrop.core.rider.RiderCandidate;
import dev.quickdrop.core.rider.RiderLocator;
import dev.quickdrop.core.web.NotFoundException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Placing an order, step by step:
 *
 * <pre>
 *  ┌ aroundTransaction (JVM / Redis lock, only for some inventory strategies)
 *  │ ┌ BEGIN
 *  │ │ 1. claim the Idempotency-Key            (if order-idempotency=on)
 *  │ │ 2. reserve stock for every line         (inventory-reservation)
 *  │ │ 3. insert the order and its items
 *  │ │ 4. find nearby riders, claim one        (rider-locator, rider-assignment)
 *  │ │ 5. send the SMS                         (only if notification-mode=inline-in-tx — a bad idea)
 *  │ └ COMMIT
 *  └ release lock
 *    6. send the SMS                           (inline / async-memory)
 * </pre>
 *
 * The transaction boundary is written out with a {@link TransactionTemplate} instead of
 * {@code @Transactional} so you can see exactly what runs inside it.
 */
@Service
public class OrderService {

    private static final int RIDER_CANDIDATES = 20;

    private final CatalogService catalog;
    private final CustomerDirectory customers;
    private final OrderRepository orders;
    private final IdempotencyDao idempotency;
    private final LineOrdering lineOrdering;
    private final StrategySelector<InventoryReservation> reservations;
    private final StrategySelector<RiderLocator> locators;
    private final StrategySelector<RiderAssignment> assignments;
    private final NotificationService notifications;
    private final ReadRouter readRouter;
    private final ToggleRegistry toggles;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final Timer placementTimer;
    private final io.micrometer.core.instrument.DistributionSummary distance;

    public OrderService(CatalogService catalog, CustomerDirectory customers, OrderRepository orders,
                        IdempotencyDao idempotency, LineOrdering lineOrdering,
                        StrategySelector<InventoryReservation> reservations, StrategySelector<RiderLocator> locators,
                        StrategySelector<RiderAssignment> assignments, NotificationService notifications,
                        ReadRouter readRouter, ToggleRegistry toggles, TransactionTemplate tx, MeterRegistry meters) {
        this.catalog = catalog;
        this.customers = customers;
        this.orders = orders;
        this.idempotency = idempotency;
        this.lineOrdering = lineOrdering;
        this.reservations = reservations;
        this.locators = locators;
        this.assignments = assignments;
        this.notifications = notifications;
        this.readRouter = readRouter;
        this.toggles = toggles;
        this.tx = tx;
        this.meters = meters;
        this.distance = io.micrometer.core.instrument.DistributionSummary.builder("quickdrop.rider.assignment.distance")
                .description("Distance from the store to the assigned rider")
                .baseUnit("km")
                .serviceLevelObjectives(0.1, 0.2, 0.3, 0.5, 0.75, 1, 1.5, 2, 3, 5)
                .register(meters);
        this.placementTimer = Timer.builder("quickdrop.order.placement")
                .description("Time to place an order, end to end")
                .register(meters);
        // Start the common outcomes at 0 so the first ones are visible to rate() (see ApiExceptionHandler).
        java.util.List.of("placed", "placed_pending_rider", "replayed", "OutOfStockException").forEach(this::preRegister);
    }

    private void preRegister(String result) {
        Counter.builder("quickdrop.orders").tag("result", result).register(meters);
    }

    public PlacedOrder placeOrder(PlaceOrderRequest request, String idempotencyKey) {
        return placementTimer.record(() -> {
            try {
                PlacedOrder placed = doPlaceOrder(request, idempotencyKey);
                count(placed.replayed() ? "replayed" : placed.order().riderId() == null ? "placed_pending_rider" : "placed");
                return placed;
            } catch (RuntimeException e) {
                count(e.getClass().getSimpleName());
                throw e;
            }
        });
    }

    private PlacedOrder doPlaceOrder(PlaceOrderRequest request, String idempotencyKey) {
        Store store = catalog.store(request.storeId());
        CustomerDirectory.CustomerContact customer = customers.find(request.customerId());
        List<OrderLine> lines = lineOrdering.apply(request.items());
        Map<Long, ProductSnapshot> products = catalog.products(lines.stream().map(OrderLine::productId).toList());
        Map<Long, String> names = products.values().stream()
                .collect(java.util.stream.Collectors.toMap(ProductSnapshot::id, ProductSnapshot::name));
        InventoryReservation reservation = reservations.current();
        boolean useIdempotency = idempotencyKey != null && toggles.is(CoreToggles.ORDER_IDEMPOTENCY, "on");

        PlacedOrder placed = reservation.aroundTransaction(store.getId(), () -> tx.execute(status -> {
            if (useIdempotency && !idempotency.claim(customer.id(), idempotencyKey)) {
                return replay(customer, idempotencyKey);
            }
            reservation.reserve(store.getId(), lines);

            Order order = new Order(customer.id(), store.getId(), idempotencyKey);
            lines.forEach(line -> order.addItem(new OrderItem(order, catalog.productRef(line.productId()), line.quantity(),
                    products.get(line.productId()).pricePaise())));
            orders.save(order);
            if (useIdempotency) {
                idempotency.attachOrder(customer.id(), idempotencyKey, order.getId());
            }

            assignRider(order, store);
            PlacedOrder result = new PlacedOrder(OrderView.of(order, names), customer.phone(), false);
            notifications.insideTransaction(result);
            return result;
        }));

        if (!placed.replayed()) {
            readRouter.wrote(customer.id());   // read-your-writes: this customer's next reads go to the primary
            notifications.afterCommit(placed);
        }
        return placed;
    }

    /** Also used by the background job that retries orders still waiting for a rider. */
    void assignRider(Order order, Store store) {
        RiderLocator locator = locators.current();
        List<RiderCandidate> candidates = locator.nearestAvailable(store, RIDER_CANDIDATES);
        Optional<Long> rider = assignments.current().assign(order.getId(), candidates);
        rider.ifPresent(order::assignRider);
        rider.ifPresent(id -> locator.onRiderBusy(id, store.getCityId()));
        // How far the chosen rider is from the store: a locator that knows only some riders picks farther ones.
        rider.flatMap(id -> candidates.stream().filter(c -> c.riderId() == id).findFirst())
                .ifPresent(c -> distance.record(c.distanceKm()));
        meters.counter("quickdrop.rider.assignments", "result", rider.isPresent() ? "assigned" : "no_rider_available").increment();
    }

    private PlacedOrder replay(CustomerDirectory.CustomerContact customer, String key) {
        long orderId = idempotency.orderFor(customer.id(), key).orElseThrow();
        Order existing = orders.findById(orderId).orElseThrow();
        return new PlacedOrder(OrderView.of(existing), customer.phone(), true);
    }

    public OrderView get(long orderId) {
        return tx.execute(status -> OrderView.of(orders.findById(orderId).orElseThrow(() -> new NotFoundException("order", orderId))));
    }

    private void count(String result) {
        Counter.builder("quickdrop.orders").tag("result", result).register(meters).increment();
    }
}
