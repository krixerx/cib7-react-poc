/**
 * Client for the curated Estonian vehicle registry served by the
 * backend's VehicleRegistryController under
 * /api/public/vehicle-registry — see redomain plan D4.
 *
 * The PartA form uses this to populate its vehicle dropdown. The engine's
 * Task_GetPrice service task hits the same backend path server-side via
 * the http-connector, so the SPA and the engine see the same catalog at
 * any moment.
 *
 * Module / file rename (objectsApi.ts → vehicleRegistryApi.ts;
 * `listPricedObjects` → `listVehicles`; `PricedObject` → `Vehicle`) is
 * deferred to PR #8 alongside the rest of the structural renames so this
 * PR stays focused on the behavior change.
 */

const VEHICLES_URL = '/api/public/vehicle-registry/vehicles';

/** Dropdown row — only what the form renders. */
export interface PricedObject {
  id: string;
  name: string;
}

interface VehicleResponse {
  vin: string;
  make: string;
  model: string;
  year: number;
  ageYears: number;
  value: number;
  fuelType: string;
}

/** Formats "VW Golf 1.4 TSI 2018 · €8,400" for the dropdown row. */
function formatLabel(v: VehicleResponse): string {
  const price = v.value.toLocaleString('et-EE', {
    style: 'currency',
    currency: 'EUR',
    maximumFractionDigits: 0,
  });
  return `${v.make} ${v.model} ${v.year} · ${price}`;
}

export async function listPricedObjects(): Promise<PricedObject[]> {
  const res = await fetch(VEHICLES_URL);
  if (!res.ok) {
    throw new Error(`Vehicle registry ${res.status} ${res.statusText}`);
  }
  const vehicles = (await res.json()) as VehicleResponse[];
  return vehicles.map((v) => ({ id: v.vin, name: formatLabel(v) }));
}
