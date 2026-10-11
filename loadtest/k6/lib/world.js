// Static facts about the seed data (V2__seed_data.sql), so scripts can build realistic requests.

export const STORES = {
  1: { city: 1, lat: 12.9784, lon: 77.6408 },
  2: { city: 1, lat: 12.9352, lon: 77.6245 },
  3: { city: 2, lat: 19.1136, lon: 72.8697 },
  4: { city: 2, lat: 19.0596, lon: 72.8295 },
  5: { city: 3, lat: 28.6315, lon: 77.2167 },
  6: { city: 3, lat: 28.5245, lon: 77.2066 },
};

export const CATEGORIES = ['dairy', 'bakery', 'snacks', 'beverages', 'fruits', 'vegetables', 'personal-care', 'household', 'staples'];
export const PRODUCTS = 500;
export const RIDERS = 6000;

/** Rider g was seeded around store 1 + g % 6. */
export function storeOfRider(riderId) {
  return 1 + (riderId % 6);
}
