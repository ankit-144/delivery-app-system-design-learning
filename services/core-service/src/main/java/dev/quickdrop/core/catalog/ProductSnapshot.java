package dev.quickdrop.core.catalog;

/** What we cache about a product. Immutable, so a cached copy can never be half-updated. */
public record ProductSnapshot(long id, String sku, String name, String category, int pricePaise) {

    static ProductSnapshot of(Product p) {
        return new ProductSnapshot(p.getId(), p.getSku(), p.getName(), p.getCategory(), p.getPricePaise());
    }
}
