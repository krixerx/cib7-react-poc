/**
 * Client for api.restful-api.dev — the external API the "Look up vehicle in
 * registry" service task calls (POC stub for the Estonian vehicle registry).
 * The owner form uses this to populate its vehicle dropdown.
 *
 * A later PR moves this lookup behind our own backend endpoint that returns
 * a curated catalog of believable Estonian-market vehicles (see redomain
 * plan D4).
 */

const OBJECTS_URL = 'https://api.restful-api.dev/objects';

/** A product whose `data` carries a price — the only ones worth selecting. */
export interface PricedObject {
  id: string;
  name: string;
}

interface RawObject {
  id: string;
  name: string;
  data: Record<string, unknown> | null;
}

/**
 * Fetches the objects from api.restful-api.dev, keeping only those whose
 * `data` contains a `price`. Selecting one of these means the service task's
 * GET /objects/{id} will have a `data.price` for the Spin response mapping.
 */
export async function listPricedObjects(): Promise<PricedObject[]> {
  const res = await fetch(OBJECTS_URL);
  if (!res.ok) {
    throw new Error(`restful-api.dev ${res.status} ${res.statusText}`);
  }
  const objects = (await res.json()) as RawObject[];
  return objects
    .filter((o) => o.data != null && o.data.price != null)
    .map((o) => ({ id: o.id, name: o.name }));
}
