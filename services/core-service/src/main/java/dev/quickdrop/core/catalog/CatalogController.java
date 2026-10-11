package dev.quickdrop.core.catalog;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CatalogController {

    private final CatalogService catalog;
    private final BestsellerService bestsellers;

    public CatalogController(CatalogService catalog, BestsellerService bestsellers) {
        this.catalog = catalog;
        this.bestsellers = bestsellers;
    }

    @GetMapping("/api/stores/{storeId}/bestsellers")
    public List<BestsellerService.Bestseller> bestsellers(@PathVariable long storeId) {
        return bestsellers.bestsellers(storeId);
    }

    @GetMapping("/api/products/{id}")
    public ProductSnapshot product(@PathVariable long id) {
        return catalog.product(id);
    }

    @GetMapping("/api/stores/{storeId}/products")
    public List<CatalogService.StoreProduct> storeProducts(@PathVariable long storeId,
                                                           @RequestParam(required = false) String category,
                                                           @RequestParam(defaultValue = "50") int limit) {
        return catalog.storeProducts(storeId, category, Math.min(limit, 500));
    }

    @PutMapping("/admin/products/{id}/price")
    public ProductSnapshot updatePrice(@PathVariable long id, @RequestBody Map<String, Integer> body) {
        return catalog.updatePrice(id, body.get("pricePaise"));
    }
}
